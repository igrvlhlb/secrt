package io.github.igrvlhlb.secrt.android

import android.app.Application
import android.os.Build
import android.provider.Settings
import io.github.igrvlhlb.secrt.crypto.SodiumCrypto
import io.github.igrvlhlb.secrt.lan.LanSync
import io.github.igrvlhlb.secrt.session.AndroidBiometricKeyStore
import io.github.igrvlhlb.secrt.session.AppSession
import io.github.igrvlhlb.secrt.storage.SqlCipherDriverFactory
import io.github.igrvlhlb.secrt.ui.AutoLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okio.FileSystem
import okio.Path.Companion.toOkioPath

/** Holds the objects that must outlive activities: the session (and so the open diary). */
class SecrtApplication : Application() {

    val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val biometrics: AndroidBiometricKeyStore by lazy { AndroidBiometricKeyStore(this) }

    val session: AppSession by lazy {
        AppSession(
            fileSystem = FileSystem.SYSTEM,
            vaultDirectory = filesDir.toOkioPath() / "vault",
            driverFactory = SqlCipherDriverFactory(this),
            cryptoProvider = { SodiumCrypto.create() },
            ioDispatcher = Dispatchers.IO,
            biometrics = biometrics,
        )
    }

    /**
     * Pairing and sync with the user's other devices. The phone listens only while its Devices
     * screen is open; nothing extra is needed for that while targetSdk is below 37 (see
     * docs/architecture.md, "LAN pairing & sync").
     */
    val lan: LanSync by lazy {
        LanSync(
            session = session,
            cryptoProvider = { SodiumCrypto.create() },
            fileSystem = FileSystem.SYSTEM,
            tempDirectory = cacheDir.toOkioPath() / "lan",
            deviceName = ::deviceName,
            ioDispatcher = Dispatchers.IO,
        )
    }

    /** The name the user gave the phone (e.g. "Galaxy S20"), else its model. */
    private fun deviceName(): String =
        Settings.Global.getString(contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() } ?: Build.MODEL

    /** Per process, not per activity: its timer must survive the activity being recreated or closed. */
    val autoLock: AutoLock by lazy { AutoLock(session, backgroundScope) }
}
