package io.github.igrvlhlb.secrt.ui

import io.github.igrvlhlb.secrt.session.AutoLockTimeout
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class AutoLockTest {

    private var timeout = AutoLockTimeout.OneMinute
    private val events = mutableListOf<String>()

    private fun TestScope.autoLock() = AutoLock(
        scope = backgroundScope,
        timeout = { timeout },
        lock = { events += "lock" },
        now = { testScheduler.currentTime },
    )

    @Test
    fun locksWhenTheTimeoutPassesInTheBackground() = runTest {
        val autoLock = autoLock()
        autoLock.onBackground()
        advanceTimeBy(59_000)
        assertEquals(emptyList(), events)
        advanceTimeBy(2_000)
        assertEquals(listOf("lock"), events)
    }

    @Test
    fun comingBackInTimeCancelsTheTimer() = runTest {
        val autoLock = autoLock()
        autoLock.onBackground()
        advanceTimeBy(30_000)
        autoLock.onForeground()
        advanceTimeBy(10 * 60_000)
        assertEquals(emptyList(), events)
    }

    @Test
    fun checksElapsedTimeOnReturnInCaseTheTimerWasLate() = runTest {
        timeout = AutoLockTimeout.FiveMinutes
        // The wall clock moves while virtual time doesn't: the timer stands for a frozen process.
        var wallClock = 0L
        val autoLock = AutoLock(backgroundScope, { timeout }, { events += "lock" }, now = { wallClock })
        autoLock.onForeground() // never went to the background: nothing to do
        autoLock.onBackground()
        wallClock = 5 * 60_000
        autoLock.onForeground()
        runCurrent()
        assertEquals(listOf("lock"), events)
    }

    @Test
    fun immediatelyLocksAfterPendingSaves() = runTest {
        timeout = AutoLockTimeout.Immediately
        val autoLock = autoLock()
        val unregister = autoLock.beforeLock { events += "save" }
        autoLock.onBackground()
        runCurrent()
        assertEquals(listOf("save", "lock"), events)

        unregister()
        autoLock.onForeground()
        autoLock.onBackground()
        runCurrent()
        assertEquals(listOf("save", "lock", "lock"), events)
    }

    @Test
    fun onAppRestartNeverLocksWhileRunning() = runTest {
        timeout = AutoLockTimeout.OnAppRestart
        val autoLock = autoLock()
        autoLock.onBackground()
        advanceTimeBy(24 * 60 * 60_000L)
        autoLock.onForeground()
        runCurrent()
        assertEquals(emptyList(), events)
    }

    @Test
    fun pausedWhileOurOwnPickerIsOpen() = runTest {
        val autoLock = autoLock()
        autoLock.pause()
        autoLock.onBackground()
        advanceTimeBy(10 * 60_000)
        autoLock.onForeground()
        autoLock.resume()
        runCurrent()
        assertEquals(emptyList(), events)
    }
}
