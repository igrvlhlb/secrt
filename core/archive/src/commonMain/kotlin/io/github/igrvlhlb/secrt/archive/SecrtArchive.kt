package io.github.igrvlhlb.secrt.archive

import io.github.igrvlhlb.secrt.crypto.Crypto
import io.github.igrvlhlb.secrt.crypto.DecryptionException
import io.github.igrvlhlb.secrt.crypto.open
import io.github.igrvlhlb.secrt.crypto.seal
import io.github.igrvlhlb.secrt.model.EntryPayload
import io.github.igrvlhlb.secrt.model.RecordType
import io.github.igrvlhlb.secrt.model.Revision
import io.github.igrvlhlb.secrt.model.SecrtJson
import io.github.igrvlhlb.secrt.vault.VaultKeys
import kotlinx.serialization.Serializable
import okio.BufferedSink
import okio.BufferedSource

open class ArchiveException(message: String, cause: Throwable? = null) : Exception(message, cause)

class NotAnArchiveException : ArchiveException("This isn't a secrt backup file")

class CorruptArchiveException(message: String, cause: Throwable? = null) : ArchiveException("The backup is damaged: $message", cause)

class UnsupportedArchiveException(format: Int) : ArchiveException("This backup was made by a newer version of secrt (format $format)")

class DifferentVaultException : ArchiveException("This backup belongs to a different diary")

/**
 * The `.secrt` file: a stored ZIP holding, in this order,
 *
 * ```
 * manifest.json          {"format":1,"vaultId":"…"}           plain
 * vault.key              key slots, same as on the device     plain (holds no secrets in clear)
 * log/<deviceId>.seg     each device's revisions              encrypted with the archive key
 * blobs/<blobId>         attachments                          encrypted, copied as stored
 * ```
 *
 * Cloud backups (`core:backup`) reuse the segment encryption ([sealSegment], [openSegment]) and
 * the attachment files as stored. Restoring or importing means applying the revisions through the
 * merge engine, exactly like sync.
 */
object SecrtArchive {
    const val EXTENSION = "secrt"
    const val FORMAT = 1

    private const val MANIFEST = "manifest.json"
    private const val VAULT_KEY = "vault.key"
    private const val LOG_PREFIX = "log/"
    private const val LOG_SUFFIX = ".seg"
    private const val BLOB_PREFIX = "blobs/"

    /** Attachments are capped at 20 MB; this leaves room for encryption overhead and large logs. */
    private const val MAX_ENTRY_BYTES = 64L * 1024 * 1024

    @Serializable
    internal data class Manifest(val format: Int, val vaultId: String)

    @Serializable
    private data class Segment(val revisions: List<Revision>)

    /**
     * Writes [revisions] (normally all of them) and the attachments they reference.
     * @param readBlob returns an attachment's encrypted file.
     */
    fun write(
        sink: BufferedSink,
        crypto: Crypto,
        keys: VaultKeys,
        vaultKeyJson: String,
        revisions: List<Revision>,
        readBlob: (blobId: String) -> ByteArray,
    ) {
        val zip = StoredZipWriter(sink)
        zip.put(MANIFEST, SecrtJson.encodeToString(Manifest(FORMAT, keys.vaultId)).encodeToByteArray())
        zip.put(VAULT_KEY, vaultKeyJson.encodeToByteArray())
        revisions.groupBy { it.deviceId }.entries.sortedBy { it.key }.forEach { (deviceId, deviceRevisions) ->
            val name = "$LOG_PREFIX$deviceId$LOG_SUFFIX"
            zip.put(name, sealSegment(crypto, keys, name, deviceRevisions))
        }
        referencedBlobs(revisions).forEach { blobId -> zip.put("$BLOB_PREFIX$blobId", readBlob(blobId)) }
        zip.finish()
    }

