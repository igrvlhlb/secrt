package io.github.igrvlhlb.secrt.vault

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
) {
    companion object {
        const val CURRENT_FORMAT = 1
    }
}

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
