/**
 * Countdown display formatting.
 *
 * Lives in the shared module so the on-watch screen and the Ongoing Activity notification cannot
 * drift apart, and so the rounding rule is unit-testable without a device.
 */
package com.racetimer.shared

/**
 * Format [remainingMs] as `M:SS` for a **countdown**, rounding *up* to the next whole second.
 *
 * A countdown rounds up; a stopwatch rounds down. With 59 980 ms left the sailor still has "1:00"
 * to go, not "0:59" — the display must not claim a second that has not elapsed yet.
 *
 * This is what keeps the cues honest. A cue at offset O fires on the first tick where remaining
 * has reached O (so remaining is O minus at most one tick interval). Flooring, that instant
 * formats as O-1 seconds, and the one-minute horn sounded exactly as the clock flipped to 0:59 —
 * the sailor sees the signal a second late. Rounding up, the horn lands on the tick the clock
 * flips *to* 1:00, which is what the Race Committee flag does.
 *
 * Non-positive input formats as `0:00` (the gun has fired; callers show "GO!" instead).
 */
fun formatCountdown(remainingMs: Long): String {
    val totalSec = if (remainingMs <= 0L) 0L else (remainingMs + 999L) / 1_000L
    val min = totalSec / 60
    val sec = totalSec % 60
    return "%d:%02d".format(min, sec)
}

/**
 * Format [elapsedMs] as `M:SS`, or `H:MM:SS` once the elapsed time reaches an hour, for a race's
 * elapsed time — [TimerState.COUNTING_UP]'s display.
 *
 * A stopwatch rounds *down*, the opposite of [formatCountdown]: at 59 980 ms elapsed, "0:59" have
 * genuinely elapsed and "1:00" has not, so claiming it would be the same premature-second bug
 * [formatCountdown]'s doc describes, just in the other direction. Unlike a start countdown — which
 * this codebase's built-in sequences cap under six minutes — a race can run for hours, so minutes
 * alone would roll over silently past 99; the hour digit is added once it is needed rather than
 * always, so the common case stays as short as [formatCountdown]'s.
 *
 * Non-positive input formats as `0:00`.
 */
fun formatElapsed(elapsedMs: Long): String {
    val totalSec = if (elapsedMs <= 0L) 0L else elapsedMs / 1_000L
    val hours = totalSec / 3_600
    val min = (totalSec % 3_600) / 60
    val sec = totalSec % 60
    return if (hours > 0L) "%d:%02d:%02d".format(hours, min, sec) else "%d:%02d".format(min, sec)
}