    /**
     * Reads an archive front to back.
     *
     * @param unlock receives the archive's vault id and `vault.key`, and returns the keys to read
     *   it with, or throws (wrong password, different vault…) to stop before anything else is read.
     * @param storeBlob receives each attachment's encrypted file.
     * @return all revisions in the archive.
     */
    fun read(
        source: BufferedSource,
        crypto: Crypto,
        unlock: (vaultId: String, vaultKeyJson: String) -> VaultKeys,
        storeBlob: (blobId: String, encrypted: ByteArray) -> Unit,
    ): List<Revision> {
        val zip = StoredZipReader(source, MAX_ENTRY_BYTES)
        val manifest = try {
            val (name, data) = zip.next() ?: throw NotAnArchiveException()
            if (name != MANIFEST) throw NotAnArchiveException()
            SecrtJson.decodeFromString<Manifest>(data.decodeToString())
        } catch (e: CorruptArchiveException) {
            throw NotAnArchiveException()
        } catch (e: IllegalArgumentException) {
            throw NotAnArchiveException()
        }
        if (manifest.format > FORMAT) throw UnsupportedArchiveException(manifest.format)

        val (keyName, keyData) = zip.next() ?: throw CorruptArchiveException("missing $VAULT_KEY")
        if (keyName != VAULT_KEY) throw CorruptArchiveException("missing $VAULT_KEY")
        val keys = unlock(manifest.vaultId, keyData.decodeToString())
        if (keys.vaultId != manifest.vaultId) throw CorruptArchiveException("vault id mismatch")

        val revisions = mutableListOf<Revision>()
        while (true) {
            val (name, data) = zip.next() ?: break
            when {
                name.startsWith(LOG_PREFIX) && name.endsWith(LOG_SUFFIX) -> revisions += openSegment(crypto, keys, name, data)
                name.startsWith(BLOB_PREFIX) -> try {
                    storeBlob(name.removePrefix(BLOB_PREFIX), data)
                } catch (e: DecryptionException) {
                    throw CorruptArchiveException("$name can't be decrypted", e)
                }
                // Unknown entries are skipped, so newer versions can add files.
            }
        }
        return revisions
    }

    /**
     * Encrypts a batch of revisions with the archive key. The AAD binds the ciphertext to the vault
     * and to [name], its path inside the archive or backup folder, so a segment can't be passed off
     * as another one or moved into another diary.
     */
    fun sealSegment(crypto: Crypto, keys: VaultKeys, name: String, revisions: List<Revision>): ByteArray {
        val json = SecrtJson.encodeToString(Segment(revisions.sortedWith(compareBy({ it.deviceId }, { it.seq }))))
        return crypto.seal(keys.archiveKey, json.encodeToByteArray(), aad(keys.vaultId, name))
    }

    /**
     * Reverses [sealSegment].
     * @throws CorruptArchiveException if [data] was tampered with, renamed, or isn't a segment.
     */
    fun openSegment(crypto: Crypto, keys: VaultKeys, name: String, data: ByteArray): List<Revision> {
        val json = try {
            crypto.open(keys.archiveKey, data, aad(keys.vaultId, name))
        } catch (e: DecryptionException) {
            throw CorruptArchiveException("$name can't be decrypted", e)
        }
        return try {
            SecrtJson.decodeFromString<Segment>(json.decodeToString()).revisions
        } catch (e: IllegalArgumentException) {
            throw CorruptArchiveException("$name can't be read", e)
        }
    }

    /** Suggested file name for an export made on [date] (ISO `yyyy-MM-dd`). */
    fun fileName(date: String) = "secrt-backup-$date.$EXTENSION"

    private fun aad(vaultId: String, entryName: String) = "secrt-archive:$vaultId:$entryName".encodeToByteArray()

    /** Attachments referenced by any of [revisions] (all of them: the history must stay complete). */
    fun referencedBlobs(revisions: List<Revision>): List<String> =
        revisions.asSequence()
            .filter { it.recordType == RecordType.ENTRY && it.payload != null }
            .flatMap { SecrtJson.decodeFromString<EntryPayload>(it.payload!!).attachments }
            .map { it.blobId }
            .distinct()
            .sorted()
            .toList()
}
