package io.github.igrvlhlb.secrt.android

import android.content.Intent
import io.github.igrvlhlb.secrt.lan.LanSync

/** Release builds have no debug hooks: pairing codes come only from the camera. See `src/debug`. */
internal object DebugHooks {
    @Suppress("UNUSED_PARAMETER")
    fun handle(intent: Intent?, lan: LanSync) = Unit
}
