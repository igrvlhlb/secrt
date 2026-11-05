package io.github.igrvlhlb.secrt.archive

/** CRC-32 (IEEE 802.3), as used by ZIP. */
internal object Crc32 {
    private val table = IntArray(256) { n ->
        var c = n
        repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor 0xEDB88320.toInt() else c ushr 1 }
        c
    }

    fun of(bytes: ByteArray): Int {
        var crc = -1
        for (b in bytes) crc = table[(crc xor b.toInt()) and 0xFF] xor (crc ushr 8)
        return crc.inv()
    }
}
