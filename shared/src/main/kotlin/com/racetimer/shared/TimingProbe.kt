package com.racetimer.shared

/**
 * The two numbers that answer #126 — *did a cue land on its boundary, and did the watch sleep?*
 *
 * Both are arithmetic over values the caller already holds, and both are here rather than at their
 * one call site in `TimerService` for the reason [CueTiming.resetAtMs] is: a timing rule written out
 * inline is a rule with no test behind it. The last rule that lived at its call site was wrong there
 * for two weeks and reported success the whole time (#98). (`wear/` had no test source set at all
 * until #160; the one it has now is scoped away from the cue path, so a timing rule left inline
 * there is still a rule with nothing behind it.)
 *
 * These are *measurement*, not behaviour. Nothing in the countdown reads them; they exist so that a
 * claim about screen-off timing can be checked against logs instead of asserted. That distinction is
 * the whole of #126's third criterion — "from logs rather than from a build tool's summary".
 */
object TimingProbe {

    /**
     * How late a cue actually fired against the boundary the sequence put it on, in milliseconds.
     *
     * Positive is late, negative is early, zero is exact. **This is not `ToneManager`'s `cue
     * lateMs=`**, and confusing the two is the trap this function exists to close: that one measures
     * the *tone thread* waking against the moment the cue was already dispatched, so it is blind by
     * construction to the thing #126 asks about. A cue deferred four seconds by a sleeping CPU can
     * still report `lateMs=1` there, because the clock it measures against only starts once the
     * dispatch it is supposed to be auditing has happened. Reading it for a doze question yields a
     * confidently wrong answer with no outward sign of being wrong.
     *
     * The identity is worth stating because it is not obvious and the sign is easy to invert.
     * [TimerEngine] anchors the gun at `gunTimeMs` on the monotonic clock and fires a cue when
     * `now >= gunTimeMs - offsetMs`, while `remainingMs` reads `gunTimeMs - now`. Substituting:
     *
     * ```
     * error = now - (gunTimeMs - offsetMs)
     *       = offsetMs - (gunTimeMs - now)
     *       = offsetMs - remainingMs
     * ```
     *
     * So `gunTimeMs` cancels and the caller needs neither the anchor nor a clock of its own — which
     * matters, because the anchor is private to the engine and a caller that reconstructed it would
     * have a second copy of the thing to keep in step.
     *
     * Read `remainingMs` *inside* the cue callback, not before it. The engine recomputes it from a
     * fresh monotonic read on every access, so sampling it there measures dispatch at the instant
     * dispatch happened; sampling it earlier measures a moment that is not the one being reported.
     */
    fun dispatchErrorMs(cueOffsetMs: Long, remainingMs: Long): Long = cueOffsetMs - remainingMs

    /**
     * How long the device has spent in deep sleep, given one reading of each of Android's two clocks.
     *
     * `SystemClock.elapsedRealtime()` counts through suspend and `SystemClock.uptimeMillis()` does
     * not, so their difference *is* accumulated suspend time since boot. That makes it the one
     * doze signal in this app that cannot be faked by anything the app itself does: a wake lock, a
     * foreground service and an ongoing notification all change whether the device suspends, and none
     * of them change what these two clocks report about whether it did.
     *
     * Pair with [deepSleepSinceMs] rather than reading it raw — the absolute value is dominated by
     * however long the watch had been off the wrist before the race, which says nothing.
     */
    fun sleepDivergenceMs(elapsedRealtimeMs: Long, uptimeMs: Long): Long = elapsedRealtimeMs - uptimeMs

    /**
     * Deep sleep accumulated since a baseline [sleepDivergenceMs] captured earlier, in milliseconds.
     *
     * Zero means the CPU never suspended over the interval, which is the result the wake lock is
     * supposed to produce for the whole of a countdown. Anything above zero is time the handler queue
     * was not running, and — because [TimerEngine]'s countdown is anchored to the monotonic clock
     * while `TimerService` schedules cues on the *uptime* clock — it is also the amount by which a
     * pending cue's scheduled wake-up slipped. The two clocks are the same two this subtracts.
     *
     * Deliberately not clamped at zero. The value cannot legitimately go negative, so a negative
     * reading means the baseline was taken on a different boot or the arguments were swapped, and
     * surfacing that beats quietly reporting a well-behaved zero.
     */
    fun deepSleepSinceMs(
        elapsedRealtimeMs: Long,
        uptimeMs: Long,
        baselineDivergenceMs: Long,
    ): Long = sleepDivergenceMs(elapsedRealtimeMs, uptimeMs) - baselineDivergenceMs
}
