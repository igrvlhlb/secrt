package io.github.igrvlhlb.secrt.storage

import io.github.igrvlhlb.secrt.model.ConflictKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiaryStoreTest {

    private fun draft(
        title: String = "A day",
        body: String = "It was fine.",
        folderId: String? = null,
        labelIds: Set<String> = emptySet(),
    ) = EntryDraft(title, body, LocalDate(2026, 10, 30), folderId, labelIds, emptyList())

    @Test
    fun savesAndReadsAnEntry() = runTest {
        val store = TestReplica.create().store
        val label = store.createLabel("travel", 0xFF0000FF)
        val folder = store.createFolder("2026", null)

        store.saveEntry("e1", draft(folderId = folder, labelIds = setOf(label)))

        val entry = store.getEntry("e1")!!
        assertEquals("A day", entry.title)
        assertEquals(folder, entry.folderId)
        assertEquals(listOf("travel"), entry.labels.map { it.name })
        assertEquals(listOf("e1"), store.observeEntries(EntryFilter(folder = FolderFilter.In(folder))).first().map { it.id })
        assertEquals(emptyList(), store.observeEntries(EntryFilter(folder = FolderFilter.In(null))).first())
    }

    @Test
    fun quickEditsAmendButLaterEditsAddRevisions() = runTest {
        val replica = TestReplica.create()
        val store = replica.store

        store.saveEntry("e1", draft(body = "v1"))
        store.saveEntry("e1", draft(body = "v2"))
        assertEquals(1, store.revisionCount())

        replica.clock.advanceMinutes(10)
        store.saveEntry("e1", draft(body = "v3"))
        assertEquals(2, store.revisionCount())
        assertEquals("v3", store.getEntry("e1")!!.body)
    }

    @Test
    fun sharedRevisionsAreNeverAmended() = runTest {
        val a = TestReplica.create().store
        val b = TestReplica.create().store
        a.saveEntry("e1", draft(body = "v1"))
        sync(a, b)

        a.saveEntry("e1", draft(body = "v2")) // within the amend window, but v1 already left the device
        sync(a, b)

        assertEquals("v2", b.getEntry("e1")!!.body)
        assertEquals(2, a.revisionCount())
    }

    @Test
    fun deletingAnEntryRemovesIt() = runTest {
        val store = TestReplica.create().store
        store.saveEntry("e1", draft())

        store.deleteEntry("e1")

        assertNull(store.getEntry("e1"))
        assertEquals(emptyList(), store.observeEntries().first())
    }

    @Test
    fun searchAndLabelFilters() = runTest {
        val store = TestReplica.create().store
        val work = store.createLabel("work", 1)
        store.saveEntry("e1", draft(title = "Beach", body = "100% sunny", labelIds = setOf(work)))
        store.saveEntry("e2", draft(title = "Office", body = "meetings"))

        assertEquals(listOf("e1"), store.observeEntries(EntryFilter(query = "beach")).first().map { it.id })
        assertEquals(listOf("e1"), store.observeEntries(EntryFilter(query = "100%")).first().map { it.id })
        assertEquals(emptyList(), store.observeEntries(EntryFilter(query = "1_0")).first())
        assertEquals(listOf("e1"), store.observeEntries(EntryFilter(labelId = work)).first().map { it.id })

        store.deleteLabel(work)
        assertEquals(emptyList(), store.getEntry("e1")!!.labels)
    }

    @Test
    fun deletingAFolderMovesItsContentsUp() = runTest {
        val store = TestReplica.create().store
        val parent = store.createFolder("parent", null)
        val child = store.createFolder("child", parent)
        val grandchild = store.createFolder("grandchild", child)
        store.saveEntry("e1", draft(folderId = child))

        store.deleteFolder(child)

        assertEquals(parent, store.getEntry("e1")!!.folderId)
        val folders = store.observeFolders().first().associateBy { it.id }
        assertFalse(child in folders)
        assertEquals(parent, folders.getValue(grandchild).parentId)
    }

    @Test
    fun cannotMoveFolderIntoItsDescendant() = runTest {
        val store = TestReplica.create().store
        val a = store.createFolder("a", null)
        val b = store.createFolder("b", a)

        assertFailsWith<IllegalArgumentException> { store.moveFolder(a, b) }
        store.moveFolder(b, null)
        assertNull(store.observeFolders().first().single { it.id == b }.parentId)
    }

    @Test
    fun attachmentsAreEncryptedAtRest() = runTest {
        val replica = TestReplica.create()
        val bytes = "a very private photo".encodeToByteArray()

        val ref = replica.store.addAttachment(bytes, "photo.jpg", "image/jpeg")

        assertContentEquals(bytes, replica.store.readAttachment(ref.blobId))
        val onDisk = replica.fileSystem.read("/vault/blobs".toPath() / ref.blobId) { readByteArray() }
        assertFalse(onDisk.decodeToString().contains("private"))
        assertEquals(ref.blobId, replica.store.addAttachment(bytes, "copy.jpg", "image/jpeg").blobId)
    }

    @Test
    fun replicasConvergeAfterConcurrentEdits() = runTest {
        val a = TestReplica.create()
        val b = TestReplica.create()
        a.store.saveEntry("e1", draft(title = "Shared", body = "line 1\nline 2\nline 3"))
        sync(a.store, b.store)

        a.clock.advanceMinutes(10)
        b.clock.advanceMinutes(11)
        a.store.saveEntry("e1", draft(title = "Renamed on A", body = "line 1\nline 2\nline 3"))
        b.store.saveEntry("e1", draft(title = "Shared", body = "line 1\nline 2\nline 3\nadded on B"))
        b.store.saveEntry("e2", draft(title = "Only on B"))
        sync(a.store, b.store)

        val onA = a.store.getEntry("e1")!!
        assertEquals(onA, b.store.getEntry("e1"))
        assertEquals("Renamed on A", onA.title)
        assertEquals("line 1\nline 2\nline 3\nadded on B", onA.body)
        assertNull(onA.conflict)
        assertEquals(a.store.observeEntries().first(), b.store.observeEntries().first())
    }

    @Test
    fun overlappingEditsAreFlaggedAndASaveResolvesThem() = runTest {
        val a = TestReplica.create()
        val b = TestReplica.create()
        a.store.saveEntry("e1", draft(body = "same line"))
        sync(a.store, b.store)

        a.store.saveEntry("e1", draft(body = "A's version"))
        b.clock.advanceMinutes(1)
        b.store.saveEntry("e1", draft(body = "B's version"))
        sync(a.store, b.store)

        val conflicted = a.store.getEntry("e1")!!
        assertEquals(ConflictKind.CONCURRENT_EDIT, conflicted.conflict)
        assertEquals("B's version", conflicted.body)
        assertEquals(setOf("A's version", "B's version"), a.store.conflictVersions("e1").map { it.body }.toSet())

        a.store.saveEntry("e1", draft(body = "A's version, then B's version"))
        sync(a.store, b.store)

        assertNull(b.store.getEntry("e1")!!.conflict)
        assertEquals("A's version, then B's version", b.store.getEntry("e1")!!.body)
    }

    @Test
    fun syncingTwiceChangesNothing() = runTest {
        val a = TestReplica.create().store
        val b = TestReplica.create().store
        a.saveEntry("e1", draft())
        sync(a, b)

        assertTrue(b.revisionsSince(a.versionVector()).isEmpty())
        assertTrue(a.revisionsSince(b.versionVector()).isEmpty())
    }

    private suspend fun DiaryStore.revisionCount() = versionVector().values.sum().toInt()
}
