package io.github.igrvlhlb.secrt.lan

import io.github.igrvlhlb.secrt.crypto.Crypto
import io.github.igrvlhlb.secrt.crypto.KeyPair
import io.github.igrvlhlb.secrt.session.AppSession
import io.github.igrvlhlb.secrt.session.SessionState
import io.github.igrvlhlb.secrt.storage.DiaryStore
import io.github.igrvlhlb.secrt.storage.Peer
import io.github.igrvlhlb.secrt.storage.StoreClosedException
import io.github.igrvlhlb.secrt.vault.Credential
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.aSocket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okio.FileSystem
import okio.Path
import okio.buffer
import okio.use
import kotlin.io.encoding.Base64
import kotlin.random.Random
import kotlin.time.Clock

/** Progress of a pairing or sync started on this device, for the UI. */
enum class LanProgress { Connecting, SendingDiary, Syncing }

sealed interface PairingResult {
    val peerName: String

    /** Paired with a computer holding this same diary, and synced right away. */
    data class Paired(override val peerName: String, val stats: SyncStats) : PairingResult

    /** The computer had no diary: it received this one, and opens it with this diary's password. */
    data class DiarySent(override val peerName: String) : PairingResult
}

/** A diary received from a phone, waiting for its password on this (first-run) computer. */
class PendingJoin internal constructor(
    internal val file: Path,
    internal val identitySeed: ByteArray,
    internal val peerKey: String,
    val peerName: String,
    internal val peerDeviceId: String?,
)

/**
 * Pairing and sync with the user's other devices on the local network (see docs/architecture.md,
 * "LAN pairing & sync"). The phone connects ([pair], [sync]); the computer listens while the user
 * keeps its Devices screen (or its "set up from your phone" screen) open ([host]).
 *
 * Every connection is authenticated and encrypted by [Handshake]; nothing received is applied
 * before that. Both diaries must be unlocked, except on a computer being set up, which has none yet.
 *
 * @param tempDirectory for the encrypted diary archive while setting up a computer; must not be
 *   inside the vault directory (restoring wipes that).
 * @param deviceName how this device is shown on the other one.
 * @param localAddresses this device's addresses for pairing codes; [localNetworkAddresses] by default.
 * @param listenPort fixed so a paired phone finds the computer again at the address it remembers;
 *   another one is used if it's taken (pairing codes carry the actual port).
 */
