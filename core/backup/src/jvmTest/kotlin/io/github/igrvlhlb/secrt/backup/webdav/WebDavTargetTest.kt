package io.github.igrvlhlb.secrt.backup.webdav

import io.github.igrvlhlb.secrt.backup.BackupAuthException
import io.github.igrvlhlb.secrt.backup.BackupNetworkException
import io.github.igrvlhlb.secrt.backup.BackupServerException
import io.github.igrvlhlb.secrt.backup.BackupTargetConfig
import io.github.igrvlhlb.secrt.backup.Device
import io.github.igrvlhlb.secrt.backup.draft
import io.github.igrvlhlb.secrt.backup.titles
import io.github.igrvlhlb.secrt.vault.Credential
import kotlinx.coroutines.test.runTest
import java.net.ServerSocket
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WebDavTargetTest {

    private val server = FakeWebDavServer()

    @AfterTest
    fun stop() = server.close()

    private fun target(password: String = "app-password") = WebDavTarget(BackupTargetConfig.WebDav(server.folderUrl, "alice", password))

    @Test
    fun filesRoundTripAndMissingFoldersAreCreated() = runTest {
        target().use { dav ->
            assertEquals(emptyList(), dav.list("secrt/"))
            assertNull(dav.get("secrt/v/log/a-1.seg"))

            dav.put("secrt/v/log/a-1.seg", byteArrayOf(1, 2, 3))
            dav.put("secrt/v/log/a-2.seg", byteArrayOf())
            dav.put("secrt/v/vault.key", "{}".encodeToByteArray())
            assertContentEquals(byteArrayOf(1, 2, 3), dav.get("secrt/v/log/a-1.seg"))
            assertContentEquals(byteArrayOf(), dav.get("secrt/v/log/a-2.seg"))
            assertEquals(listOf("v/"), dav.list("secrt/"))
            assertEquals(setOf("log/", "vault.key"), dav.list("secrt/v/").toSet())
            assertEquals(setOf("a-1.seg", "a-2.seg"), dav.list("secrt/v/log/").toSet())

            dav.put("secrt/v/vault.key", "{\"new\":1}".encodeToByteArray())
            assertEquals("{\"new\":1}", dav.get("secrt/v/vault.key")?.decodeToString())
            dav.delete("secrt/v/log/a-2.seg")
            dav.delete("secrt/v/log/a-2.seg")
            assertEquals(listOf("a-1.seg"), dav.list("secrt/v/log/"))
        }
        // Each folder level was created once.
        assertEquals(3, server.requests.count { it.startsWith("MKCOL") })
    }

    @Test
    fun unsafePathsAreRefused() = runTest {
        target().use { dav ->
            assertFailsWith<IllegalArgumentException> { dav.get("../etc/passwd") }
            assertFailsWith<IllegalArgumentException> { dav.put("secrt/a b", byteArrayOf()) }
            assertFailsWith<IllegalArgumentException> { dav.list("secrt/../") }
        }
    }

    @Test
    fun wrongPasswordIsReportedAsSuch() = runTest {
        target(password = "nope").use { dav -> assertFailsWith<BackupAuthException> { dav.list("secrt/") } }
        assertFailsWith<BackupAuthException> { WebDavTarget.connect(server.folderUrl, "alice", "nope") }
    }

    @Test
    fun connectChecksTheFolderAndLeavesNoTrace() = runTest {
        val config = WebDavTarget.connect(server.folderUrl.removeSuffix("/"), "alice", "app-password")
        assertEquals(server.folderUrl, config.url)
        // It proved it can write, then cleaned up.
        assertTrue(server.files.isEmpty())
        assertTrue(server.requests.any { it.startsWith("PUT") })
    }

    @Test
    fun connectFindsNextcloudFolderFromTheServerAddress() = runTest {
        FakeWebDavServer(root = "/remote.php/dav/files/alice@example.com/", username = "alice@example.com").use { nextcloud ->
            val config = WebDavTarget.connect(nextcloud.origin, "alice@example.com", "app-password")
            assertEquals("${nextcloud.origin}/remote.php/dav/files/alice@example.com/", config.url)
            assertEquals("alice@example.com @ 127.0.0.1:${nextcloud.origin.substringAfterLast(':')}", config.description)

            WebDavTarget(config).use { dav ->
                dav.put("secrt/x/vault.key", byteArrayOf(7))
                assertEquals(listOf("x/"), dav.list("secrt/"))
            }
            assertContentEquals(byteArrayOf(7), nextcloud.files["secrt/x/vault.key"])
        }
    }

    @Test
    fun connectRefusesAPlainWebServer() = runTest {
        assertFailsWith<BackupServerException> { WebDavTarget.connect("${server.origin}/not-dav/", "alice", "app-password") }
    }

    @Test
    fun unreachableServerIsANetworkError() = runTest {
        val port = ServerSocket(0).use { it.localPort }
        WebDavTarget(BackupTargetConfig.WebDav("http://127.0.0.1:$port/", "a", "b")).use { dav ->
            assertFailsWith<BackupNetworkException> { dav.list("secrt/") }
        }
    }

    @Test
    fun urlsAreNormalized() {
        assertEquals("https://cloud.example.com/", normalizeFolderUrl("cloud.example.com"))
        assertEquals("https://cloud.example.com/dav/", normalizeFolderUrl(" https://cloud.example.com/dav?x=1#y "))
        assertEquals("http://nas.local:8080/backups/", normalizeFolderUrl("http://nas.local:8080/backups"))
        assertFailsWith<IllegalArgumentException> { normalizeFolderUrl("ftp://nas.local/") }
        assertFalse(BackupTargetConfig.WebDav("http://nas.local/", "u", "p").isEncryptedTransport)
        assertTrue(BackupTargetConfig.WebDav("HTTPS://nas.local/", "u", "p").isEncryptedTransport)
    }

    @Test
    fun multistatusFromDifferentServersIsUnderstood() {
        // Nextcloud/Sabre: "d:" prefix, absolute paths.
        val sabre = """<?xml version="1.0"?><d:multistatus xmlns:d="DAV:" xmlns:s="http://sabredav.org/ns">
            <d:response><d:href>/remote.php/dav/files/bob/secrt/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat></d:response>
            <d:response><d:href>/remote.php/dav/files/bob/secrt/vault%2Ekey</d:href><d:propstat><d:prop><d:resourcetype/></d:prop></d:propstat></d:response>
            <d:response><d:href>/remote.php/dav/files/bob/secrt/log/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat></d:response>
            </d:multistatus>"""
        assertEquals(listOf("vault.key", "log/"), childNames("/remote.php/dav/files/bob/secrt/", parseMultistatus(sabre)))

        // Default namespace, full URLs, entities, and a trailing-slash-less self entry.
        val other = """<multistatus xmlns="DAV:">
            <response><href>https://h.example/a%20b/secrt</href><propstat><prop><resourcetype><collection /></resourcetype></prop></propstat></response>
            <response><href>https://h.example/a%20b/secrt/x&amp;y</href><propstat><prop><resourcetype></resourcetype></prop></propstat></response>
            <response><href>https://h.example/a%20b/secrt/deep/er/file</href></response>
            </multistatus>"""
        assertEquals(listOf("x&y"), childNames("/a b/secrt/", parseMultistatus(other)))
    }

    @Test
    fun backupRoundTripOverWebDav() = runTest {
        val config = WebDavTarget.connect(server.folderUrl, "alice", "app-password")
        val phone = Device()
        phone.session.create("pw", withRecoveryKey = false)
        val photo = phone.store.addAttachment("PIXELS".encodeToByteArray(), "photo.png", "image/png")
        phone.store.saveEntry("e1", draft("Lisbon", "A SECRET day").copy(attachments = listOf(photo)))
        phone.backup.saveTarget(config)
        config.open().use { phone.backup.push(it) }

        assertTrue(server.files.keys.any { it.endsWith("/vault.key") })
        server.files.forEach { (path, bytes) ->
            assertFalse("SECRET" in bytes.decodeToString() || "PIXELS" in bytes.decodeToString(), "$path holds plaintext")
        }

        val laptop = Device()
        config.open().use { dav ->
            val vaultId = laptop.backup.findDiaries(dav).single()
            laptop.backup.restore(dav, vaultId, Credential.Password("pw"), config)
        }
        assertEquals(setOf("Lisbon"), laptop.store.titles())
        assertContentEquals("PIXELS".encodeToByteArray(), laptop.store.readAttachment(photo.blobId))

        laptop.store.saveEntry("e2", draft("Porto"))
        val saved = laptop.backup.savedTarget()!!
        saved.open().use { laptop.backup.sync(it) }
        config.open().use { assertEquals(1, phone.backup.sync(it).downloaded) }
        assertEquals(setOf("Lisbon", "Porto"), phone.store.titles())
    }
}
