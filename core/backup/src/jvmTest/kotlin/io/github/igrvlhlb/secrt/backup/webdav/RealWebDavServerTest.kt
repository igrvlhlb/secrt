package io.github.igrvlhlb.secrt.backup.webdav

import io.github.igrvlhlb.secrt.backup.CloudBackup
import io.github.igrvlhlb.secrt.backup.draft
import io.github.igrvlhlb.secrt.backup.titles
import io.github.igrvlhlb.secrt.crypto.SodiumCrypto
import io.github.igrvlhlb.secrt.session.AppSession
import io.github.igrvlhlb.secrt.session.SessionState
import io.github.igrvlhlb.secrt.storage.DiaryStore
import io.github.igrvlhlb.secrt.storage.SqlCipherDriverFactory
import io.github.igrvlhlb.secrt.vault.Credential
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * End to end against a real WebDAV server, with the desktop app's wiring (SQLCipher database,
 * real files, OkHttp). Skipped unless `SECRT_WEBDAV_URL` is set, e.g. for a local wsgidav:
 * ```
 * SECRT_WEBDAV_URL=http://127.0.0.1:18080/dav SECRT_WEBDAV_USER=alice SECRT_WEBDAV_PASSWORD=… \
 *   ./gradlew :core:backup:jvmTest --tests '*RealWebDavServerTest*'
 * ```
 * Leaves the backup on the server, to inspect it.
 */
class RealWebDavServerTest {

    private val url = System.getenv("SECRT_WEBDAV_URL")
    private val user = System.getenv("SECRT_WEBDAV_USER").orEmpty()
    private val password = System.getenv("SECRT_WEBDAV_PASSWORD").orEmpty()

    private class DesktopLike {
        val dir: File = Files.createTempDirectory("secrt-it").toFile()
        val session = AppSession(
            fileSystem = FileSystem.SYSTEM,
            vaultDirectory = dir.toOkioPath() / "vault",
            driverFactory = SqlCipherDriverFactory(File(dir, "secrt.db")),
            cryptoProvider = { SodiumCrypto.create() },
            ioDispatcher = Dispatchers.IO,
        )
        val backup = CloudBackup(session)
        val store: DiaryStore get() = assertIs<SessionState.Unlocked>(session.state.value).store
    }

    @Test
    fun backupRestoreAndSyncThroughARealServer() = runBlocking {
        if (url == null) return@runBlocking
        val config = WebDavTarget.connect(url, user, password)
        println("Connected: $config")

        val desktop = DesktopLike()
        desktop.session.create("correct horse battery", withRecoveryKey = true)
        val photo = desktop.store.addAttachment("PLAINTEXT-PIXELS".encodeToByteArray(), "photo.png", "image/png")
        desktop.store.saveEntry("e1", draft("PLAINTEXT-TITLE", "PLAINTEXT-BODY").copy(attachments = listOf(photo)))
        desktop.backup.saveTarget(config)
        println("Pushed: " + config.open().use { desktop.backup.push(it) })

        val laptop = DesktopLike()
        config.open().use { target ->
            val vaultId = laptop.backup.findDiaries(target).single()
            println("Restored $vaultId: " + laptop.backup.restore(target, vaultId, Credential.Password("correct horse battery"), config))
        }
        assertEquals(setOf("PLAINTEXT-TITLE"), laptop.store.titles())
        assertContentEquals("PLAINTEXT-PIXELS".encodeToByteArray(), laptop.store.readAttachment(photo.blobId))

        laptop.store.saveEntry("e2", draft("Written on the laptop"))
        println("Laptop sync: " + laptop.backup.savedTarget()!!.open().use { laptop.backup.sync(it) })
        desktop.store.saveEntry("e3", draft("Written on the desktop"))
        println("Desktop sync: " + config.open().use { desktop.backup.sync(it) })
        println("Laptop sync: " + config.open().use { laptop.backup.sync(it) })

        val expected = setOf("PLAINTEXT-TITLE", "Written on the laptop", "Written on the desktop")
        assertEquals(expected, desktop.store.titles())
        assertEquals(expected, laptop.store.titles())
        desktop.session.lock()
        laptop.session.lock()
    }
}
