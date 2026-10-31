package io.github.igrvlhlb.secrt.sync

import io.github.igrvlhlb.secrt.model.ConflictKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecordResolverTest {

    @Test
    fun linearHistoryResolvesToLatest() {
        val h = History()
        val v1 = h.rev("A", 1, emptyList(), entry(title = "v1"))
        h.rev("A", 2, listOf(v1), entry(title = "v2"))

        val r = h.resolve()

        assertEquals("v2", r.value?.title)
        assertNull(r.conflict)
        assertEquals(1, r.heads.size)
    }

    @Test
    fun concurrentEditsToDifferentFieldsMergeCleanly() {
        val h = History()
        val v1 = h.rev("A", 1, emptyList(), entry(title = "v1", labels = setOf("x")))
        h.rev("A", 2, listOf(v1), entry(title = "renamed on A", labels = setOf("x")))
        h.rev("B", 3, listOf(v1), entry(title = "v1", labels = setOf("x", "y"), body = "line 1\nline 2\nline 3\nline 4"))

        val r = h.resolve()

        assertEquals("renamed on A", r.value?.title)
        assertEquals(setOf("x", "y"), r.value?.labelIds)
        assertEquals("line 1\nline 2\nline 3\nline 4", r.value?.body)
        assertNull(r.conflict)
    }

    @Test
    fun sameFieldChangedOnBothSidesTakesTheNewer() {
        val h = History()
        val v1 = h.rev("A", 1, emptyList(), entry(title = "v1"))
        h.rev("A", 5, listOf(v1), entry(title = "from A (newer)"))
        h.rev("B", 3, listOf(v1), entry(title = "from B"))

        assertEquals("from A (newer)", h.resolve().value?.title)
    }

    @Test
    fun overlappingBodyEditsAreFlaggedAndShowTheNewer() {
        val h = History()
        val v1 = h.rev("A", 1, emptyList(), entry(body = "line 1\nline 2\nline 3"))
        h.rev("A", 2, listOf(v1), entry(body = "line 1\nA's line\nline 3"))
        h.rev("B", 3, listOf(v1), entry(body = "line 1\nB's line\nline 3"))

        val r = h.resolve()

        assertEquals(ConflictKind.CONCURRENT_EDIT, r.conflict)
        assertEquals("line 1\nB's line\nline 3", r.value?.body)
        assertEquals(2, r.heads.size)
    }

    @Test
    fun resolvingRevisionCollapsesHeads() {
        val h = History()
        val v1 = h.rev("A", 1, emptyList(), entry(body = "x"))
        val a = h.rev("A", 2, listOf(v1), entry(body = "a"))
        val b = h.rev("B", 3, listOf(v1), entry(body = "b"))
        h.rev("A", 4, listOf(a, b), entry(body = "a and b"))

        val r = h.resolve()

        assertNull(r.conflict)
        assertEquals("a and b", r.value?.body)
    }

    @Test
    fun editWinsOverConcurrentDeleteAndIsFlagged() {
        val h = History()
        val v1 = h.rev("A", 1, emptyList(), entry(title = "v1"))
        h.rev("A", 2, listOf(v1), null)
        h.rev("B", 3, listOf(v1), entry(title = "kept"))

        val r = h.resolve()

        assertEquals("kept", r.value?.title)
        assertEquals(ConflictKind.EDITED_AFTER_DELETE, r.conflict)
    }

    @Test
    fun deletedRecordRemembersLastValue() {
        val h = History()
        val v1 = h.rev("A", 1, emptyList(), entry(title = "v1"))
        h.rev("A", 2, listOf(v1), null)

        val r = h.resolve()

        assertTrue(r.deleted)
        assertEquals("v1", r.lastKnownValue?.title)
    }

    @Test
    fun resultDoesNotDependOnRevisionOrder() {
        val h = History()
        val v1 = h.rev("A", 1, emptyList(), entry(title = "t", labels = setOf("a")))
        h.rev("A", 2, listOf(v1), entry(title = "t2", labels = setOf("a", "b")))
        h.rev("B", 3, listOf(v1), entry(title = "t", labels = emptySet(), body = "line 0\nline 1\nline 2\nline 3"))
        h.rev("C", 4, listOf(v1), entry(title = "t3", labels = setOf("a", "c")))

        val expected = h.resolve()
        repeat(20) { seed ->
            val shuffled = h.revisions.shuffled(kotlin.random.Random(seed))
            val r = RecordResolver.resolve(shuffled, { io.github.igrvlhlb.secrt.model.SecrtJson.decodeFromString<io.github.igrvlhlb.secrt.model.EntryPayload>(it) }, EntryMerger)
            assertEquals(expected.value, r.value)
            assertEquals(expected.conflict, r.conflict)
        }
        assertEquals(setOf("b", "c"), expected.value?.labelIds)
    }
}
