package io.github.igrvlhlb.secrt.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import io.github.igrvlhlb.secrt.session.AppSession
import io.github.igrvlhlb.secrt.ui.SecrtApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.awt.Desktop
import java.awt.Dimension
import java.awt.desktop.QuitStrategy
import java.awt.desktop.SystemSleepEvent
import java.awt.desktop.SystemSleepListener
import java.awt.desktop.UserSessionEvent
import java.awt.desktop.UserSessionListener
import javax.swing.JOptionPane
import kotlin.system.exitProcess

fun main() {
    val dataDirectory = dataDirectory()
    val instanceLock = lockDataDirectory(dataDirectory) ?: run {
        JOptionPane.showMessageDialog(null, "secrt is already open.", "secrt", JOptionPane.INFORMATION_MESSAGE)
        exitProcess(1)
    }
    val session = desktopSession(dataDirectory)
    val lan = desktopLanSync(session, dataDirectory)
    val google = desktopGoogleSignIn()
    val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    integrateWithDesktop(session)

    application {
        var open by remember { mutableStateOf(true) }
        if (open) {
            Window(
                onCloseRequest = { open = false },
                title = "secrt",
                icon = rememberVectorPainter(AppIcon),
                state = rememberWindowState(position = WindowPosition(Alignment.Center), width = 1000.dp, height = 720.dp),
            ) {
                // Narrower than this, the phone layout starts to clip.
                LaunchedEffect(Unit) { window.minimumSize = Dimension(400, 560) }
                SecrtApp(session, backgroundScope, lan = lan, google = google)
            }
        } else {
            LaunchedEffect(Unit) {
                // Closing the window stopped the editor, which queued its final save there.
                backgroundScope.coroutineContext.job.children.toList().joinAll()
                session.lock()
                instanceLock.close()
                exitApplication()
            }
        }
    }
}

/**
 * Hooks the session into OS events: Cmd+Q closes the window like the close button does (so the
 * entry being edited is saved), and the diary locks when the computer goes to sleep or the user
 * switches accounts.
 *
 * The in-app [io.github.igrvlhlb.secrt.ui.AutoLock] already covers minimizing: Compose Desktop
 * stops the window's lifecycle while it's minimized, like Android does for a backgrounded app.
 * Merely switching to another app only pauses it, so it doesn't lock.
 */
private fun integrateWithDesktop(session: AppSession) {
    if (!Desktop.isDesktopSupported()) return
    val desktop = Desktop.getDesktop()
    if (desktop.isSupported(Desktop.Action.APP_QUIT_STRATEGY)) {
        desktop.setQuitStrategy(QuitStrategy.CLOSE_ALL_WINDOWS)
    }
    if (desktop.isSupported(Desktop.Action.APP_EVENT_SYSTEM_SLEEP) || desktop.isSupported(Desktop.Action.APP_EVENT_USER_SESSION)) {
        // Blocks the event thread briefly, so the lock is done before the machine sleeps.
        val lockNow = { runBlocking { withTimeoutOrNull(2_000) { session.lock() } } }
        desktop.addAppEventListener(
            object : SystemSleepListener, UserSessionListener {
                override fun systemAboutToSleep(e: SystemSleepEvent) {
                    lockNow()
                }

                override fun systemAwoke(e: SystemSleepEvent) = Unit

                override fun userSessionDeactivated(e: UserSessionEvent) {
                    lockNow()
                }

                override fun userSessionActivated(e: UserSessionEvent) = Unit
            },
        )
    }
}
