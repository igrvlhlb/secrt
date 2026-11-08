package io.github.igrvlhlb.secrt.desktop

import io.github.igrvlhlb.secrt.crypto.SodiumCrypto
import io.github.igrvlhlb.secrt.session.AppSession
import io.github.igrvlhlb.secrt.storage.SqlCipherDriverFactory
import kotlinx.coroutines.Dispatchers
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import java.io.Closeable
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

/**
 * The session over the diary in [dataDirectory], laid out like on Android: `vault/` holds
 * `vault.key` and the encrypted attachments, next to the encrypted database `secrt.db`.
 */
fun desktopSession(dataDirectory: File): AppSession {
    ensureDataDirectory(dataDirectory)
    val name by lazy(::computerName)
    return AppSession(
        fileSystem = FileSystem.SYSTEM,
        vaultDirectory = dataDirectory.toOkioPath() / "vault",
        driverFactory = SqlCipherDriverFactory(File(dataDirectory, "secrt.db")),
        cryptoProvider = { SodiumCrypto.create() },
        ioDispatcher = Dispatchers.IO,
        deviceName = { name },
    )
}

/**
 * Makes sure only one app instance uses [dataDirectory]: two would each hold their own view of the
 * vault and could overwrite each other's `vault.key`. The OS releases the lock if the app dies.
 *
 * @return the lock to hold while the app runs, or `null` if another instance has it.
 */
fun lockDataDirectory(dataDirectory: File): Closeable? {
    ensureDataDirectory(dataDirectory)
    val channel = FileChannel.open(File(dataDirectory, ".lock").toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
    val lock = runCatching { channel.tryLock() }.getOrNull()
    if (lock == null) {
        channel.close()
        return null
    }
    return Closeable {
        lock.release()
        channel.close()
    }
}
