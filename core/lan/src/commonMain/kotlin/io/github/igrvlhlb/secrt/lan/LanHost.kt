package io.github.igrvlhlb.secrt.lan

import io.github.igrvlhlb.secrt.crypto.Crypto
import io.github.igrvlhlb.secrt.crypto.KeyPair
import io.github.igrvlhlb.secrt.storage.StoreClosedException
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.ServerSocket
import io.ktor.network.sockets.aSocket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.withContext

/**
 * The side that shows pairing codes: listens for the user's devices while [run] runs, which the UI
 * does only while a screen meant for it is open. Nothing is advertised; other devices find it
 * through the QR code, or at the address they remember from it.
 *
 * Connections are served one at a time. Pairing needs a code shown with [showInvite]: a fresh
 * one-time secret, valid for [INVITE_VALIDITY_MILLIS] and consumed by the first device that proves
 * it. If that device has no diary, it only gets this one once the user agrees ([question]).
 */
class LanHost internal constructor(private val lan: LanSync, val joining: Boolean) {

    sealed interface Status {
        data object Starting : Status

        data class Listening(val addresses: List<String>, val port: Int) : Status

        data class Failed(val message: String) : Status
    }

    /** A pairing code on screen. */
    data class Invite(val link: String, val expiresAt: Long)

    sealed interface Event {
        data class Paired(val peerName: String) : Event

        data class Synced(val peerName: String, val stats: SyncStats) : Event

        /** A phone sent its diary ([joining] only); see [LanSync.pendingJoin]. */
        data class DiaryReceived(val peerName: String) : Event

        /** A device without a diary scanned this one's code and received this diary. */
        data class DiarySent(val peerName: String) : Event

        /** The connected device asks its user something (e.g. whether to send its diary here) before going on. */
        data class WaitingForAnswer(val peerName: String) : Event

        data class Failed(val message: String) : Event
    }

    private val _status = MutableStateFlow<Status>(Status.Starting)
    val status: StateFlow<Status> = _status.asStateFlow()

