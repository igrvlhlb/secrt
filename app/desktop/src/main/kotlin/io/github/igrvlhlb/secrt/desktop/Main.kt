package io.github.igrvlhlb.secrt.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import io.github.igrvlhlb.secrt.session.AppSession
import io.github.igrvlhlb.secrt.ui.DesktopTexts
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
import java.awt.Taskbar
import java.awt.desktop.QuitStrategy
import java.awt.desktop.SystemSleepEvent
import java.awt.desktop.SystemSleepListener
import java.awt.desktop.UserSessionEvent
import java.awt.desktop.UserSessionListener
import javax.imageio.ImageIO
import javax.swing.JOptionPane
import kotlin.system.exitProcess

fun main() {
    val dataDirectory = dataDirectory()
    val instanceLock = lockDataDirectory(dataDirectory) ?: run {
        JOptionPane.showMessageDialog(null, runBlocking { DesktopTexts.alreadyOpen() }, "secrt", JOptionPane.INFORMATION_MESSAGE)
        exitProcess(1)
    }
    val session = desktopSession(dataDirectory)
    val lan = desktopLanSync(session, dataDirectory)
    val google = desktopGoogleSignIn()
    val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    integrateWithDesktop(session)
    setTaskbarIcon()

    application {
        var open by remember { mutableStateOf(true) }
        if (open) {
            Window(
                onCloseRequest = { open = false },
                title = "secrt",
                // macOS shows no window icons, and the Dock's comes from the app bundle (or setTaskbarIcon).
                icon = if (isMac) null else painterResource("icon.svg"),
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

private val isMac = System.getProperty("os.name").orEmpty().startsWith("Mac")

/**
 * Sets the Dock (macOS) or taskbar icon when running unpackaged (`./gradlew :app:desktop:run`),
 * where the Dock would otherwise show the Java launcher's icon. The packaged app (jpackage sets
 * `jpackage.app-path`) keeps its bundle icon (`packaging/secrt.icns`): replacing it at runtime
 * swaps the macOS-shaped icon for the flat PNG. Skipped where the OS doesn't allow changing it.
 */
private fun setTaskbarIcon() {
    if (System.getProperty("jpackage.app-path") != null) return
    if (!Taskbar.isTaskbarSupported()) return
    val taskbar = Taskbar.getTaskbar()
    if (!taskbar.isSupported(Taskbar.Feature.ICON_IMAGE)) return
    val icon = ClassLoader.getSystemResourceAsStream("icon.png")?.use { ImageIO.read(it) } ?: return
    taskbar.iconImage = icon
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
