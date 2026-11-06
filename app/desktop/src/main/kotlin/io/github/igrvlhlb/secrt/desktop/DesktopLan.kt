package io.github.igrvlhlb.secrt.desktop

import io.github.igrvlhlb.secrt.crypto.SodiumCrypto
import io.github.igrvlhlb.secrt.lan.LanSync
import io.github.igrvlhlb.secrt.session.AppSession
import kotlinx.coroutines.Dispatchers
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import java.io.File
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * LAN sync for the desktop session over [dataDirectory]. A diary received from a phone is staged in
 * `lan/` there (encrypted, like a backup file), outside `vault/`, which restoring wipes.
 */
fun desktopLanSync(session: AppSession, dataDirectory: File): LanSync {
    val name by lazy(::computerName)
    return LanSync(
        session = session,
        cryptoProvider = { SodiumCrypto.create() },
        fileSystem = FileSystem.SYSTEM,
        tempDirectory = dataDirectory.toOkioPath() / "lan",
        deviceName = { name },
        ioDispatcher = Dispatchers.IO,
    )
}

/** The name the user gave this computer ("Igor's MacBook Air"), else its host name. */
private fun computerName(): String {
    if (System.getProperty("os.name").orEmpty().startsWith("Mac")) {
        runCatching {
            val process = ProcessBuilder("scutil", "--get", "ComputerName").redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText().trim()
            if (process.waitFor(2, TimeUnit.SECONDS) && process.exitValue() == 0 && output.isNotEmpty()) return output
        }
    }
    System.getenv("COMPUTERNAME")?.takeIf { it.isNotBlank() }?.let { return it }
    return runCatching { InetAddress.getLocalHost().hostName.removeSuffix(".local") }.getOrNull()?.takeIf { it.isNotBlank() } ?: "Computer"
}
