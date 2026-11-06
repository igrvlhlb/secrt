package io.github.igrvlhlb.secrt.lan

import io.github.igrvlhlb.secrt.crypto.Crypto
import kotlin.io.encoding.Base64

/**
 * What a pairing QR code carries: where to connect, which identity to expect there, and the
 * one-time secret both sides must prove they know. As text: `secrt-pair:` followed by base64url of
 *
 * ```
 * version (1) | port (2, big-endian) | address count n (1) | n IPv4 addresses (4 each) | identity key (32) | secret (32)
 * ```
 *
 * Binary rather than a URL with parameters: it keeps the code small (fewer, larger QR modules scan
 * more easily) and leaves nothing to escape.
 */
class PairingInvite(
    /** IPv4 addresses of the inviting device, dotted. */
    val addresses: List<String>,
    val port: Int,
    /** The inviting device's Ed25519 identity public key. */
    val identityKey: ByteArray,
    /** One-time pairing secret. */
    val secret: ByteArray,
) {
    init {
        require(addresses.size in 1..MAX_ADDRESSES) { "1 to $MAX_ADDRESSES addresses" }
        require(port in 1..65535) { "Invalid port" }
        require(identityKey.size == Crypto.PUBLIC_KEY_BYTES && secret.size == SECRET_BYTES) { "Invalid key sizes" }
        addresses.forEach { requireNotNull(parseIpv4(it)) { "Not an IPv4 address: $it" } }
    }

    fun toLink(): String {
        val bytes = ByteArray(4 + 4 * addresses.size + Crypto.PUBLIC_KEY_BYTES + SECRET_BYTES)
        bytes[0] = VERSION.toByte()
        bytes[1] = (port shr 8).toByte()
        bytes[2] = port.toByte()
        bytes[3] = addresses.size.toByte()
        addresses.forEachIndexed { i, address -> parseIpv4(address)!!.copyInto(bytes, 4 + 4 * i) }
        identityKey.copyInto(bytes, 4 + 4 * addresses.size)
        secret.copyInto(bytes, 4 + 4 * addresses.size + Crypto.PUBLIC_KEY_BYTES)
        return PREFIX + base64.encode(bytes)
    }

    companion object {
        const val PREFIX = "secrt-pair:"
        const val VERSION = 1
        const val SECRET_BYTES = 32
        const val MAX_ADDRESSES = 4

        private val base64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)

        /** @throws LanException.InvalidCode if [link] isn't a pairing code this version understands. */
        fun parse(link: String): PairingInvite {
            val text = link.trim()
            if (!text.startsWith(PREFIX)) throw LanException.InvalidCode()
            val bytes = try {
                base64.decode(text.removePrefix(PREFIX))
            } catch (e: IllegalArgumentException) {
                throw LanException.InvalidCode()
            }
            if (bytes.size < 4 || bytes[0].toInt() != VERSION) throw LanException.InvalidCode()
            val port = ((bytes[1].toInt() and 0xff) shl 8) or (bytes[2].toInt() and 0xff)
            val count = bytes[3].toInt() and 0xff
            if (count !in 1..MAX_ADDRESSES || port == 0) throw LanException.InvalidCode()
            val keyAt = 4 + 4 * count
            if (bytes.size != keyAt + Crypto.PUBLIC_KEY_BYTES + SECRET_BYTES) throw LanException.InvalidCode()
            val addresses = (0 until count).map { i ->
                (0 until 4).joinToString(".") { (bytes[4 + 4 * i + it].toInt() and 0xff).toString() }
            }
            return PairingInvite(
                addresses = addresses,
                port = port,
                identityKey = bytes.copyOfRange(keyAt, keyAt + Crypto.PUBLIC_KEY_BYTES),
                secret = bytes.copyOfRange(keyAt + Crypto.PUBLIC_KEY_BYTES, bytes.size),
            )
        }

        internal fun parseIpv4(address: String): ByteArray? {
            val parts = address.split('.')
            if (parts.size != 4) return null
            return ByteArray(4) { i ->
                val part = parts[i]
                if (part.isEmpty() || part.length > 3 || !part.all { it.isDigit() }) return null
                val value = part.toInt()
                if (value > 255) return null
                value.toByte()
            }
        }
    }
}
