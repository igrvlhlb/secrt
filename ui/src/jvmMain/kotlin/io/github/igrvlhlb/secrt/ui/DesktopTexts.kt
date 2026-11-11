package io.github.igrvlhlb.secrt.ui

import io.github.igrvlhlb.secrt.ui.resources.Res
import io.github.igrvlhlb.secrt.ui.resources.desktop_already_open
import io.github.igrvlhlb.secrt.ui.resources.desktop_google_denied
import io.github.igrvlhlb.secrt.ui.resources.desktop_google_done
import org.jetbrains.compose.resources.getString

/** Texts the desktop app shows outside Compose, in the user's language. */
object DesktopTexts {
    /** Said when another window of secrt already has the diary open. */
    suspend fun alreadyOpen(): String = getString(Res.string.desktop_already_open)

    /** The browser page after Google sign-in, when access was granted. */
    suspend fun googleSignInDone(): String = getString(Res.string.desktop_google_done)

    /** The browser page after Google sign-in, when access wasn't granted. */
    suspend fun googleSignInDenied(): String = getString(Res.string.desktop_google_denied)
}
