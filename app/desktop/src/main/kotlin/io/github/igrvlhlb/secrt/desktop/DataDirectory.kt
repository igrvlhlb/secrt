package io.github.igrvlhlb.secrt.desktop

import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

/** System property that points the app at another data directory (tests, dev runs, a USB stick). */
const val DATA_DIR_PROPERTY = "secrt.dataDir"

/** Environment variable with the same meaning as [DATA_DIR_PROPERTY]; the property wins if both are set. */
const val DATA_DIR_ENV = "SECRT_DATA_DIR"

/**
 * Where the diary lives: [DATA_DIR_PROPERTY] or [DATA_DIR_ENV] if set, otherwise the OS's usual
 * place for per-user app data:
 * - macOS: `~/Library/Application Support/secrt`
 * - Windows: `%APPDATA%\secrt`
 * - Linux and others: `$XDG_DATA_HOME/secrt`, or `~/.local/share/secrt`
 *
 * The parameters exist for tests; the app uses the real environment.
 */
fun dataDirectory(
    property: (String) -> String? = System::getProperty,
    env: (String) -> String? = System::getenv,
): File {
    property(DATA_DIR_PROPERTY)?.takeIf { it.isNotBlank() }?.let { return File(it) }
    env(DATA_DIR_ENV)?.takeIf { it.isNotBlank() }?.let { return File(it) }

    val home = File(requireNotNull(property("user.home")) { "No home directory" })
    val os = property("os.name").orEmpty().lowercase()
    return when {
        os.startsWith("mac") -> File(home, "Library/Application Support/secrt")
        os.startsWith("windows") -> File(env("APPDATA")?.takeIf { it.isNotBlank() } ?: File(home, "AppData/Roaming").path, "secrt")
        else -> File(env("XDG_DATA_HOME")?.takeIf { it.isNotBlank() } ?: File(home, ".local/share").path, "secrt")
    }
}

/**
 * Creates [directory] if needed. Where the file system supports it, only the user can open it:
 * everything inside is encrypted, but file names and sizes still say something.
 */
fun ensureDataDirectory(directory: File) {
    if (directory.isDirectory) return
    val path = directory.toPath()
    if ("posix" in FileSystems.getDefault().supportedFileAttributeViews()) {
        Files.createDirectories(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
    } else {
        Files.createDirectories(path)
    }
}
