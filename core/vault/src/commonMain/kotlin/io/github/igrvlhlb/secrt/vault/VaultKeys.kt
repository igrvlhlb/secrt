package io.github.igrvlhlb.secrt.vault

import io.github.igrvlhlb.secrt.crypto.Crypto

/**
 * Keys of an unlocked vault. Every key is derived from the single data key, so changing the
 * password never requires re-encrypting data. Call [close] when locking to wipe them from memory.
 */
class VaultKeys internal constructor(
    val vaultId: String,
    private val crypto: Crypto,
    internal val dataKey: ByteArray,
) : AutoCloseable {

    /** SQLCipher raw key for the database. */
    val databaseKey: ByteArray = crypto.deriveSubkey(dataKey, 1, "secrt_db")

    /** Encrypts attachment blobs. */
    val blobKey: ByteArray = crypto.deriveSubkey(dataKey, 2, "secrtblb")

    /** Keyed hash that names blobs, so file names don't reveal which attachments are identical across vaults. */
    val blobIdKey: ByteArray = crypto.deriveSubkey(dataKey, 3, "secrtbid")

    /** Encrypts revision segments in backups and exports. */
    val archiveKey: ByteArray = crypto.deriveSubkey(dataKey, 4, "secrtarc")

    override fun close() {
        listOf(dataKey, databaseKey, blobKey, blobIdKey, archiveKey).forEach(crypto::wipe)
    }
}
