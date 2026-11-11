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

/**
 * Something went wrong talking to the backup target. Messages are in English, for logs and tests;
 * the UI shows its own (translated) text for each subclass and its fields.
 */
open class BackupException(message: String, cause: Throwable? = null) : Exception(message, cause)

class BackupAuthException : BackupException("The server refused the username or password")

/** The server couldn't be reached; [detail] is the system's reason, if it gave one. */
class BackupNetworkException(cause: Throwable) :
    BackupException("Couldn't reach the server" + (cause.message?.let { ": $it" } ?: ""), cause) {
    val detail: String? = cause.message
}

/** The server answered, but not as hoped: see [problem]. */
class BackupServerException(
    val problem: Problem,
    /** The file or folder concerned, for every problem but [Problem.OutOfSpace]. */
    val path: String? = null,
    /** What was being done, for [Problem.NotAllowed] and [Problem.Failed]. */
    val action: Action? = null,
    /** The HTTP status, for [Problem.Failed]. */
    val status: Int? = null,
) : BackupException(englishMessage(problem, path, action, status)) {

    enum class Problem { FileTooLarge, NoWebDavFolder, NotAllowed, OutOfSpace, Failed }

    enum class Action(internal val english: String) {
        List("list"),
        Download("download"),
        Upload("upload"),
        Delete("delete"),
        CreateFolder("create the folder"),
    }

    private companion object {
        fun englishMessage(problem: Problem, path: String?, action: Action?, status: Int?): String {
            val what = listOfNotNull(action?.english, path).joinToString(" ")
            return when (problem) {
                Problem.FileTooLarge -> "$path is larger than any backup file"
                Problem.NoWebDavFolder -> "No WebDAV folder answers at $path"
                Problem.NotAllowed -> "The server doesn't allow this account to $what"
                Problem.OutOfSpace -> "The server is out of space"
                Problem.Failed -> "The server couldn't $what (HTTP $status)"
            }
        }
    }
}

/** What the user typed isn't a server address. */
class InvalidServerAddressException : BackupException("That isn't a valid server address")

/** Another device changed the backup while this one was working on it. */
class BackupChangedException : BackupException("The backup changed meanwhile. Try again.")

/**
 * The user turned down taking on the backup's newer password (or recovery key), so nothing was
 * synced: [CloudBackup.pull] and [CloudBackup.sync] downloaded and uploaded nothing.
 */
class KeySlotsChangeDeclinedException :
    BackupException("Nothing was synced: this device keeps its current password.")

/** The target has no backup of the diary that was asked for. */
class NoBackupFoundException : BackupException("No secrt backup was found there")
