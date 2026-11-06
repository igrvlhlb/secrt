package io.github.igrvlhlb.secrt.backup

/**
 * Somewhere backups are kept: a WebDAV folder today, later Google Drive, OneDrive or an S3
 * bucket. A dumb file store: everything put here is ciphertext or the non-secret `vault.key` and
 * manifest, and all merging happens on the devices, so the server needs neither trust nor logic.
 *
 * Paths are relative to the target's root, use `/` as separator, and only contain characters that
 * need no escaping in URLs or object keys (`[A-Za-z0-9._-]`). A path ending in `/` is a directory.
 *
 * Implementations must make [put] atomic, as every server here does: a reader sees the old file
 * or the new one, never part of one. No locking or conditional writes are needed, because each
 * device only ever creates files of its own and never rewrites them (see [CloudBackup]).
 */
interface BackupTarget : AutoCloseable {
    /**
     * Identifies where the data lives (e.g. the server URL and user), so that bookkeeping about
     * what was already uploaded is kept per destination. Not secret, never contains credentials.
     */
    val id: String

    /**
     * Names of the entries directly inside [directory] (which ends in `/`): file names, and
     * sub-directory names followed by `/`. A missing directory is empty.
     */
    suspend fun list(directory: String): List<String>

    /** The file's content, or `null` if there is no such file. */
    suspend fun get(path: String): ByteArray?

    /** Creates or replaces a file, creating missing parent directories. */
    suspend fun put(path: String, bytes: ByteArray)

    /** Deletes a file; deleting a missing one is not an error. */
    suspend fun delete(path: String)

    /** Releases connections. */
    override fun close() {}
}

/** Something went wrong talking to the backup target. Messages are meant for the user. */
open class BackupException(message: String, cause: Throwable? = null) : Exception(message, cause)

class BackupAuthException : BackupException("The server refused the username or password")

class BackupNetworkException(cause: Throwable) :
    BackupException("Couldn't reach the server" + (cause.message?.let { ": $it" } ?: ""), cause)

class BackupServerException(message: String) : BackupException(message)

/** The target has no backup of the diary that was asked for. */
class NoBackupFoundException(message: String = "No secrt backup was found there") : BackupException(message)
