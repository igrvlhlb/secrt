package io.github.igrvlhlb.secrt.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import io.github.igrvlhlb.secrt.session.AppSession
import io.github.igrvlhlb.secrt.session.AutoLockTimeout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Clock

/**
 * Locks the diary after the app has been in the background for the user's [AutoLockTimeout].
 *
 * Two mechanisms, because neither is enough alone: a timer started when the app leaves the
 * foreground closes the database even if the user never comes back, but may fire late (or not
 * at all) while the system freezes the process; so the elapsed time is checked again when the
 * app returns.
 *
 * Keep one instance per process (Android: in the Application). The timer must be cancelled by
 * whichever activity comes back, and a fresh instance per activity would lose it.
 *
 * Pause it while an external screen we launched ourselves (like the file picker) is in front.
 *
 * @param scope outlives activities, so the timer keeps running after the UI goes away.
 */
class AutoLock(
    private val scope: CoroutineScope,
    private val timeout: () -> AutoLockTimeout,
    private val lock: suspend () -> Unit,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    constructor(session: AppSession, scope: CoroutineScope) :
        this(scope, { session.settings.value.autoLock }, { session.lock() })

    // Lifecycle callbacks arrive on the main thread; only beforeLock is also read from the timer.
    private var paused = false
    private var backgroundedAt: Long? = null
    private var timer: Job? = null
    private val beforeLock = MutableStateFlow<List<suspend () -> Unit>>(emptyList())

    fun pause() {
        paused = true
    }

    fun resume() {
        paused = false
    }

    /**
     * Registers work that must finish before the diary closes, like saving the entry being
     * edited. @return a function that unregisters it.
     */
    fun beforeLock(action: suspend () -> Unit): () -> Unit {
        beforeLock.update { it + action }
        return { beforeLock.update { it - action } }
    }

    /** The app went to the background. */
    fun onBackground() {
        if (paused) return
        backgroundedAt = now()
        timer?.cancel()
        val millis = timeout().millis ?: return
        timer = scope.launch {
            delay(millis)
            lockNow()
        }
    }

    /** The app is in front again. */
    fun onForeground() {
        val alreadyLocked = timer?.isCompleted == true
        timer?.cancel()
        timer = null
        val since = backgroundedAt ?: return
        backgroundedAt = null
        if (paused || alreadyLocked) return
        val millis = timeout().millis ?: return
        if (now() - since >= millis) scope.launch { lockNow() }
    }

    private suspend fun lockNow() {
        // A failed save is reported by its owner; the diary locks regardless.
        beforeLock.value.forEach { runCatching { it() } }
        lock()
    }
}

@Composable
internal fun AutoLockEffect(autoLock: AutoLock) {
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { autoLock.onBackground() }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { autoLock.onForeground() }
}

/** The app's [AutoLock], for screens that launch the platform's pickers. */
val LocalAutoLock = staticCompositionLocalOf<AutoLock> { error("No AutoLock provided") }
