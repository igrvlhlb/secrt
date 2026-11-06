package io.github.igrvlhlb.secrt.lan

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.github.igrvlhlb.secrt.crypto.Argon2Params
import io.github.igrvlhlb.secrt.crypto.SodiumCrypto
import io.github.igrvlhlb.secrt.session.AppSession
import io.github.igrvlhlb.secrt.session.SessionState
import io.github.igrvlhlb.secrt.storage.DiaryStore
import io.github.igrvlhlb.secrt.storage.DriverFactory
import io.github.igrvlhlb.secrt.storage.EntryDraft
import io.github.igrvlhlb.secrt.vault.Credential
import io.github.igrvlhlb.secrt.vault.InvalidCredentialsException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.LocalDate
import okio.Buffer
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pairing and sync between two full app sessions (a "phone" and a "computer") over real localhost
 * sockets: the same code paths as between devices, minus the Wi-Fi.
 */
class LanSyncTest {

    private class Device(val name: String) {
        val fileSystem = FakeFileSystem()
        val dbFile: File = File.createTempFile("secrt-lan", ".db").apply { delete(); deleteOnExit() }
        var clock = 1_800_000_000_000L

        // Test-only: an unencrypted JDBC database. Encryption at rest is covered elsewhere.
        private val driverFactory = object : DriverFactory {
            override fun create(schema: SqlSchema<QueryResult.Value<Unit>>, key: ByteArray): SqlDriver =
                JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}", schema = schema)

            override fun delete() {
                dbFile.delete()
            }
        }

        val session = AppSession(
            fileSystem = fileSystem,
            vaultDirectory = "/vault".toPath(),
            driverFactory = driverFactory,
            cryptoProvider = { SodiumCrypto.create() },
            ioDispatcher = Dispatchers.IO,
            argon2Params = Argon2Params.Insecure,
        )

        val lan = LanSync(
            session = session,
            cryptoProvider = { SodiumCrypto.create() },
            fileSystem = fileSystem,
            tempDirectory = "/tmp".toPath(),
            deviceName = { name },
            ioDispatcher = Dispatchers.IO,
            localAddresses = { listOf("127.0.0.1") },
            // Fixed per device, like the real default port: a phone finds the computer again at the port it paired with.
            listenPort = ServerSocket(0).use { it.localPort },
            connectTimeoutMillis = 2_000,
            now = { clock },
        )

