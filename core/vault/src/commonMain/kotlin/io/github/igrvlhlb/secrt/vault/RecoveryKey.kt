package io.github.igrvlhlb.secrt.vault

/**
 * Human-friendly encoding of the 160-bit recovery key: Crockford base32 in groups of four,
 * e.g. `7K3M-Q0ZD-...`. Decoding is forgiving: case, spaces, dashes and the look-alikes
 * O/I/L are accepted.
 */
internal object RecoveryKey {
    const val BYTES = 20
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    private const val GROUP = 4

    fun encode(bytes: ByteArray): String {
        require(bytes.size == BYTES)
        val out = StringBuilder()
        var buffer = 0
        var bits = 0
        for (b in bytes) {
            buffer = (buffer shl 8) or (b.toInt() and 0xFF)
            bits += 8
            while (bits >= 5) {
                out.append(ALPHABET[(buffer shr (bits - 5)) and 0x1F])
                bits -= 5
            }
        }
        return out.chunked(GROUP).joinToString("-")
    }

    /** @return the key bytes, or `null` if [text] is not a well-formed recovery key. */
    fun decode(text: String): ByteArray? {
        val chars = text.uppercase()
            .filterNot { it == '-' || it.isWhitespace() }
            .map { c -> when (c) { 'O' -> '0'; 'I', 'L' -> '1'; else -> c } }
        if (chars.size != BYTES * 8 / 5) return null
        val out = ByteArray(BYTES)
        var buffer = 0
        var bits = 0
        var index = 0
        for (c in chars) {
            val value = ALPHABET.indexOf(c)
            if (value < 0) return null
            buffer = (buffer shl 5) or value
            bits += 5
            if (bits >= 8) {
                out[index++] = (buffer shr (bits - 8)).toByte()
                bits -= 8
            }
        }
        return out
    }
}
