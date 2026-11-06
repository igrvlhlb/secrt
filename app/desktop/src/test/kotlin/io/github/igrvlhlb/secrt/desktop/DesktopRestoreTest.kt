package io.github.igrvlhlb.secrt.desktop

import io.github.igrvlhlb.secrt.crypto.Argon2Params
import io.github.igrvlhlb.secrt.crypto.SodiumCrypto
import io.github.igrvlhlb.secrt.session.AppSession
import io.github.igrvlhlb.secrt.session.SessionState
import io.github.igrvlhlb.secrt.storage.DiaryStore
import io.github.igrvlhlb.secrt.storage.EntryDraft
import io.github.igrvlhlb.secrt.storage.SqlCipherDriverFactory
import io.github.igrvlhlb.secrt.vault.Credential
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import okio.sink
import okio.source
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** A phone's backup restored through the desktop app's real wiring: files on disk, encrypted database. */
class DesktopRestoreTest {

    private val root: File = Files.createTempDirectory("secrt-desktop").toFile()

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    private val AppSession.store: DiaryStore get() = assertIs<SessionState.Unlocked>(state.value).store

    private fun draft(title: String, day: Int) = EntryDraft(title, "Notes about $title", LocalDate(2026, 10, day), null, emptySet(), emptyList())

    /** Stands in for the phone: same session code, its own directory, fast key derivation. */
    private fun phone(): AppSession {
        val dir = File(root, "phone").apply { mkdirs() }
        return AppSession(
            fileSystem = FileSystem.SYSTEM,
            vaultDirectory = dir.toOkioPath() / "vault",
            driverFactory = SqlCipherDriverFactory(File(dir, "secrt.db")),
            cryptoProvider = { SodiumCrypto.create() },
            ioDispatcher = Dispatchers.IO,
            argon2Params = Argon2Params.Insecure,
        )
    }

    @Test
    fun restoresAPhoneBackupAndReopensItAfterARestart() = runTest {
        val phone = phone()
        phone.create("phone-password", withRecoveryKey = false)
        val photo = phone.store.addAttachment("pixels".encodeToByteArray(), "photo.png", "image/png")
        phone.store.saveEntry("e1", draft("First day", 1).copy(attachments = listOf(photo)))
        phone.store.saveEntry("e2", draft("Lisbon", 2))
        val backup = File(root, "phone.secrt")
        phone.exportTo(backup.sink())
        phone.lock()

        val dataDirectory = File(root, "desktop")
        val desktop = desktopSession(dataDirectory)
        assertEquals(SessionState.NoVault, desktop.state.value)
        desktop.restore(backup.source(), Credential.Password("phone-password"))

        assertEquals(setOf("First day", "Lisbon"), desktop.store.observeEntries().first().map { it.title }.toSet())
        assertContentEquals("pixels".encodeToByteArray(), desktop.store.readAttachment(photo.blobId))
        desktop.store.saveEntry("e3", draft("Written on the Mac", 3))
        desktop.lock()

        // Everything is in the data directory, and the database is encrypted.
        assertNotNull(File(dataDirectory, "vault/vault.key").takeIf { it.exists() })
        val db = File(dataDirectory, "secrt.db").readBytes()
        assertFalse(db.decodeToString(endIndex = 16).startsWith("SQLite format 3"))
        assertFalse("Lisbon" in db.decodeToString())

        // A restart finds the diary locked, and it opens with the phone's password.
        val restarted = desktopSession(dataDirectory)
        assertEquals(SessionState.Locked, restarted.state.value)
        restarted.unlock("phone-password")
        assertEquals("Written on the Mac", restarted.store.getEntry("e3")?.title)
        restarted.lock()
    }

    @Test
    fun onlyOneInstanceCanUseADataDirectory() {
        val dataDirectory = File(root, "desktop")
        val first = assertNotNull(lockDataDirectory(dataDirectory))
        assertNull(lockDataDirectory(dataDirectory))
        first.close()
        lockDataDirectory(dataDirectory)?.close() ?: error("Lock not released")
    }
}
