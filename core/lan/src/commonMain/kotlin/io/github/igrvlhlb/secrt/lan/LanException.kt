package io.github.igrvlhlb.secrt.lan

/** Why a pairing or sync over the local network failed. Messages are meant for the user. */
sealed class LanException(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /** No listed address answered, or the connection dropped. */
    class Unreachable(name: String, cause: Throwable? = null) : LanException(
        "Couldn't reach $name. Check that secrt is open and unlocked on it, showing Devices, and that both are on the same Wi-Fi.",
        cause,
    )

    /** The connection dropped after it was set up. Whatever was fully received is kept. */
    class ConnectionLost(name: String, cause: Throwable? = null) : LanException("The connection to $name was lost. Try again.", cause)

    /** The other side closed the connection before authenticating, e.g. it isn't showing a pairing code. */
    class Refused : LanException("The other device isn't accepting this right now. On it, open Devices (or show a new pairing code) and try again.")

    /** The pairing secret didn't match: the code expired, was already used, or was mistyped/forged. */
    class WrongCode : LanException("The pairing code didn't match. It may have expired or been used already: show a new one and scan it again.")

    /** The other side no longer has this device among its paired devices. */
    class UnknownDevice(name: String) : LanException("$name doesn't recognize this device anymore. Pair them again.")

    /** Both sides hold a diary, and not the same one. */
    class DifferentDiary : LanException(
        "These devices hold different diaries, so they can't sync. To use a diary on another device, set that device up " +
            "from this one, on its first screen.",
    )

    /** A device without a diary scanned the code of another one without a diary: there's nothing to set it up from. */
    class NoDiary : LanException("The other device has no diary yet. Scan the code of the device that holds your diary.")

    /** The other side runs a version of secrt that can't set up a device that scanned its code. */
    class Outdated : LanException("The other device needs a newer version of secrt to set this one up. Update it and try again.")

    /** The diary is locked on this or the other side. */
    class Locked : LanException("The diary is locked. Unlock it on both devices and try again.")

    /** The other side is busy with another device. */
    class Busy : LanException("The other device is busy with another sync. Try again in a moment.")

    /** Data failed authentication or didn't follow the protocol. Nothing from it was applied. */
    class Protocol(detail: String, cause: Throwable? = null) : LanException("The connection failed a security check ($detail).", cause)

    /** The link scanned isn't a secrt pairing code. */
    class InvalidCode : LanException("This isn't a secrt pairing code.")
}
