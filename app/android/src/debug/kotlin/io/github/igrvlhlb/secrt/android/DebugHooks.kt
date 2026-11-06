package io.github.igrvlhlb.secrt.android

import android.content.Intent
import android.util.Log
import io.github.igrvlhlb.secrt.lan.LanSync

/**
 * Debug builds only (this source set isn't part of release builds): lets a test feed a pairing code
 * without pointing the camera at a screen, e.g.
 *
 * ```
 * adb shell am start -n io.github.igrvlhlb.secrt/.android.MainActivity --es io.github.igrvlhlb.secrt.debug.PAIRING_LINK 'secrt-pair:…'
 * ```
 *
 * The code goes exactly where a scanned one would (the Devices screen, once the diary is unlocked),
 * so the user still sees what happens. Release builds compile the no-op in `src/release`.
 */
internal object DebugHooks {
    private const val EXTRA_PAIRING_LINK = "io.github.igrvlhlb.secrt.debug.PAIRING_LINK"

    fun handle(intent: Intent?, lan: LanSync) {
        val link = intent?.getStringExtra(EXTRA_PAIRING_LINK) ?: return
        intent.removeExtra(EXTRA_PAIRING_LINK)
        Log.i("secrt", "Debug: pairing code received through an intent extra")
        lan.pendingPairingLink.value = link
    }
}
