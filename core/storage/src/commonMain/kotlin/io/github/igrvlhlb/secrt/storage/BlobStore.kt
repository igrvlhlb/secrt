package io.github.igrvlhlb.secrt.storage

import io.github.igrvlhlb.secrt.crypto.Crypto
import io.github.igrvlhlb.secrt.crypto.DecryptionException
import io.github.igrvlhlb.secrt.crypto.open
import io.github.igrvlhlb.secrt.crypto.seal
import io.github.igrvlhlb.secrt.crypto.toHex
import io.github.igrvlhlb.secrt.vault.VaultKeys
import okio.FileSystem
import okio.Path

/**
 * Encrypted, content-addressed attachment files. A blob's id is a keyed hash of its content, so
 * identical files are stored once and blobs never change (and therefore never conflict).
 */
class BlobStore(
    private val fileSystem: FileSystem,
    private val directory: Path,
    private val crypto: Crypto,
    private val keys: VaultKeys,
) {
    /** Stores [bytes] encrypted and returns the blob id. */
    fun put(bytes: ByteArray): String {
        val id = crypto.keyedHash(keys.blobIdKey, bytes).toHex()
        val path = directory / id
        if (!fileSystem.exists(path)) {
            fileSystem.createDirectories(directory)
            val temp = directory / "$id.tmp"
            fileSystem.write(temp) { write(crypto.seal(keys.blobKey, bytes, id.encodeToByteArray())) }
            fileSystem.atomicMove(temp, path)
        }
        return id
    }

    /** @throws io.github.igrvlhlb.secrt.crypto.DecryptionException if the file was tampered with. */
    fun get(id: String): ByteArray {
        val sealed = fileSystem.read(directory / id) { readByteArray() }
        return crypto.open(keys.blobKey, sealed, id.encodeToByteArray())
    }

    fun exists(id: String): Boolean = fileSystem.exists(directory / id)

    /** The blob's encrypted file, for copying it as-is into an archive. */
    fun readEncrypted(id: String): ByteArray = fileSystem.read(directory / id) { readByteArray() }

    /**
     * Stores an encrypted blob copied from an archive of this same vault, after checking that it
     * decrypts and matches its id.
     *
     * @throws DecryptionException if it was tampered with.
     */
    fun putEncrypted(id: String, sealed: ByteArray) {
        val plain = crypto.open(keys.blobKey, sealed, id.encodeToByteArray())
        if (crypto.keyedHash(keys.blobIdKey, plain).toHex() != id) {
            throw DecryptionException("Attachment content doesn't match its id")
        }
        if (exists(id)) return
        fileSystem.createDirectories(directory)
        val temp = directory / "$id.tmp"
        fileSystem.write(temp) { write(sealed) }
        fileSystem.atomicMove(temp, directory / id)
    }
}
