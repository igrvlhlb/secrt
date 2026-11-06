package io.github.igrvlhlb.secrt.backup

import io.github.igrvlhlb.secrt.archive.CorruptArchiveException
import io.github.igrvlhlb.secrt.archive.UnsupportedArchiveException
import io.github.igrvlhlb.secrt.session.SessionState
import io.github.igrvlhlb.secrt.vault.Credential
import io.github.igrvlhlb.secrt.vault.InvalidCredentialsException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CloudBackupTest {

    private val target = MemoryTarget()
    private val phone = Device()

    private suspend fun Device.restoreFrom(target: BackupTarget, credential: Credential = Credential.Password("pw")): BackupResult {
        val vaultId = backup.findDiaries(target).single()
        return backup.restore(target, vaultId, credential)
    }

    private fun segmentNames() = target.files.keys.filter { "/log/" in it }

    @Test
    fun pushThenRestoreOnAFreshDevice() = runTest {
        phone.session.create("pw", withRecoveryKey = false)
        val photo = phone.store.addAttachment("pixels".encodeToByteArray(), "photo.png", "image/png")
        phone.store.saveEntry("e1", draft("Lisbon").copy(attachments = listOf(photo)))
        val folder = phone.store.createFolder("Trips", null)

        val pushed = phone.backup.push(target)
        assertEquals(2, pushed.uploaded)
        val vaultId = phone.backup.findDiaries(target).single()
        assertEquals(
            setOf("manifest.json", "vault.key", "blobs/${photo.blobId}", "log/${phone.store.deviceId}-1.seg").map { "secrt/$vaultId/$it" }.toSet(),
            target.files.keys,
        )
        assertNotNull(phone.backup.lastBackupAt(target))

        val laptop = Device()
        val config = BackupTargetConfig.WebDav("https://dav.example/", "me", "secret")
        val restored = laptop.backup.restore(target, vaultId, Credential.Password("pw"), config)
        assertEquals(2, restored.downloaded)
        assertEquals("Lisbon", laptop.store.getEntry("e1")?.title)
        assertContentEquals("pixels".encodeToByteArray(), laptop.store.readAttachment(photo.blobId))
        assertEquals(listOf(folder), laptop.store.observeFolders().first().map { it.id })
        assertTrue(laptop.store.deviceId != phone.store.deviceId)
        // The restored diary remembers its backup, and knows the backup already holds everything.
        assertEquals(config, laptop.backup.savedTarget())
        val putsBefore = target.puts
        assertEquals(0, laptop.backup.push(target).uploaded)
        assertEquals(putsBefore, target.puts)
    }

    @Test
    fun nothingOnTheTargetIsPlaintext() = runTest {
        phone.session.create("pw", withRecoveryKey = false)
        val photo = phone.store.addAttachment("SECRET-PIXELS".encodeToByteArray(), "photo.png", "image/png")
        phone.store.saveEntry("e1", draft("SECRET-TITLE", "SECRET-BODY").copy(attachments = listOf(photo)))
        phone.store.createFolder("SECRET-FOLDER", null)
        phone.backup.push(target)

        target.files.forEach { (path, bytes) ->
            val text = bytes.decodeToString()
            assertFalse("SECRET" in text || "photo.png" in text, "$path holds plaintext")
        }
    }

    @Test
    fun devicesExchangeChangesBothWays() = runTest {
        phone.session.create("pw", withRecoveryKey = false)
        phone.store.saveEntry("shared", draft("Shared"))
        phone.backup.push(target)
        val laptop = Device()
        laptop.restoreFrom(target)

        laptop.store.saveEntry("l", draft("Written on the laptop"))
        assertEquals(1, laptop.backup.push(target).uploaded)
        phone.store.saveEntry("p", draft("Written on the phone"))

        val synced = phone.backup.sync(target)
        assertEquals(1, synced.downloaded)
        assertEquals(1, synced.uploaded)
        assertEquals(BackupResult(downloaded = 1), laptop.backup.pull(target))

        val expected = setOf("Shared", "Written on the phone", "Written on the laptop")
        assertEquals(expected, phone.store.titles())
        assertEquals(expected, laptop.store.titles())
        // Each device only ever wrote its own segment files.
        assertEquals(
            setOf("${phone.store.deviceId}-1.seg", "${phone.store.deviceId}-2.seg", "${laptop.store.deviceId}-1.seg"),
            segmentNames().map { it.substringAfterLast('/') }.toSet(),
        )
    }

    @Test
    fun repeatedOperationsChangeNothing() = runTest {
        phone.session.create("pw", withRecoveryKey = false)
        phone.store.saveEntry("e1", draft("hello"))
        phone.backup.push(target)
        val files = target.files.mapValues { it.value.toList() }

        assertEquals(BackupResult(), phone.backup.push(target))
        assertEquals(BackupResult(), phone.backup.sync(target))
        assertEquals(files, target.files.mapValues { it.value.toList() })

        val laptop = Device()
        laptop.restoreFrom(target)
        assertEquals(BackupResult(), laptop.backup.pull(target))
        assertEquals(BackupResult(), laptop.backup.sync(target))
    }

    @Test
    fun pushedRevisionsAreNeverAmendedInPlace() = runTest {
        phone.session.create("pw", withRecoveryKey = false)
        phone.store.saveEntry("e1", draft("first"))
        phone.backup.push(target)
        // Within the amend window: without the watermark this would rewrite the pushed revision.
        phone.store.saveEntry("e1", draft("second"))
        assertEquals(1, phone.backup.push(target).uploaded)

        val laptop = Device()
        laptop.restoreFrom(target)
        assertEquals("second", laptop.store.getEntry("e1")?.title)
        assertEquals(mapOf(phone.store.deviceId to 2L), laptop.store.versionVector())
    }

    @Test
    fun tamperedSegmentIsRejectedAndNothingIsApplied() = runTest {
        phone.session.create("pw", withRecoveryKey = false)
        phone.store.saveEntry("e1", draft("one"))
        phone.backup.push(target)
        val laptop = Device()
        laptop.restoreFrom(target)
        phone.store.saveEntry("e2", draft("two"))
        phone.backup.push(target)

        val segment = segmentNames().single { it.endsWith("-2.seg") }
        val original = target.files.getValue(segment)
        target.files[segment] = original.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        assertFailsWith<CorruptArchiveException> { laptop.backup.pull(target) }
        assertEquals(setOf("one"), laptop.store.titles())

        // A valid segment under another name (replayed, or moved from another diary) is refused too.
        target.files[segment] = target.files.getValue(segmentNames().single { it.endsWith("-1.seg") })
        assertFailsWith<CorruptArchiveException> { laptop.backup.pull(target) }
        assertEquals(setOf("one"), laptop.store.titles())

        target.files[segment] = original
        assertEquals(1, laptop.backup.pull(target).downloaded)
        assertEquals(setOf("one", "two"), laptop.store.titles())
    }

    @Test
    fun tamperedAttachmentIsRejected() = runTest {
        phone.session.create("pw", withRecoveryKey = false)
        val photo = phone.store.addAttachment("pixels".encodeToByteArray(), "photo.png", "image/png")
        phone.store.saveEntry("e1", draft("pic").copy(attachments = listOf(photo)))
        phone.backup.push(target)
        val blob = target.files.keys.single { "/blobs/" in it }
        target.files[blob] = target.files.getValue(blob).also { it[30] = (it[30] + 1).toByte() }

        val laptop = Device()
        assertFailsWith<CorruptArchiveException> { laptop.restoreFrom(target) }
        assertEquals(SessionState.NoVault, laptop.session.state.value)
    }

    @Test
    fun failedRestoreLeavesNothingBehind() = runTest {
        phone.session.create("pw", withRecoveryKey = false)
        val attachments = listOf("one", "two").map { phone.store.addAttachment(it.encodeToByteArray(), "$it.png", "image/png") }
        phone.store.saveEntry("e1", draft("hello").copy(attachments = attachments))
        phone.backup.push(target)

        val laptop = Device()
        assertFailsWith<InvalidCredentialsException> { laptop.restoreFrom(target, Credential.Password("wrong")) }
        assertEquals(SessionState.NoVault, laptop.session.state.value)
        assertEquals(emptyList(), laptop.leftovers)

        val segment = segmentNames().single()
        val original = target.files.getValue(segment)
        target.files[segment] = original.copyOf(original.size - 1)
        assertFailsWith<CorruptArchiveException> { laptop.restoreFrom(target) }
        assertEquals(SessionState.NoVault, laptop.session.state.value)
        assertEquals(emptyList(), laptop.leftovers)
        target.files[segment] = original

        // Fails after the first attachment was already downloaded and stored.
        val lastBlob = target.files.keys.filter { "/blobs/" in it }.max()
        val blob = target.files.getValue(lastBlob)
        target.files[lastBlob] = blob.copyOf().also { it[30] = (it[30] + 1).toByte() }
        assertFailsWith<CorruptArchiveException> { laptop.restoreFrom(target) }
        assertEquals(SessionState.NoVault, laptop.session.state.value)
        assertEquals(emptyList(), laptop.leftovers)
        target.files[lastBlob] = blob

        assertFailsWith<NoBackupFoundException> { laptop.backup.restore(target, "00000000-0000-0000-0000-000000000000", Credential.Password("pw")) }
        assertEquals(emptyList(), laptop.leftovers)

        laptop.restoreFrom(target)
        assertEquals("hello", laptop.store.getEntry("e1")?.title)
    }

    @Test
    fun restoreWithRecoveryKeyAsksForANewPassword() = runTest {
        val recoveryKey = phone.session.create("pw", withRecoveryKey = true)!!
        phone.store.saveEntry("e1", draft("hello"))
        phone.backup.push(target)

        val laptop = Device()
        laptop.restoreFrom(target, Credential.RecoveryKey(recoveryKey))
        assertTrue((laptop.session.state.value as SessionState.Unlocked).viaRecoveryKey)
        assertEquals("hello", laptop.store.getEntry("e1")?.title)
    }

    @Test
    fun vaultKeyIsUploadedOnlyWhenItChangedOnThisDevice() = runTest {
        phone.session.create("pw", withRecoveryKey = false)
        phone.backup.push(target)
        val laptop = Device()
        laptop.restoreFrom(target)
        val vaultKey = target.files.keys.single { it.endsWith("/vault.key") }

        phone.session.changePassword("pw", "new password")
        phone.backup.push(target)
        val changed = target.files.getValue(vaultKey).decodeToString()

        // The laptop never saw the change; its push must not bring the old password back.
        laptop.store.saveEntry("l", draft("from the laptop"))
        laptop.backup.push(target)
        assertEquals(changed, target.files.getValue(vaultKey).decodeToString())

        val tablet = Device()
        assertFailsWith<InvalidCredentialsException> { tablet.restoreFrom(target, Credential.Password("pw")) }
        tablet.restoreFrom(target, Credential.Password("new password"))
        assertEquals(setOf("from the laptop"), tablet.store.titles())
    }

    @Test
    fun aDeletedBackupIsRebuiltOnTheNextPush() = runTest {
        phone.session.create("pw", withRecoveryKey = false)
        phone.store.saveEntry("e1", draft("hello"))
        phone.backup.push(target)
        target.files.clear()

        assertEquals(1, phone.backup.push(target).uploaded)
        val laptop = Device()
        laptop.restoreFrom(target)
        assertEquals(setOf("hello"), laptop.store.titles())
    }

    @Test
    fun pullFromAnEmptyTargetDoesNothing() = runTest {
        phone.session.create("pw", withRecoveryKey = false)
        assertEquals(BackupResult(), phone.backup.pull(target))
        assertTrue(target.files.isEmpty())
        assertEquals(emptyList(), phone.backup.findDiaries(target))
    }

    @Test
    fun backupsOfANewerFormatAreRefused() = runTest {
        phone.session.create("pw", withRecoveryKey = false)
        phone.backup.push(target)
        val manifest = target.files.keys.single { it.endsWith("/manifest.json") }
        target.files[manifest] = target.files.getValue(manifest).decodeToString().replace("\"format\":1", "\"format\":2").encodeToByteArray()

        assertFailsWith<UnsupportedArchiveException> { phone.backup.pull(target) }
        assertFailsWith<UnsupportedArchiveException> { phone.backup.push(target) }
        assertFailsWith<UnsupportedArchiveException> { Device().restoreFrom(target) }
    }

    @Test
    fun targetSettingsAreKeptEncryptedWithTheDiary() = runTest {
        phone.session.create("pw", withRecoveryKey = false)
        assertNull(phone.backup.savedTarget())
        val config = BackupTargetConfig.WebDav("https://dav.example/", "me", "app-password")
        phone.backup.saveTarget(config)
        assertEquals(config, phone.backup.savedTarget())
        assertFalse("app-password" in config.toString())

        phone.session.lock()
        phone.session.unlock("pw")
        assertEquals(config, phone.backup.savedTarget())
        phone.backup.saveTarget(null)
        assertNull(phone.backup.savedTarget())
    }

    @Test
    fun largeHistoriesAreSplitIntoSeveralSegments() = runTest {
        phone.session.create("pw", withRecoveryKey = false)
        val labels = (1..2100).map { phone.store.createLabel("label $it", 0) }
        assertEquals(2100, phone.backup.push(target).uploaded)
        assertEquals(2, segmentNames().size)

        val laptop = Device()
        assertEquals(2100, laptop.restoreFrom(target).downloaded)
        assertEquals(labels.toSet(), laptop.store.observeLabels().first().map { it.id }.toSet())
    }
}