        val store: DiaryStore get() = assertIs<SessionState.Unlocked>(session.state.value).store
    }

    private val scope = CoroutineScope(Dispatchers.IO)
    private val phone = Device("Phone")
    private val computer = Device("Computer")

    @AfterTest
    fun tearDown() = runBlocking {
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancelAndJoin()
        phone.session.lock()
        computer.session.lock()
    }

    private fun draft(title: String, body: String = "body", attachments: List<io.github.igrvlhlb.secrt.model.AttachmentRef> = emptyList()) =
        EntryDraft(title, body, LocalDate(2026, 11, 6), null, emptySet(), attachments)

    private suspend fun DiaryStore.titles() = observeEntries().first().map { it.title }.toSet()

    /** Starts listening on the computer and returns it once it's ready. */
    private suspend fun Device.listen(joining: Boolean = false): LanHost {
        val host = lan.host(joining)
        scope.launch { host.run() }
        host.status.first { it is LanHost.Status.Listening }
        return host
    }

    private suspend fun LanHost.newCode(): String {
        showInvite()
        return assertNotNull(invite.value).link
    }

    /** Phone with a diary, computer holding a copy of the same diary (restored from a backup). */
    private suspend fun sameDiaryOnBoth() {
        phone.session.create("pw", withRecoveryKey = false)
        phone.store.saveEntry("e1", draft("From the phone"))
        val backup = Buffer().also { phone.session.exportTo(it) }
        computer.session.restore(backup, Credential.Password("pw"))
    }

    private fun test(block: suspend () -> Unit) = runBlocking { withTimeout(60_000) { block() } }

    @Test
    fun pairsAndSyncsBothWaysIncludingAttachments() = test {
        sameDiaryOnBoth()
        val host = computer.listen()
        val pixels = "phone photo".encodeToByteArray()
        val photo = phone.store.addAttachment(pixels, "photo.png", "image/png")
        phone.store.saveEntry("e2", draft("With a photo", attachments = listOf(photo)))
        val scan = computer.store.addAttachment("scan".encodeToByteArray(), "scan.pdf", "application/pdf")
        computer.store.saveEntry("e3", draft("Written on the computer", attachments = listOf(scan)))

        val result = assertIs<PairingResult.Paired>(phone.lan.pair(host.newCode()))
        assertEquals("Computer", result.peerName)
        assertEquals(1, result.stats.received)

        assertEquals(setOf("From the phone", "With a photo", "Written on the computer"), phone.store.titles())
        assertEquals(phone.store.titles(), computer.store.titles())
        assertContentEquals(pixels, computer.store.readAttachment(photo.blobId))
        assertContentEquals("scan".encodeToByteArray(), phone.store.readAttachment(scan.blobId))
        assertEquals(phone.store.versionVector(), computer.store.versionVector())

        // Both keep each other, and the code is gone once used.
        val computerOnPhone = phone.store.peers.all().single()
        assertEquals("Computer", computerOnPhone.name)
        assertTrue(computerOnPhone.addresses.single().startsWith("127.0.0.1:"))
        assertEquals("Phone", computer.store.peers.all().single().name)
        assertNull(host.invite.value)

        // Later edits on both sides, then "Sync now" from the phone.
        phone.store.saveEntry("e1", draft("From the phone", body = "edited on the phone"))
        computer.store.saveEntry("e4", draft("Another one"))
        val stats = phone.lan.sync(phone.store.peers.all().single())
        assertEquals(1, stats.received)
        assertEquals(1, stats.sent)
        assertEquals("edited on the phone", computer.store.getEntry("e1")?.body)
        assertEquals(phone.store.titles(), computer.store.titles())
        assertNotNull(phone.store.peers.all().single().lastSyncAt)
        assertNotNull(computer.store.peers.all().single().lastSyncAt)

        // Nothing new: nothing moves.
        assertEquals(SyncStats(0, 0), phone.lan.sync(phone.store.peers.all().single()))
    }

    @Test
    fun setsUpAFreshComputerFromThePhone() = test {
        phone.session.create("phone-password", withRecoveryKey = false)
        val photo = phone.store.addAttachment("pixels".encodeToByteArray(), "photo.png", "image/png")
        phone.store.saveEntry("e1", draft("Day one", attachments = listOf(photo)))
        phone.store.saveEntry("e2", draft("Day two"))
        assertEquals(SessionState.NoVault, computer.session.state.value)

        val host = computer.listen(joining = true)
        val received = scope.launch { host.events.first { it is LanHost.Event.DiaryReceived } }
        val result = phone.lan.pair(host.newCode())
        assertIs<PairingResult.DiarySent>(result)
        received.join()
        val join = assertNotNull(computer.lan.pendingJoin.value)
        assertEquals("Phone", join.peerName)
        // Received data waits for the password; the staged copy is encrypted like a backup file.
        assertEquals(SessionState.NoVault, computer.session.state.value)
        assertFailsWith<InvalidCredentialsException> { computer.lan.completeJoin(Credential.Password("wrong")) }
        assertNotNull(computer.lan.pendingJoin.value)

        computer.lan.completeJoin(Credential.Password("phone-password"))
        assertNull(computer.lan.pendingJoin.value)
        assertEquals(setOf("Day one", "Day two"), computer.store.titles())
        assertContentEquals("pixels".encodeToByteArray(), computer.store.readAttachment(photo.blobId))
        assertEquals("Phone", computer.store.peers.all().single().name)
        assertEquals("Computer", phone.store.peers.all().single().name)
        assertTrue(computer.store.deviceId != phone.store.deviceId)

        // The computer kept the identity the phone paired with: later syncs authenticate.
        computer.listen()
        computer.store.saveEntry("e3", draft("Day three, on the computer"))
        val stats = phone.lan.sync(phone.store.peers.all().single())
        assertEquals(1, stats.received)
        assertEquals(setOf("Day one", "Day two", "Day three, on the computer"), phone.store.titles())
    }

    @Test
    fun refusesToPairDifferentDiaries() = test {
        phone.session.create("pw", withRecoveryKey = false)
        phone.store.saveEntry("e1", draft("Phone diary"))
        computer.session.create("pw", withRecoveryKey = false)
        computer.store.saveEntry("e2", draft("Computer diary"))
        val host = computer.listen()

        assertFailsWith<LanException.DifferentDiary> { phone.lan.pair(host.newCode()) }
        assertEquals(setOf("Phone diary"), phone.store.titles())
        assertEquals(setOf("Computer diary"), computer.store.titles())
        assertEquals(emptyList(), phone.store.peers.all())
        assertEquals(emptyList(), computer.store.peers.all())
    }

    @Test
    fun rejectsAWrongSecretAndAForgedIdentity() = test {
        sameDiaryOnBoth()
        val host = computer.listen()
        val invite = PairingInvite.parse(host.newCode())

        val wrongSecret = PairingInvite(invite.addresses, invite.port, invite.identityKey, ByteArray(32) { 7 })
        assertFailsWith<LanException.WrongCode> { phone.lan.pair(wrongSecret.toLink()) }

        // Someone else's QR code pointing at this computer: it can't prove that identity.
        val otherIdentity = SodiumCrypto.create().signingKeyPair().publicKey
        val forged = PairingInvite(invite.addresses, invite.port, otherIdentity, invite.secret)
        assertFailsWith<LanException.Protocol> { phone.lan.pair(forged.toLink()) }

        assertEquals(emptyList(), phone.store.peers.all())
        assertEquals(emptyList(), computer.store.peers.all())
        // Failed attempts don't burn the code: the real one still works.
        assertIs<PairingResult.Paired>(phone.lan.pair(invite.toLink()))
    }

    @Test
    fun codesExpireAndWorkOnlyOnce() = test {
        sameDiaryOnBoth()
        val host = computer.listen()
        val code = host.newCode()
        computer.clock += LanHost.INVITE_VALIDITY_MILLIS + 1
        assertFailsWith<LanException.WrongCode> { phone.lan.pair(code) }

        val fresh = host.newCode()
        assertIs<PairingResult.Paired>(phone.lan.pair(fresh))
        // Used: the computer isn't pairing anymore.
        assertFailsWith<LanException.Refused> { phone.lan.pair(fresh) }
    }

    @Test
    fun onlyPairedDevicesCanSync() = test {
        sameDiaryOnBoth()
        val host = computer.listen()
        phone.lan.pair(host.newCode())
        val peer = phone.store.peers.all().single()

        computer.store.peers.forget(computer.store.peers.all().single().identityKey)
        computer.store.saveEntry("secret", draft("Not for unpaired devices"))
        val error = assertFailsWith<LanException.UnknownDevice> { phone.lan.sync(peer) }
        assertTrue("Computer" in error.message!!)
        assertTrue("Not for unpaired devices" !in phone.store.titles())

        // Nor can a device that never paired, even holding the same diary.
        val stranger = Device("Stranger")
        stranger.session.restore(Buffer().also { phone.session.exportTo(it) }, Credential.Password("pw"))
        assertFailsWith<LanException.UnknownDevice> { stranger.lan.sync(peer) }
        stranger.session.lock()
    }

    @Test
    fun rejectsTamperedFrames() = test {
        sameDiaryOnBoth()
        val host = computer.listen()
        val invite = PairingInvite.parse(host.newCode())

        // Flips one byte of the computer's first encrypted frame (after its 32-byte ephemeral key
        // and the 4-byte length): the phone must notice before trusting anything.
        TamperingProxy(invite.port, toClientOffset = 32 + 4 + 10).use { proxy ->
            val error = assertFailsWith<LanException.Protocol> {
                phone.lan.pair(PairingInvite(invite.addresses, proxy.port, invite.identityKey, invite.secret).toLink())
            }
            assertTrue("tampered" in error.message!!)
        }

        // Flips a byte in the phone's first frame after the handshake (42-byte hello, then the
        // 4 + 128 + 16-byte authentication frame): the computer drops the connection, applies nothing.
        val failure = scope.launch { host.events.first { it is LanHost.Event.Failed } }
        TamperingProxy(invite.port, toServerOffset = 42 + 148 + 4 + 3).use { proxy ->
            assertFailsWith<LanException.ConnectionLost> {
                phone.lan.pair(PairingInvite(invite.addresses, proxy.port, invite.identityKey, invite.secret).toLink())
            }
        }
        failure.join()
        assertEquals(emptyList(), computer.store.peers.all())
        assertEquals(emptyList(), phone.store.peers.all())
    }

    /** Forwards one connection to localhost:[targetPort], flipping the byte at the given offset of one direction. */
    private class TamperingProxy(targetPort: Int, toServerOffset: Long = -1, toClientOffset: Long = -1) : AutoCloseable {
        private val server = ServerSocket(0)
        val port: Int = server.localPort

        init {
            thread(isDaemon = true) {
                runCatching {
                    server.accept().use { client ->
                        Socket("127.0.0.1", targetPort).use { target ->
                            val up = thread(isDaemon = true) { pump(client, target, toServerOffset) }
                            pump(target, client, toClientOffset)
                            up.join()
                        }
                    }
                }
            }
        }

        private fun pump(from: Socket, to: Socket, flipAt: Long) = runCatching {
            val input = from.getInputStream()
            val output = to.getOutputStream()
            var position = 0L
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (flipAt in position until position + read) {
                    val i = (flipAt - position).toInt()
                    buffer[i] = (buffer[i].toInt() xor 0x01).toByte()
                }
                position += read
                output.write(buffer, 0, read)
                output.flush()
            }
            runCatching { to.shutdownOutput() }
        }

        override fun close() = server.close()
    }
}