class LanSync(
    private val session: AppSession,
    private val cryptoProvider: suspend () -> Crypto,
    private val fileSystem: FileSystem,
    private val tempDirectory: Path,
    private val deviceName: () -> String,
    internal val ioDispatcher: CoroutineDispatcher,
    internal val localAddresses: () -> List<String> = ::localNetworkAddresses,
    internal val listenPort: Int = DEFAULT_PORT,
    private val connectTimeoutMillis: Long = 4_000,
    internal val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val clientLock = Mutex()

    /**
     * A pairing code handed in from outside the in-app scanner; the Devices screen picks it up.
     * Only debug builds set it (an intent extra, for testing without a camera).
     */
    val pendingPairingLink = MutableStateFlow<String?>(null)

    private val _pendingJoin = MutableStateFlow<PendingJoin?>(null)

    /** Set once a phone has sent its diary to this computer; [completeJoin] opens it. */
    val pendingJoin: StateFlow<PendingJoin?> = _pendingJoin.asStateFlow()

    // ------------------------------------------------------------------ connecting side (phone)

    /**
     * Pairs with the computer showing the QR code [link], then syncs with it, or sets it up with
     * this diary if it has none.
     *
     * @throws LanException
     */
    suspend fun pair(link: String, progress: (LanProgress) -> Unit = {}): PairingResult = client {
        val invite = PairingInvite.parse(link)
        val store = unlockedStore()
        val vaultId = session.vaultId ?: throw LanException.Locked()
        progress(LanProgress.Connecting)
        val computer = "the computer"
        withConnection(invite.addresses.map { "$it:${invite.port}" }, computer) { connection, address ->
            val crypto = cryptoProvider()
            val identity = identityOf(store, crypto)
            val messages = try {
                MessageChannel(Handshake.initiate(connection, crypto, identity, ConnectionMode.Pair, invite.identityKey, invite.secret))
            } finally {
                crypto.wipe(identity.secretKey)
            }
            messages.send(Hello(deviceName().clean(), store.deviceId, vaultId))
            val hello = messages.expect<Hello>()
            val name = hello.deviceName.clean()
            val peerKey = invite.identityKey.toKeyString()
            val addresses = (listOf(address) + invite.addresses.map { "$it:${invite.port}" }).distinct()
            when (hello.vaultId) {
                null -> {
                    progress(LanProgress.SendingDiary)
                    sendDiary(messages)
                    store.peers.pair(peerKey, name, deviceId = null, addresses = addresses, now = now())
                    PairingResult.DiarySent(name)
                }
                vaultId -> {
                    store.peers.pair(peerKey, name, hello.deviceId, addresses, now())
                    progress(LanProgress.Syncing)
                    val stats = SyncExchange.run(messages, store, initiator = true)
                    store.peers.recordSync(peerKey, name, hello.deviceId, addresses, store.versionVector(), now())
                    PairingResult.Paired(name, stats)
                }
                else -> throw LanException.DifferentDiary()
            }
        }
    }

    /**
     * Syncs with a paired computer at the address it was last reached at (see [Peer.addresses]).
     *
     * @throws LanException
     */
    suspend fun sync(peer: Peer, progress: (LanProgress) -> Unit = {}): SyncStats = client {
        val store = unlockedStore()
        val vaultId = session.vaultId ?: throw LanException.Locked()
        if (peer.addresses.isEmpty()) throw LanException.Unreachable(peer.name)
        progress(LanProgress.Connecting)
        withConnection(peer.addresses, peer.name) { connection, address ->
            val crypto = cryptoProvider()
            val identity = identityOf(store, crypto)
            val messages = try {
                MessageChannel(Handshake.initiate(connection, crypto, identity, ConnectionMode.Sync, peer.identityKey.toKeyBytes(), null))
            } finally {
                crypto.wipe(identity.secretKey)
            }
            messages.send(Hello(deviceName().clean(), store.deviceId, vaultId))
            val hello = try {
                messages.expect<Hello>()
            } catch (e: LanException.UnknownDevice) {
                throw LanException.UnknownDevice(peer.name)
            }
            if (hello.vaultId != vaultId) throw LanException.DifferentDiary()
            progress(LanProgress.Syncing)
            val stats = SyncExchange.run(messages, store, initiator = true)
            val addresses = (listOf(address) + peer.addresses).distinct()
            store.peers.recordSync(peer.identityKey, hello.deviceName.clean(), hello.deviceId, addresses, store.versionVector(), now())
            stats
        }
    }

    /** Unpairs [peer] on this device. It can't sync with this one until paired again. */
    suspend fun forget(peer: Peer) = unlockedStore().peers.forget(peer.identityKey)

    // ------------------------------------------------------------------ listening side (computer)

    /**
     * Listens for this user's devices while the returned host [runs][LanHost.run].
     *
     * @param joining for a computer without a diary: only accepts a phone that pairs and sends its
     *   diary (see [pendingJoin]).
     */
    fun host(joining: Boolean = false): LanHost = LanHost(this, joining)

    /**
     * Opens the diary a phone sent ([pendingJoin]) with its password or recovery key, and keeps
     * the phone as a paired device.
     *
     * @throws io.github.igrvlhlb.secrt.vault.InvalidCredentialsException (the diary stays pending, to retry)
     */
    suspend fun completeJoin(credential: Credential) {
        val join = requireNotNull(_pendingJoin.value) { "No diary was received" }
        session.restore(fileSystem.source(join.file), credential)
        val store = unlockedStore()
        store.peers.setIdentitySeed(join.identitySeed)
        store.peers.pair(join.peerKey, join.peerName, join.peerDeviceId, emptyList(), now())
        discardJoin()
    }

    /** Drops a received diary without opening it. */
    fun discardJoin() {
        val join = _pendingJoin.value ?: return
        _pendingJoin.value = null
        join.identitySeed.fill(0)
        runCatching { fileSystem.delete(join.file, mustExist = false) }
    }

    // ------------------------------------------------------------------ internals

    internal suspend fun crypto(): Crypto = cryptoProvider()

    internal fun ownName(): String = deviceName().clean()

    internal fun unlockedStoreOrNull(): DiaryStore? = (session.state.value as? SessionState.Unlocked)?.store

    internal fun vaultId(): String? = session.vaultId

    private fun unlockedStore(): DiaryStore = unlockedStoreOrNull() ?: throw LanException.Locked()

    /** This replica's identity key pair, created on first use. Wipe the secret key after use. */
    internal suspend fun identityOf(store: DiaryStore, crypto: Crypto): KeyPair {
        val seed = store.peers.identitySeed { crypto.randomBytes(IDENTITY_SEED_BYTES) }
        return try {
            crypto.signingKeyPairFromSeed(seed)
        } finally {
            crypto.wipe(seed)
        }
    }

    internal fun receivedDiaryFile(): Path = tempDirectory / "received-diary.secrt"

    internal fun offerJoin(join: PendingJoin) {
        discardJoin()
        _pendingJoin.value = join
    }

    /** Receives the diary archive a phone sends to set this computer up. */
    internal suspend fun receiveDiary(messages: MessageChannel): Path {
        val start = messages.expect<ArchiveStart>()
        if (start.size !in 1..MAX_ARCHIVE_BYTES) throw LanException.Protocol("archive size")
        fileSystem.createDirectories(tempDirectory)
        val file = receivedDiaryFile()
        var received = 0L
        try {
            fileSystem.sink(file).buffer().use { out ->
                while (true) {
                    when (val incoming = messages.receive()) {
                        is Incoming.Chunk -> {
                            received += incoming.bytes.size
                            if (received > start.size) throw LanException.Protocol("archive size")
                            out.write(incoming.bytes)
                        }
                        is Incoming.Message -> when (val message = incoming.message) {
                            ArchiveEnd -> break
                            is Failure -> throw message.toException()
                            else -> throw LanException.Protocol("unexpected message")
                        }
                        is Incoming.Attachment -> throw LanException.Protocol("unexpected data")
                    }
                }
            }
            if (received != start.size) throw LanException.Protocol("archive size")
        } catch (e: Throwable) {
            fileSystem.delete(file, mustExist = false)
            throw e
        }
        return file
    }

    /**
     * Sends this whole diary as a `.secrt` archive: the same encrypted format as a backup file, so
     * the computer restores it with the existing, tested code path. It is staged in [tempDirectory]
     * (it's encrypted) rather than in memory, which could be too small for the attachments.
     */
    private suspend fun sendDiary(messages: MessageChannel) {
        fileSystem.createDirectories(tempDirectory)
        val file = tempDirectory / "outgoing-diary-${Random.nextLong().toULong()}.secrt"
        try {
            session.exportTo(fileSystem.sink(file))
            val size = requireNotNull(fileSystem.metadata(file).size)
            messages.send(ArchiveStart(size))
            fileSystem.source(file).buffer().use { source ->
                val buffer = ByteArray(CHUNK_BYTES)
                while (true) {
                    val read = source.read(buffer)
                    if (read == -1) break
                    messages.sendChunk(buffer.copyOf(read))
                }
            }
            messages.send(ArchiveEnd)
            messages.expect<Received>()
        } finally {
            fileSystem.delete(file, mustExist = false)
        }
    }

    private suspend fun <T> client(block: suspend () -> T): T = clientLock.withLock {
        withContext(ioDispatcher) {
            try {
                block()
            } catch (e: StoreClosedException) {
                throw LanException.Locked()
            }
        }
    }

    /** Connects to the first of [addresses] (`host:port`) that answers, runs [block], and closes. */
    private suspend fun <T> withConnection(addresses: List<String>, name: String, block: suspend (Connection, String) -> T): T =
        SelectorManager(ioDispatcher).use { selector ->
            var failure: Throwable? = null
            for (address in addresses) {
                val host = address.substringBeforeLast(':')
                val port = address.substringAfterLast(':').toIntOrNull() ?: continue
                val socket = try {
                    withTimeout(connectTimeoutMillis) { aSocket(selector).tcp().connect(host, port) }
                } catch (e: TimeoutCancellationException) {
                    failure = e
                    continue
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failure = e
                    continue
                }
                val connection = SocketConnection(socket)
                return@use try {
                    block(connection, address)
                } catch (e: ConnectionClosedException) {
                    throw LanException.ConnectionLost(name, e)
                } finally {
                    connection.close()
                }
            }
            throw LanException.Unreachable(name, failure)
        }

    companion object {
        const val DEFAULT_PORT = 47820
        internal const val IDENTITY_SEED_BYTES = 32
        private const val CHUNK_BYTES = 1024 * 1024
        private const val MAX_ARCHIVE_BYTES = 64L * 1024 * 1024 * 1024
    }
}

private val keyEncoding = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)

/** Identity keys as stored in the peer table. */
internal fun ByteArray.toKeyString(): String = keyEncoding.encode(this)

internal fun String.toKeyBytes(): ByteArray = keyEncoding.decode(this)

/** A device name from the other side: shown in lists, so short and printable. */
internal fun String.clean(): String =
    filter { !it.isISOControl() }.trim().take(64).ifEmpty { "Unnamed device" }
