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
