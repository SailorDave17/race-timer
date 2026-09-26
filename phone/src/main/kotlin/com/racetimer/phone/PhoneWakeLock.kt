package com.racetimer.phone

/**
 * How long the race's `PARTIAL_WAKE_LOCK` is asked to hold, from what is left to run (#203).
 *
 * Pure arithmetic in its own file for one reason: the watch shipped this inline and the inline copy
 * carried #126 — the lock was sized once at Start, `ACTION_SYNC` re-anchored the gun up to
 * `LATE_TAP_WINDOW_MS` later without re-sizing, and enough round-up syncs spent the whole margin,
 * after which a timed lock expires *silently* (no callback) and the CPU is free to suspend with
 * cues pending. The phone's
 * criterion is that the sizing is unit-tested and re-computed on every event that can move the gun,
 * and an inline expression inside a `Service` can be neither.
 *
 * The margin covers the gun cue itself (3 s sustained) plus the teardown linger that follows it,
 * with room over — the same 30 s the watch settled on. Too small starves the gun; too large only
 * holds the CPU seconds longer on races that already held it for minutes.
 */
object PhoneWakeLock {

    const val MARGIN_MS = 30_000L

    /**
     * Timeout for a lock (re-)acquired now, with [remainingMs] of race left.
     *
     * Clamps a negative remaining to zero rather than propagating it: the engine reports negative
     * remaining once the gun is due, and a margin shortened by however late the caller read the
     * clock would be the same failure #126 measured, arrived at from the other side.
     */
    fun timeoutMs(remainingMs: Long): Long = remainingMs.coerceAtLeast(0L) + MARGIN_MS
}
