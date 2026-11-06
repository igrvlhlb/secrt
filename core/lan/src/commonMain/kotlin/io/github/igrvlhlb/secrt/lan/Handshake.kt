package io.github.igrvlhlb.secrt.lan

import io.github.igrvlhlb.secrt.crypto.Crypto
import io.github.igrvlhlb.secrt.crypto.KeyPair

/** What a connection is for, announced in the first message. */
internal enum class ConnectionMode(val code: Byte) {
    /** First contact: both sides prove knowledge of the pairing secret from the QR code. */
    Pair(1),

    /** Later syncs: only identities already paired are accepted. */
    Sync(2),
}

/** How the listening side treats an incoming handshake. */
internal interface ResponderPolicy {
    /** This side's identity, used to sign. */
    val identity: KeyPair

    /** Whether to go on with [mode] at all; refusing closes the connection right away. */
    fun accepts(mode: ConnectionMode): Boolean

    /** The current pairing secret, or `null` if none is active (the handshake then fails). */
    fun pairingSecret(): ByteArray?

    /** Called once a peer proved the pairing secret, so the secret can't be used again. */
    fun consumePairingSecret(secret: ByteArray)

    /** Whether [identityKey] belongs to a paired device (sync mode). */
    suspend fun isKnownPeer(identityKey: ByteArray): Boolean
}

/**
 * The authenticated key exchange that opens every LAN connection: SIGMA-I style, built from
 * libsodium primitives. Ephemeral X25519 keys give forward secrecy; Ed25519 identity signatures over
 * the transcript authenticate both sides; when pairing, a MAC keyed by the QR code's one-time
 * secret proves that both sides saw the code. See docs/architecture.md for the full description.
 *
 * ```
 * I -> R  "SECRTLAN" | version | mode | eI                       (plain)
 * R -> I  eR | frame{ idR | sig_R(h1, idR) [| mac_S(h1, idR)] }  (h1 = H(first message | eR))
 * I -> R  frame{ idI | sig_I(h2, idI) [| mac_S(h2, idI)] }       (h2 = H(h1 | idR | sig_R))
 * ```
 *
 * Frame keys come from `crypto_kx` over the ephemeral keys, then are bound to h1 (and so to the
 * mode and both ephemerals) with a keyed hash. Identities travel encrypted, so a passive observer
 * doesn't learn which devices talk.
 */
internal object Handshake {
    private val MAGIC = "SECRTLAN".encodeToByteArray()
    private const val VERSION: Byte = 1
    private const val HELLO_BYTES = 8 + 1 + 1 + Crypto.PUBLIC_KEY_BYTES
    private const val MAC_BYTES = 32
    private const val TIMEOUT_MILLIS = 15_000L

    private val LABEL_I2R = "secrt-lan/1 key i2r".encodeToByteArray()
    private val LABEL_R2I = "secrt-lan/1 key r2i".encodeToByteArray()
    private val SIG_RESPONDER = "secrt-lan/1 sig responder".encodeToByteArray()
    private val SIG_INITIATOR = "secrt-lan/1 sig initiator".encodeToByteArray()
    private val MAC_RESPONDER = "secrt-lan/1 pair responder".encodeToByteArray()
    private val MAC_INITIATOR = "secrt-lan/1 pair initiator".encodeToByteArray()

    /**
     * The connecting side (the phone).
     *
     * @param expectedPeer the identity key the other side must prove: from the QR code, or the
     *   stored peer.
     * @param pairingSecret required in [ConnectionMode.Pair].
     * @throws LanException.Refused if the other side hung up before authenticating.
     * @throws LanException.WrongCode if it doesn't know the pairing secret.
     * @throws LanException.Protocol if it isn't the expected device or the data is malformed.
     */
    suspend fun initiate(
        connection: Connection,
        crypto: Crypto,
        identity: KeyPair,
        mode: ConnectionMode,
        expectedPeer: ByteArray,
        pairingSecret: ByteArray?,
    ): SecureChannel = withReadTimeout(TIMEOUT_MILLIS) {
        require((mode == ConnectionMode.Pair) == (pairingSecret != null)) { "A pairing secret goes with pair mode only" }
        val ephemeral = crypto.keyExchangeKeyPair()
        try {
            val hello = MAGIC + byteArrayOf(VERSION, mode.code) + ephemeral.publicKey
            refusedIfClosed { connection.write(hello) }
            val responderEphemeral = refusedIfClosed { connection.read(Crypto.PUBLIC_KEY_BYTES) }
            val sessionKeys = try {
                crypto.clientSessionKeys(ephemeral, responderEphemeral)
            } catch (e: IllegalArgumentException) {
                throw LanException.Protocol("bad key", e)
            }
            val h1 = crypto.hash(hello + responderEphemeral)
            val channel = SecureChannel(
                connection,
                crypto,
                sendKey = crypto.keyedHash(sessionKeys.send, LABEL_I2R + h1),
                receiveKey = crypto.keyedHash(sessionKeys.receive, LABEL_R2I + h1),
            )
            crypto.wipe(sessionKeys.send)
            crypto.wipe(sessionKeys.receive)

            val auth = refusedIfClosed { channel.receive() }
            val (responderKey, responderSignature, responderMac) = parseAuth(auth, mode)
            if (!responderKey.contentEquals(expectedPeer)) throw LanException.Protocol("unexpected device")
            if (!crypto.verifySignature(responderKey, SIG_RESPONDER + h1 + responderKey, responderSignature)) {
                throw LanException.Protocol("bad signature")
            }
            if (pairingSecret != null) {
                val expectedMac = crypto.keyedHash(pairingSecret, MAC_RESPONDER + h1 + responderKey)
                if (!constantTimeEquals(expectedMac, responderMac!!)) throw LanException.WrongCode()
            }

            val h2 = crypto.hash(h1 + responderKey + responderSignature)
            var reply = identity.publicKey + crypto.sign(identity.secretKey, SIG_INITIATOR + h2 + identity.publicKey)
            if (pairingSecret != null) reply += crypto.keyedHash(pairingSecret, MAC_INITIATOR + h2 + identity.publicKey)
            channel.send(reply)
            channel.peerIdentityKey = responderKey
            channel
        } finally {
            crypto.wipe(ephemeral.secretKey)
        }
    }

