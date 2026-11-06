package io.github.igrvlhlb.secrt.lan

import io.github.igrvlhlb.secrt.crypto.Crypto
import io.github.igrvlhlb.secrt.crypto.DecryptionException
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.readFully
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/** A raw byte stream; a TCP socket outside tests. */
internal interface Connection {
    /** @throws ConnectionClosedException */
    suspend fun read(count: Int): ByteArray

    /** Writes and flushes. @throws ConnectionClosedException */
    suspend fun write(bytes: ByteArray)

    fun close()
}

/** The other side went away (or never answered) mid-conversation. */
internal class ConnectionClosedException(cause: Throwable? = null) : Exception("Connection closed", cause)

internal class SocketConnection(private val socket: Socket) : Connection {
    private val input = socket.openReadChannel()
    private val output = socket.openWriteChannel(autoFlush = false)

    override suspend fun read(count: Int): ByteArray = io {
        ByteArray(count).also { input.readFully(it) }
    }

    override suspend fun write(bytes: ByteArray) = io {
        output.writeFully(bytes)
        output.flush()
    }

    override fun close() = socket.close()

    private inline fun <T> io(block: () -> T): T = try {
        block()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        throw ConnectionClosedException(e)
    }
}

/**
 * The encrypted, authenticated byte channel set up by [Handshake]. Each direction has its own key;
 * every frame is XChaCha20-Poly1305 with a nonce that counts frames, so frames can't be altered,
 * dropped, replayed or reordered without the receiver noticing. Messages that end a conversation
 * are explicit, so a cut-off stream is never mistaken for a complete one.
 *
 * On the wire: `length (4 bytes, big-endian) | ciphertext`, at most [MAX_FRAME_BYTES] of plaintext.
 */
internal class SecureChannel(
    private val connection: Connection,
    private val crypto: Crypto,
    private val sendKey: ByteArray,
    private val receiveKey: ByteArray,
    /** The authenticated identity public key of the other side (set once the handshake checked it). */
    var peerIdentityKey: ByteArray? = null,
) {
    private var sentFrames = 0L
    private var receivedFrames = 0L

    suspend fun send(plaintext: ByteArray) {
        require(plaintext.size <= MAX_FRAME_BYTES) { "Frame too large" }
        val ciphertext = crypto.aeadEncrypt(sendKey, nonce(sentFrames++), plaintext, AAD)
        val frame = ByteArray(4 + ciphertext.size)
        frame.putInt(0, ciphertext.size)
        ciphertext.copyInto(frame, 4)
        connection.write(frame)
    }

    /**
     * @throws LanException.Protocol if the frame is oversized or fails authentication.
     * @throws ConnectionClosedException
     */
    suspend fun receive(timeoutMillis: Long = RECEIVE_TIMEOUT_MILLIS): ByteArray = withReadTimeout(timeoutMillis) {
        val length = connection.read(4).getInt(0)
        if (length < Crypto.AEAD_TAG_BYTES || length > MAX_FRAME_BYTES + Crypto.AEAD_TAG_BYTES) {
            throw LanException.Protocol("frame size")
        }
        val ciphertext = connection.read(length)
        try {
            crypto.aeadDecrypt(receiveKey, nonce(receivedFrames++), ciphertext, AAD)
        } catch (e: DecryptionException) {
            throw LanException.Protocol("tampered data", e)
        }
    }

    fun close() {
        connection.close()
        crypto.wipe(sendKey)
        crypto.wipe(receiveKey)
    }

    private fun nonce(counter: Long): ByteArray = ByteArray(Crypto.NONCE_BYTES).also { it.putLong(Crypto.NONCE_BYTES - 8, counter) }

    companion object {
        /** Fits the largest attachment (20 MB) plus encryption overhead in one frame. */
        const val MAX_FRAME_BYTES = 24 * 1024 * 1024

        /** Generous: a frame can be a 20 MB attachment on slow Wi-Fi. */
        const val RECEIVE_TIMEOUT_MILLIS = 120_000L

        private val AAD = "secrt-lan/1 frame".encodeToByteArray()
    }
}

internal suspend fun <T> withReadTimeout(millis: Long, block: suspend () -> T): T = try {
    withTimeout(millis) { block() }
} catch (e: TimeoutCancellationException) {
    throw ConnectionClosedException(e)
}

internal fun ByteArray.putInt(at: Int, value: Int) {
    for (i in 0 until 4) this[at + i] = (value shr (24 - 8 * i)).toByte()
}

internal fun ByteArray.getInt(at: Int): Int =
    (0 until 4).fold(0) { acc, i -> (acc shl 8) or (this[at + i].toInt() and 0xff) }

internal fun ByteArray.putLong(at: Int, value: Long) {
    for (i in 0 until 8) this[at + i] = (value shr (56 - 8 * i)).toByte()
}
