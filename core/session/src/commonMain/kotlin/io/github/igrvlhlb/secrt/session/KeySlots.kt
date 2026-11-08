package io.github.igrvlhlb.secrt.session

import io.github.igrvlhlb.secrt.model.Hlc

/**
 * Newer key slots (password and recovery key) of this diary, set on another device: once taken on
 * ([AppSession.adoptKeySlots]), this device opens with them from its next unlock. The data key
 * stays the same, so nothing else changes: the diary stays open, biometric unlock keeps working.
 */
class KeySlotsChange internal constructor(
    /** The device where the password or recovery key was changed, if known. */
    val changedOn: String?,
    /** When, in epoch milliseconds by that device's clock. */
    val changedAt: Long,
    internal val stamp: Hlc,
    internal val vaultKeyJson: String,
)

/** How another copy of this diary's `vault.key` compares with this device's ([AppSession.compareKeySlots]). */
sealed interface KeySlotsComparison {
    /** The same key-slot change, or neither copy was stamped (both predate stamps): nothing to do. */
    data object Same : KeySlotsComparison

    /** This device's are newer: the other copy should take them on. */
    data object NewerHere : KeySlotsComparison

    /** The other copy's are newer: this device would take them on, if the user agrees. */
    class NewerThere(val change: KeySlotsChange) : KeySlotsComparison
}