    /**
     * The listening side (the desktop).
     *
     * @return the channel and the mode, or `null` if the connection was refused or failed
     *   authentication (it has been closed then; the initiator learns why from an error frame
     *   where one could be sent).
     */
    suspend fun respond(connection: Connection, crypto: Crypto, policy: ResponderPolicy): Pair<SecureChannel, ConnectionMode>? =
        withReadTimeout(TIMEOUT_MILLIS) {
            val hello = connection.read(HELLO_BYTES)
            val mode = ConnectionMode.entries.firstOrNull { it.code == hello[9] }
            if (!hello.copyOfRange(0, 8).contentEquals(MAGIC) || hello[8] != VERSION || mode == null || !policy.accepts(mode)) {
                return@withReadTimeout null
            }
            val pairingSecret = if (mode == ConnectionMode.Pair) policy.pairingSecret() else null
            val initiatorEphemeral = hello.copyOfRange(10, HELLO_BYTES)
            val ephemeral = crypto.keyExchangeKeyPair()
            try {
                val sessionKeys = try {
                    crypto.serverSessionKeys(ephemeral, initiatorEphemeral)
                } catch (e: IllegalArgumentException) {
                    return@withReadTimeout null
                }
                connection.write(ephemeral.publicKey)
                val h1 = crypto.hash(hello + ephemeral.publicKey)
                val channel = SecureChannel(
                    connection,
                    crypto,
                    sendKey = crypto.keyedHash(sessionKeys.send, LABEL_R2I + h1),
                    receiveKey = crypto.keyedHash(sessionKeys.receive, LABEL_I2R + h1),
                )
                crypto.wipe(sessionKeys.send)
                crypto.wipe(sessionKeys.receive)

                val ownKey = policy.identity.publicKey
                val signature = crypto.sign(policy.identity.secretKey, SIG_RESPONDER + h1 + ownKey)
                var auth = ownKey + signature
                if (mode == ConnectionMode.Pair) {
                    // Without an active secret a random MAC goes out: the initiator then reports a wrong code.
                    auth += pairingSecret?.let { crypto.keyedHash(it, MAC_RESPONDER + h1 + ownKey) } ?: crypto.randomBytes(MAC_BYTES)
                }
                channel.send(auth)

                val (initiatorKey, initiatorSignature, initiatorMac) = parseAuth(channel.receive(), mode)
                val h2 = crypto.hash(h1 + ownKey + signature)
                if (!crypto.verifySignature(initiatorKey, SIG_INITIATOR + h2 + initiatorKey, initiatorSignature)) {
                    channel.close()
                    return@withReadTimeout null
                }
                channel.peerIdentityKey = initiatorKey
                val rejection = when (mode) {
                    ConnectionMode.Pair -> {
                        val expectedMac = pairingSecret?.let { crypto.keyedHash(it, MAC_INITIATOR + h2 + initiatorKey) }
                        if (expectedMac != null && constantTimeEquals(expectedMac, initiatorMac!!)) {
                            policy.consumePairingSecret(pairingSecret)
                            null
                        } else {
                            Failure.Reason.WrongCode
                        }
                    }
                    ConnectionMode.Sync -> if (policy.isKnownPeer(initiatorKey)) null else Failure.Reason.UnknownDevice
                }
                if (rejection != null) {
                    // Authenticated as *someone*, so it may learn why: it can't fix anything with it.
                    runCatching { MessageChannel(channel).send(Failure(rejection)) }
                    channel.close()
                    return@withReadTimeout null
                }
                channel to mode
            } finally {
                crypto.wipe(ephemeral.secretKey)
            }
        }

    private data class Auth(val identityKey: ByteArray, val signature: ByteArray, val mac: ByteArray?)

    private fun parseAuth(bytes: ByteArray, mode: ConnectionMode): Auth {
        val base = Crypto.PUBLIC_KEY_BYTES + Crypto.SIGNATURE_BYTES
        val expected = if (mode == ConnectionMode.Pair) base + MAC_BYTES else base
        if (bytes.size != expected) throw LanException.Protocol("handshake size")
        return Auth(
            identityKey = bytes.copyOfRange(0, Crypto.PUBLIC_KEY_BYTES),
            signature = bytes.copyOfRange(Crypto.PUBLIC_KEY_BYTES, base),
            mac = if (mode == ConnectionMode.Pair) bytes.copyOfRange(base, expected) else null,
        )
    }

    private inline fun <T> refusedIfClosed(block: () -> T): T = try {
        block()
    } catch (e: ConnectionClosedException) {
        throw LanException.Refused()
    }
}

internal fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
    if (a.size != b.size) return false
    var diff = 0
    for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
    return diff == 0
}
