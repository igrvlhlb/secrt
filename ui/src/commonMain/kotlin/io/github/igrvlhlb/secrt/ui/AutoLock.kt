package io.github.igrvlhlb.secrt.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import io.github.igrvlhlb.secrt.session.AppSession
import kotlinx.coroutines.launch
import kotlin.time.Clock

/**
 * Locks the diary when the app comes back after being in the background for longer than
 * [timeoutMillis]. Pause it while an external screen we launched ourselves (like the file
 * picker) is in front.
 */
class AutoLock(val timeoutMillis: Long = 60_000) {
    internal var paused = false
    internal var backgroundedAt: Long? = null

    fun pause() {
        paused = true
    }

    fun resume() {
        paused = false
    }
}

@Composable
internal fun AutoLockEffect(session: AppSession, autoLock: AutoLock) {
    val scope = rememberCoroutineScope()
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        autoLock.backgroundedAt = Clock.System.now().toEpochMilliseconds()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        val since = autoLock.backgroundedAt
        autoLock.backgroundedAt = null
        if (since != null && !autoLock.paused && Clock.System.now().toEpochMilliseconds() - since > autoLock.timeoutMillis) {
            scope.launch { session.lock() }
        }
    }
}

@Composable
internal fun rememberAutoLock() = remember { AutoLock() }
