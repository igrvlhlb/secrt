package io.github.igrvlhlb.secrt.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Hybrid Logical Clock timestamp.
 *
 * Orders events across devices even when wall clocks disagree slightly: [millis] follows the
 * wall clock, [counter] breaks ties within the same millisecond, and [node] (the device id)
 * makes every timestamp unique.
 */
@Serializable(with = HlcSerializer::class)
data class Hlc(val millis: Long, val counter: Int, val node: String) : Comparable<Hlc> {

    override fun compareTo(other: Hlc): Int =
        compareValuesBy(this, other, Hlc::millis, Hlc::counter, Hlc::node)

    /** Encodes to a string whose lexicographic order matches [compareTo]. */
    fun encode(): String =
        millis.toString().padStart(MILLIS_DIGITS, '0') + SEP +
            counter.toString().padStart(COUNTER_DIGITS, '0') + SEP + node

    override fun toString(): String = encode()

    companion object {
        private const val MILLIS_DIGITS = 15
        private const val COUNTER_DIGITS = 6
        private const val SEP = '-'

        fun decode(value: String): Hlc {
            val first = value.indexOf(SEP)
            val second = value.indexOf(SEP, first + 1)
            require(first > 0 && second > first) { "Invalid HLC: $value" }
            return Hlc(
                millis = value.substring(0, first).toLong(),
                counter = value.substring(first + 1, second).toInt(),
                node = value.substring(second + 1),
            )
        }
    }
}

object HlcSerializer : KSerializer<Hlc> {
    override val descriptor = PrimitiveSerialDescriptor("secrt.Hlc", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Hlc) = encoder.encodeString(value.encode())
    override fun deserialize(decoder: Decoder): Hlc = Hlc.decode(decoder.decodeString())
}

/**
 * Issues monotonically increasing [Hlc]s for one device.
 *
 * Not thread-safe: callers serialize access (the storage layer does it under its write lock).
 */
class HlcClock(
    private val node: String,
    private val wallClock: () -> Long,
    initial: Hlc? = null,
) {
    private var last: Hlc = initial ?: Hlc(0, 0, node)

    fun now(): Hlc {
        val wall = wallClock()
        last = if (wall > last.millis) Hlc(wall, 0, node) else Hlc(last.millis, last.counter + 1, node)
        return last
    }

    /** Advances the clock past a timestamp received from another device. */
    fun observe(remote: Hlc) {
        val wall = wallClock()
        val millis = maxOf(wall, last.millis, remote.millis)
        val counter = when {
            millis == last.millis && millis == remote.millis -> maxOf(last.counter, remote.counter) + 1
            millis == last.millis -> last.counter + 1
            millis == remote.millis -> remote.counter + 1
            else -> 0
        }
        last = Hlc(millis, counter, node)
    }
}
