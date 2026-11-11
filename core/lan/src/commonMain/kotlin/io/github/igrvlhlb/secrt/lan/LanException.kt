package io.github.igrvlhlb.secrt.lan

/**
 * Why a pairing or sync over the local network failed. Messages are in English, for logs and
 * tests; the UI shows its own (translated) text for each subclass and its fields. A `null`
 * device name means the other device's name isn't known yet.
 */
sealed class LanException(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /** No listed address answered, or the connection dropped. */
    class Unreachable(val deviceName: String, cause: Throwable? = null) : LanException(
        "Couldn't reach $deviceName. Check that secrt is open and unlocked on it, showing Devices, and that both are on the same Wi-Fi.",
        cause,
    )

    /** The connection dropped after it was set up. Whatever was fully received is kept. */
    class ConnectionLost(val deviceName: String, cause: Throwable? = null) :
        LanException("The connection to $deviceName was lost. Try again.", cause)

    /** The other side closed the connection before authenticating, e.g. it isn't showing a pairing code. */
    class Refused : LanException("The other device isn't accepting this right now. On it, open Devices (or show a new pairing code) and try again.")

    /** The pairing secret didn't match: the code expired, was already used, or was mistyped/forged. */
    class WrongCode : LanException("The pairing code didn't match. It may have expired or been used already: show a new one and scan it again.")

    /** The other side no longer has this device among its paired devices. */
    class UnknownDevice(val deviceName: String?) :
        LanException("${deviceName ?: OTHER_DEVICE} doesn't recognize this device anymore. Pair them again.")

    /** Both sides hold a diary, and not the same one. */
    class DifferentDiary : LanException(
        "These devices hold different diaries, so they can't sync. To use a diary on another device, set that device up " +
            "from this one, on its first screen.",
    )

    /** A device without a diary scanned the code of another one without a diary: there's nothing to set it up from. */
    class NoDiary : LanException("The other device has no diary yet. Scan the code of the device that holds your diary.")

    /** The other side runs a version of secrt too old for what was asked (setting this device up, or syncing). */
    class Outdated(val deviceName: String? = null) :
        LanException("${deviceName ?: OTHER_DEVICE} runs an older version of secrt. Update secrt on it and try again.")

    /** The user said no on this device (or didn't answer in time): nothing was exchanged with [deviceName]. */
    class Declined(val reason: Reason, val deviceName: String) : LanException(
        when (reason) {
            Reason.NothingSent -> "Nothing was sent to $deviceName."
            Reason.KeepsPassword -> "Not synced with $deviceName: this device keeps its password."
        },
    ) {
        enum class Reason {
            /** Its diary wasn't sent to a device without one. */
            NothingSent,

            /** Not synced, since that would have taken on the other device's password. */
            KeepsPassword,
        }
    }

    /** The other device's user said no, or didn't answer in time: nothing was exchanged. */
    class DeclinedThere(val reason: Reason, val deviceName: String?) : LanException(
        (deviceName ?: OTHER_DEVICE).let { name ->
            when (reason) {
                Reason.SendDeclined -> "$name declined to send its diary."
                Reason.NoAnswer -> "Nobody answered on $name in time. Try again."
                Reason.KeepsPassword -> "$name declined: its password would have changed."
            }
        },
    ) {
        enum class Reason { SendDeclined, NoAnswer, KeepsPassword }
    }

    /** The diary is locked on this or the other side. */
    class Locked : LanException("The diary is locked. Unlock it on both devices and try again.")

    /** The other side is busy with another device. */
    class Busy : LanException("The other device is busy with another sync. Try again in a moment.")

    /** Data failed authentication or didn't follow the protocol. Nothing from it was applied. [detail] is technical. */
    class Protocol(val detail: String, cause: Throwable? = null) : LanException("The connection failed a security check ($detail).", cause)

    /** The link scanned isn't a secrt pairing code. */
    class InvalidCode : LanException("This isn't a secrt pairing code.")

    private companion object {
        const val OTHER_DEVICE = "The other device"
    }
}
