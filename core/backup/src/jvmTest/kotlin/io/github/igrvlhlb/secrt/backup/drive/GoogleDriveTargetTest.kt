package io.github.igrvlhlb.secrt.backup.drive

import io.github.igrvlhlb.secrt.backup.BackupServerException
import io.github.igrvlhlb.secrt.backup.BackupTargetConfig
import io.github.igrvlhlb.secrt.backup.Device
import io.github.igrvlhlb.secrt.backup.draft
import io.github.igrvlhlb.secrt.backup.titles
import io.github.igrvlhlb.secrt.vault.Credential
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GoogleDriveTargetTest {

    private val drive = FakeGoogleDrive(pageLimit = 2)
    private val signIn = FakeSignIn()
    private val account = BackupTargetConfig.GoogleDrive("alice@gmail.com")

    @AfterTest
    fun stop() = drive.close()

    private fun target() = GoogleDriveTarget(account, signIn, endpoints = drive.endpoints, retryDelaysMillis = listOf(0, 0, 0))

    @Test
    fun filesRoundTripUnderTheirWholePathAsName() = runTest {
        target().use { gd ->
            assertEquals(emptyList(), gd.list("secrt/"))
            assertNull(gd.get("secrt/v/log/a-1.seg"))

            gd.put("secrt/v/log/a-1.seg", byteArrayOf(1, 2, 3))
            gd.put("secrt/v/log/a-2.seg", byteArrayOf())
            gd.put("secrt/v/vault.key", "{}".encodeToByteArray())
            assertContentEquals(byteArrayOf(1, 2, 3), gd.get("secrt/v/log/a-1.seg"))
            assertContentEquals(byteArrayOf(), gd.get("secrt/v/log/a-2.seg"))
            assertEquals(listOf("v/"), gd.list("secrt/"))
            assertEquals(setOf("log/", "vault.key"), gd.list("secrt/v/").toSet())
            assertEquals(setOf("a-1.seg", "a-2.seg"), gd.list("secrt/v/log/").toSet())

            gd.put("secrt/v/vault.key", "{\"new\":1}".encodeToByteArray())
            assertEquals("{\"new\":1}", gd.get("secrt/v/vault.key")?.decodeToString())
            gd.delete("secrt/v/log/a-2.seg")
            gd.delete("secrt/v/log/a-2.seg")
            assertEquals(listOf("a-1.seg"), gd.list("secrt/v/log/"))
        }
        // Flat in appDataFolder, one file per path; rewriting updated the file in place.
        assertEquals(setOf("secrt/v/log/a-1.seg", "secrt/v/vault.key"), drive.files.values.map { it.name }.toSet())
        assertEquals(3, drive.requests.count { it == "POST /upload/drive/v3/files multipart" })
        assertEquals(1, drive.requests.count { it.startsWith("PATCH") && it.endsWith("media") })
    }

    @Test
    fun aNewTargetFindsFilesAcrossPages() = runTest {
        repeat(5) { drive.addFile("secrt/v/log/b-$it.seg", byteArrayOf(it.toByte())) }
        drive.addFile("not a secrt file", byteArrayOf())
        target().use { gd ->
            assertEquals((0..4).map { "b-$it.seg" }.toSet(), gd.list("secrt/v/log/").toSet())
            assertContentEquals(byteArrayOf(3), gd.get("secrt/v/log/b-3.seg"))
            assertEquals(listOf("secrt/"), gd.list(""))
        }
        assertEquals(3, drive.requests.count { it == "GET /drive/v3/files" })
    }

    @Test
    fun largeFilesUseResumableUploads() = runTest {
        val big = Random.nextBytes(6 * 1024 * 1024)
        val bigger = Random.nextBytes(7 * 1024 * 1024)
        target().use { gd ->
            gd.put("secrt/v/blobs/x", big)
            gd.put("secrt/v/blobs/x", bigger)
        }
        target().use { assertContentEquals(bigger, it.get("secrt/v/blobs/x")) }
        assertEquals(1, drive.files.size)
        assertEquals(listOf("POST /upload/drive/v3/files resumable", "PATCH /upload/drive/v3/files/id1 resumable"), drive.requests.filter { "resumable" in it })
    }

    @Test
    fun aRefusedTokenIsReplacedOnce() = runTest {
        signIn.tokens = ArrayDeque(listOf("expired", "token-1"))
        target().use { gd ->
            gd.put("secrt/a", byteArrayOf(1))
            assertContentEquals(byteArrayOf(1), gd.get("secrt/a"))
        }
        assertEquals(listOf(null, "expired"), signIn.staleTokens)

        // A token refused twice means the access is gone: the user has to connect again.
        signIn.tokens = ArrayDeque(listOf("revoked"))
        assertFailsWith<GoogleAuthException> { target().use { it.list("") } }
    }

    @Test
    fun rateLimitsAndPassingFailuresAreRetried() = runTest {
        drive.failNext += 403 to FakeGoogleDrive.error(403, "Rate Limit Exceeded", "userRateLimitExceeded")
        drive.failNext += 503 to FakeGoogleDrive.error(503, "Backend Error", "backendError")
        drive.failNext += 429 to FakeGoogleDrive.error(429, "Too Many Requests", "rateLimitExceeded")
        target().use { gd -> gd.put("secrt/a", byteArrayOf(1)) }
        assertEquals(1, drive.files.size)

        repeat(4) { drive.failNext += 503 to FakeGoogleDrive.error(503, "Backend Error", "backendError") }
        assertFailsWith<BackupServerException> { target().use { it.list("") } }
    }

    @Test
    fun errorsAreExplained() = runTest {
        drive.failNext += 403 to FakeGoogleDrive.error(403, "The user's Drive storage quota has been exceeded.", "storageQuotaExceeded")
        val full = assertFailsWith<BackupServerException> { target().use { it.list("") } }
        assertEquals("Your Google storage is full", full.message)

        drive.failNext += 403 to FakeGoogleDrive.error(403, "Drive API has not been used", "accessNotConfigured")
        val disabled = assertFailsWith<BackupServerException> { target().use { it.list("") } }
        assertTrue("isn't enabled" in disabled.message!!)
    }

    @Test
    fun duplicateNamesReadTheNewestAndAreCleanedUpOnWrite() = runTest {
        drive.addFile("secrt/v/vault.key", "old".encodeToByteArray())
        drive.addFile("secrt/v/vault.key", "new".encodeToByteArray())
        target().use { gd ->
            assertEquals(listOf("vault.key"), gd.list("secrt/v/"))
            assertEquals("new", gd.get("secrt/v/vault.key")?.decodeToString())
            gd.put("secrt/v/vault.key", "newest".encodeToByteArray())
        }
        assertEquals(listOf("newest"), drive.files.values.map { it.bytes.decodeToString() })
    }

    @Test
    fun aFileDeletedByAnotherDeviceReadsAsMissing() = runTest {
        val file = drive.addFile("secrt/a", byteArrayOf(1))
        target().use { gd ->
            assertEquals(listOf("a"), gd.list("secrt/"))
            drive.files.remove(file.id)
            assertNull(gd.get("secrt/a"))
            assertEquals(emptyList(), gd.list("secrt/"))
        }
    }

    @Test
    fun unsafePathsAreRefused() = runTest {
        target().use { gd ->
            assertFailsWith<IllegalArgumentException> { gd.get("../x") }
            assertFailsWith<IllegalArgumentException> { gd.put("secrt/a b", byteArrayOf()) }
        }
    }

    @Test
    fun theDesktopOAuthFlowGetsTokensAndTheAccount() = runTest {
        GoogleOAuthClient("client", "not-secret", drive.endpoints).use { oauth ->
            val url = oauth.authorizationUrl("http://127.0.0.1:5555", state = "st", codeChallenge = "ch")
            assertTrue(url.startsWith("${drive.origin}/o/oauth2/v2/auth?"))
            listOf("client_id=client", "redirect_uri=http%3A%2F%2F127.0.0.1%3A5555", "code_challenge=ch", "code_challenge_method=S256", "state=st", "drive.appdata")
                .forEach { assertTrue(it in url, "$it missing from $url") }

            val tokens = oauth.exchangeCode("good-code", "verifier", "http://127.0.0.1:5555")
            assertEquals("refresh-1", tokens.refreshToken)
            assertEquals("alice@gmail.com", oauth.email(tokens.accessToken))
            assertEquals("token-1", oauth.refresh("refresh-1").accessToken)
            assertTrue("no longer" in assertFailsWith<GoogleAuthException> { oauth.refresh("revoked") }.message!!)
        }
    }

    @Test
    fun theTokenIsNeverPrinted() {
        val config = BackupTargetConfig.GoogleDrive("alice@gmail.com", refreshToken = "1//SECRET")
        assertFalse("SECRET" in config.toString())
        assertEquals("Google Drive · alice@gmail.com", config.description)
    }

    @Test
    fun backupRoundTripOverGoogleDrive() = runTest {
        val phone = Device()
        phone.session.create("pw", withRecoveryKey = false)
        val photo = phone.store.addAttachment("PIXELS".encodeToByteArray(), "photo.png", "image/png")
        phone.store.saveEntry("e1", draft("Lisbon", "A SECRET day").copy(attachments = listOf(photo)))
        phone.backup.saveTarget(account)
        target().use { phone.backup.push(it) }

        assertTrue(drive.byName.keys.any { it.endsWith("/vault.key") })
        drive.byName.forEach { (name, bytes) ->
            assertFalse("SECRET" in bytes.decodeToString() || "PIXELS" in bytes.decodeToString(), "$name holds plaintext")
        }

        val laptop = Device()
        target().use { gd ->
            val vaultId = laptop.backup.findDiaries(gd).single()
            laptop.backup.restore(gd, vaultId, Credential.Password("pw"), account)
        }
        assertEquals(setOf("Lisbon"), laptop.store.titles())
        assertContentEquals("PIXELS".encodeToByteArray(), laptop.store.readAttachment(photo.blobId))

        laptop.store.saveEntry("e2", draft("Porto"))
        assertEquals(account, laptop.backup.savedTarget())
        target().use { laptop.backup.sync(it) }
        target().use { assertEquals(1, phone.backup.sync(it).downloaded) }
        assertEquals(setOf("Lisbon", "Porto"), phone.store.titles())
    }
}
