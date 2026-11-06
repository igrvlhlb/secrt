package io.github.igrvlhlb.secrt.session

import io.github.igrvlhlb.secrt.crypto.Crypto
import io.github.igrvlhlb.secrt.storage.BlobStore
import io.github.igrvlhlb.secrt.storage.DiaryStore
import io.github.igrvlhlb.secrt.vault.VaultKeys

/**
 * What a transport (cloud backup, and later LAN sync) works with while the diary is open. Only
 * valid inside [AppSession.withOpenDiary] or [AppSession.restoreFrom]: the keys are wiped when the
 * diary locks.
 */
class OpenDiary internal constructor(
    val store: DiaryStore,
    val keys: VaultKeys,
    val blobs: BlobStore,
    val crypto: Crypto,
    /** The installed `vault.key`: key slots only, no secrets in clear. */
    val vaultKeyJson: String,
)

/** Handed to the source of an [AppSession.restoreFrom]. */
class RestoreContext internal constructor(
    val crypto: Crypto,
    private val unlockHeader: (vaultKeyJson: String) -> VaultKeys,
    private val blobStore: (VaultKeys) -> BlobStore,
) {
    internal var keys: VaultKeys? = null
        private set
    internal var vaultKeyJson: String? = null
        private set
    private var unlockedBlobs: BlobStore? = null

    /**
     * Unlocks the copy's `vault.key` with the credential given to the restore; it becomes this
     * device's `vault.key` once the restore succeeds. Call it once, before anything else.
     *
     * @throws io.github.igrvlhlb.secrt.vault.InvalidCredentialsException
     * @throws io.github.igrvlhlb.secrt.vault.InvalidVaultHeaderException
     */
    fun unlock(vaultKeyJson: String): VaultKeys {
        check(keys == null) { "Already unlocked" }
        return unlockHeader(vaultKeyJson).also {
            keys = it
            this.vaultKeyJson = vaultKeyJson
            unlockedBlobs = blobStore(it)
        }
    }

    /** Where the copy's attachments go (encrypted, as stored). Available after [unlock]. */
    val blobs: BlobStore get() = checkNotNull(unlockedBlobs) { "Unlock the vault key first" }
}