    private val _invite = MutableStateFlow<Invite?>(null)
    val invite: StateFlow<Invite?> = _invite.asStateFlow()

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 16)
    val events: SharedFlow<Event> = _events.asSharedFlow()

    private val _question = MutableStateFlow<LanQuestion?>(null)

    /**
     * A question for the user while a connected device waits; answer it with [answer]. It goes away
     * once answered, after [LanSync.DECISION_TIMEOUT_MILLIS], or when the connection ends (e.g.
     * [run] is cancelled because the screen closed, or the diary locked).
     */
    val question: StateFlow<LanQuestion?> = _question.asStateFlow()

    fun answer(question: LanQuestion, accept: Boolean) {
        question.answer.complete(accept)
    }

    private class Secret(val bytes: ByteArray, val expiresAt: Long)

    /** Swapped atomically: the UI replaces it while a connection may be reading it. */
    private val secret = MutableStateFlow<Secret?>(null)

    /** A device without a diary has no database for its identity yet: it gets one now, saved after joining. */
    private var joinIdentitySeed: ByteArray? = null

    /**
     * Listens until cancelled (or, when [joining], until a diary arrives).
     * Never throws for network trouble: see [status] and [events].
     */
    suspend fun run() = withContext(lan.ioDispatcher) {
        val crypto = lan.crypto()
        if (joining) joinIdentitySeed = crypto.randomBytes(LanSync.IDENTITY_SEED_BYTES)
        val selector = SelectorManager(lan.ioDispatcher)
        var server: ServerSocket? = null
        try {
            server = bind(selector) ?: return@withContext
            val port = (server.localAddress as InetSocketAddress).port
            val addresses = lan.localAddresses()
            _status.value = if (addresses.isEmpty()) Status.Failed("This device isn't connected to a network.") else Status.Listening(addresses, port)
            while (true) {
                val socket = server.accept()
                val received = serve(SocketConnection(socket), crypto)
                if (received) break
            }
        } finally {
            server?.close()
            selector.close()
            _invite.value = null
            secret.getAndUpdate { null }?.bytes?.fill(0)
            // A received diary holds its own copy.
            joinIdentitySeed?.fill(0)
        }
    }

    /** Shows a new pairing code (replacing any previous one). Needs [status] to be [Status.Listening]. */
    suspend fun showInvite() {
        val listening = _status.value as? Status.Listening ?: return
        val crypto = lan.crypto()
        val identityKey = identity(crypto)?.let { pair -> pair.publicKey.also { crypto.wipe(pair.secretKey) } } ?: return
        val newSecret = crypto.randomBytes(PairingInvite.SECRET_BYTES)
        val expiresAt = lan.now() + INVITE_VALIDITY_MILLIS
        secret.getAndUpdate { Secret(newSecret, expiresAt) }?.bytes?.fill(0)
        _invite.value = Invite(PairingInvite(listening.addresses, listening.port, identityKey, newSecret.copyOf()).toLink(), expiresAt)
    }

    fun hideInvite() {
        _invite.value = null
        secret.getAndUpdate { null }?.bytes?.fill(0)
    }

    private suspend fun bind(selector: SelectorManager): ServerSocket? {
        for (port in listOf(lan.listenPort, 0).distinct()) {
            try {
                return aSocket(selector).tcp().bind("0.0.0.0", port) { reuseAddress = true }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Taken (another app, or a second window): any free port works, since codes carry it.
            }
        }
        _status.value = Status.Failed("Couldn't open a network port for syncing.")
        return null
    }

    /** @return whether a diary was received (joining), which ends listening. */
    private suspend fun serve(connection: Connection, crypto: Crypto): Boolean {
        var peerName = "A device"
        // Before authentication, failures are whoever knocked (a port scan, a stale phone): not worth telling the user.
        var authenticated = false
        try {
            val identity = identity(crypto) ?: return false
            val policy = Policy(identity)
            val (channel, mode) = try {
                Handshake.respond(connection, crypto, policy) ?: return false
            } finally {
                crypto.wipe(identity.secretKey)
            }
            authenticated = true
            val messages = MessageChannel(channel)
            val hello = messages.expect<Hello>()
            peerName = hello.deviceName.clean()
            messages.peerName = peerName
            val peerKey = requireNotNull(channel.peerIdentityKey).toKeyString()

            if (joining) {
                if (hello.vaultId == null) {
                    messages.send(Failure(Failure.Reason.NoDiary))
                    _events.tryEmit(Event.Failed("$peerName has no diary either: there's nothing to set this computer up from."))
                    return false
                }
                messages.send(Hello(lan.ownName(), deviceId = null, vaultId = null, lan.protocolVersion))
                // Its user is asked before it sends anything (versions from before that just send).
                if (hello.protocol >= LanSync.PROTOCOL_ASKS) _events.tryEmit(Event.WaitingForAnswer(peerName))
                val file = lan.receiveDiary(messages)
                messages.send(Received)
                lan.offerJoin(PendingJoin(file, requireNotNull(joinIdentitySeed).copyOf(), peerKey, peerName, hello.deviceId, peerAddresses = emptyList()))
                _events.tryEmit(Event.DiaryReceived(peerName))
                return true
            }

            val store = lan.unlockedStoreOrNull()
            val vaultId = lan.vaultId()
            if (store == null || vaultId == null) {
                messages.send(Failure(Failure.Reason.Locked))
                return false
            }
            if (hello.vaultId == null) {
                // A new device that scanned this one's code. Showing the code isn't consent enough:
                // someone could have photographed it and scanned it first. So the user says who gets
                // the diary, once the other device's name is known.
                if (mode != ConnectionMode.Pair) {
                    messages.send(Failure(Failure.Reason.Protocol))
                    return false
                }
                messages.send(Hello(lan.ownName(), store.deviceId, vaultId, lan.protocolVersion))
                val answer = lan.decide(LanQuestion.SendDiary(peerName, peerAsked = true), ::ask)
                if (answer != LanSync.Answer.Yes) {
                    lan.refuse(messages, answer, hello.protocol, Failure.Reason.SendDeclined, "Nothing was sent to $peerName.")
                }
                val sent = store.versionVector()
                lan.sendDiary(messages)
                // Its device id comes with the first sync: restoring gives it a new one.
                store.peers.pair(peerKey, peerName, deviceId = null, addresses = emptyList(), now = lan.now())
                // It now holds everything this one had.
                store.peers.recordSync(peerKey, peerName, deviceId = null, addresses = emptyList(), versionVector = sent, now = lan.now())
                _events.tryEmit(Event.DiarySent(peerName))
                return false
            }
            if (hello.vaultId != vaultId) {
                messages.send(Failure(Failure.Reason.DifferentDiary))
                _events.tryEmit(Event.Failed("$peerName holds a different diary, so it can't sync with this one."))
                return false
            }
            if (hello.protocol < LanSync.PROTOCOL_KEY_SLOTS) {
                // It wouldn't agree on key slots (see LanSync.requireKeySlots); it reads this as a refusal.
                messages.send(Failure(Failure.Reason.Protocol))
                _events.tryEmit(Event.Failed("$peerName runs an older version of secrt. Update secrt on it to sync with this device."))
                return false
            }
            messages.send(Hello(lan.ownName(), store.deviceId, vaultId, lan.protocolVersion))
            if (mode == ConnectionMode.Pair) {
                store.peers.pair(peerKey, peerName, hello.deviceId, emptyList(), lan.now())
                _events.tryEmit(Event.Paired(peerName))
            }
            val stats = SyncExchange.run(messages, store, initiator = false, KeySlotsTurn(lan, peerName, ::ask))
            store.peers.recordSync(peerKey, peerName, hello.deviceId, emptyList(), store.versionVector(), lan.now())
            _events.tryEmit(Event.Synced(peerName, stats))
        } catch (e: CancellationException) {
            throw e
        } catch (e: LanException) {
            if (authenticated) _events.tryEmit(Event.Failed(e.message ?: "Sync failed"))
        } catch (e: ConnectionClosedException) {
            if (authenticated) _events.tryEmit(Event.Failed("The connection to $peerName was lost."))
        } catch (e: StoreClosedException) {
            // Locked meanwhile; the screen is going away.
        } catch (e: Exception) {
            _events.tryEmit(Event.Failed(e.message ?: "Sync failed"))
        } finally {
            connection.close()
        }
        return false
    }

    /** Shows [question] on [LanHost.question] until it's answered, or the waiting is cancelled. */
    private suspend fun ask(question: LanQuestion): Boolean {
        _question.value = question
        try {
            return question.answer.await()
        } finally {
            _question.compareAndSet(question, null)
        }
    }

    /** This device's identity: the diary's, or the provisional one of a computer being set up. */
    private suspend fun identity(crypto: Crypto): KeyPair? {
        if (joining) return joinIdentitySeed?.let(crypto::signingKeyPairFromSeed)
        val store = lan.unlockedStoreOrNull() ?: return null
        return lan.identityOf(store, crypto)
    }

    private inner class Policy(override val identity: KeyPair) : ResponderPolicy {
        override fun accepts(mode: ConnectionMode): Boolean = when (mode) {
            ConnectionMode.Pair -> _invite.value != null
            ConnectionMode.Sync -> !joining && lan.unlockedStoreOrNull() != null
        }

        override fun pairingSecret(): ByteArray? =
            secret.value?.takeIf { lan.now() < it.expiresAt }?.bytes?.copyOf()

        override fun consumePairingSecret(secret: ByteArray) {
            // Only if it's still the current one: the user may have shown a new code meanwhile.
            val current = this@LanHost.secret.value ?: return
            if (current.bytes.contentEquals(secret) && this@LanHost.secret.compareAndSet(current, null)) {
                current.bytes.fill(0)
                _invite.value = null
            }
        }

        override suspend fun isKnownPeer(identityKey: ByteArray): Boolean =
            lan.unlockedStoreOrNull()?.peers?.get(identityKey.toKeyString()) != null
    }

    companion object {
        const val INVITE_VALIDITY_MILLIS = 5 * 60 * 1000L
    }
}
