package com.racetimer.phone

import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * Arms at most one pending cue dispatch; arming again replaces it (#202).
 *
 * The seam that makes the ViewModel's *scheduled, never polled* claim provable in a unit test: the
 * test injects a recording implementation and drives a whole race through it with the display poll
 * never running, which is the assertion the AC actually makes. Production is [HandlerCueScheduler].
 */
interface CueScheduler {

    /** Run [action] in [delayMs], replacing whatever was armed before. */
    fun armIn(delayMs: Long, action: Runnable)

    /** Disarm the pending dispatch, if any. */
    fun cancel()

    /**
     * The no-op the ViewModel's default parameter names — same contract and same warning as
     * [CueSounder.SILENT]: tests and previews only, never the production wiring. A race run on this
     * scheduler fires cues only when the display poll happens to notice them, which is the exact
     * behaviour #202 exists to remove.
     */
    object NONE : CueScheduler {
        override fun armIn(delayMs: Long, action: Runnable) {}
        override fun cancel() {}
    }
}

/**
 * The production [CueScheduler]: `postAtTime` against the uptime clock on the main looper.
 *
 * The same mechanism the watch's `TimerService.scheduleNextCue` uses, for the same reason: a
 * `Handler` message posted at the cue's own boundary wakes the dispatch when the cue is due rather
 * than up to a poll interval later (#58 measured that poll cost at up to ±212 ms). `postAtTime`
 * rather than `postDelayed` so the deadline is fixed at arm time and does not inherit whatever ran
 * between computing the delay and posting it.
 *
 * The uptime clock stops in deep suspend, which on the watch is why the wake lock is load-bearing
 * (#126). The phone equivalent — a foreground service holding a wake lock — is #203; until it
 * lands, the cue path is honest only while the app is in the foreground with the screen on, which
 * is #202's stated scope.
 */
class HandlerCueScheduler : CueScheduler {

    private val handler = Handler(Looper.getMainLooper())

    private var armed: Runnable? = null

    override fun armIn(delayMs: Long, action: Runnable) {
        cancel()
        armed = action
        handler.postAtTime(action, SystemClock.uptimeMillis() + delayMs)
    }

    override fun cancel() {
        armed?.let(handler::removeCallbacks)
        armed = null
    }
}
