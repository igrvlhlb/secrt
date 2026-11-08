package io.github.igrvlhlb.secrt.vault

import io.github.igrvlhlb.secrt.model.Hlc
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Contents of `vault.key`. Holds no secrets in clear: each slot is the data key encrypted
 * ("wrapped") with a key that only the slot's credential can reproduce.
 */
@Serializable
internal data class VaultHeader(
    val format: Int = CURRENT_FORMAT,
    val vaultId: String,
    val slots: List<KeySlot>,
    /**
     * Base64 key-check value of the data key (see [VaultManager.unlockWithDataKey]). Optional, so
     * headers written before it existed still read as format 1; added when first needed.
     */
    val keyCheck: String? = null,
    /**
     * When the key slots last changed, and on which device. Optional too (format 1 still): headers
     * written before it existed have none, and so do headers rewritten by such a version, which
     * drops fields it doesn't know. Either counts as older than any stamped header.
     */
    val slotsChanged: SlotsChange? = null,
) {
    companion object {
        const val CURRENT_FORMAT = 1
    }
}

/**
 * Stamp of a key-slot change: the password, or the recovery key, was set or removed. Devices of one
 * diary compare stamps to agree on the newest key slots (see docs/security.md).
 */
@Serializable
internal data class SlotsChange(
    /**
     * A hybrid logical clock value from the device that made the change (its node is that device's
     * id), always after the stamp it replaced, so a change made after adopting another device's
     * wins over it whatever the clocks say.
     */
    val at: Hlc,
    /**
     * Base64 of that device's name, sealed with the archive key: shown when another device is asked
     * to take this change on. Sealed because the file travels in clear (backups, exports).
     */
    val deviceName: String? = null,
)

@Serializable
internal sealed class KeySlot {
    /** Base64 of `nonce || ciphertext` of the data key. */
    abstract val wrappedKey: String
    abstract val salt: String

    @Serializable
    @SerialName("password")
    data class Password(
        override val salt: String,
        val opsLimit: Long,
        val memLimitBytes: Int,
        override val wrappedKey: String,
    ) : KeySlot()

    @Serializable
    @SerialName("recovery")
    data class Recovery(
        override val salt: String,
        override val wrappedKey: String,
    ) : KeySlot()
}
