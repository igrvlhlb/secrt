package io.github.igrvlhlb.secrt.android

import android.app.Application
import io.github.igrvlhlb.secrt.crypto.SodiumCrypto
import io.github.igrvlhlb.secrt.session.AppSession
import io.github.igrvlhlb.secrt.storage.SqlCipherDriverFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okio.FileSystem
import okio.Path.Companion.toOkioPath

/** Holds the objects that must outlive activities: the session (and so the open diary). */
class SecrtApplication : Application() {

    val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val session: AppSession by lazy {
        AppSession(
            fileSystem = FileSystem.SYSTEM,
            vaultDirectory = filesDir.toOkioPath() / "vault",
            driverFactory = SqlCipherDriverFactory(this),
            cryptoProvider = { SodiumCrypto.create() },
            ioDispatcher = Dispatchers.IO,
        )
    }
}
