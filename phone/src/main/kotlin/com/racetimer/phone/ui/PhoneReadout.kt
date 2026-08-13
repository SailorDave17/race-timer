package com.racetimer.phone.ui

import com.racetimer.shared.TimerState
import com.racetimer.shared.backgroundArgbFor
import com.racetimer.shared.formatCountdown
import com.racetimer.shared.formatElapsed

/**
 * Everything the countdown screen draws, derived from the engine's state and nothing else.
 *
 * Pure, and separate from the composable on purpose: the one thing worth asserting about this
 * screen is that it *renders engine state rather than doing its own arithmetic* (#197 AC 2), and a
 * value a test can compute is the only form of that claim a test can check. A composable holding
 * the same `when` would need a UI harness to ask the same question, and would then be measuring
 * Compose.
 *
 * @param text          What the big readout says — the countdown, or `GO!` at the gun.
 * @param backgroundArgb The screen colour, straight from `shared/MessageContrast.kt`.
 */
data class PhoneReadout(
    val text: String,
    val backgroundArgb: Long,
) {
    companion object {
        /** What the readout says the instant the gun fires. The watch says the same word. */
        const val GUN_LABEL = "GO!"

        /**
         * Build the readout for an engine state.
         *
         * [remainingMs] and [elapsedMs] are read off the engine, never recomputed here — which is
         * the whole point of the split described above.
         *
         * The colour is [backgroundArgbFor] and is not restated: the thresholds it applies (one
         * minute, final ten) are the watch's, tested there, and a second copy of them would be the
         * duplicated-constant defect this repo has recorded more often than any other.
         */
        fun of(state: TimerState, remainingMs: Long, elapsedMs: Long): PhoneReadout = PhoneReadout(
            text = when (state) {
                TimerState.FINISHED -> GUN_LABEL
                // The two race-manager states. No sequence this module can currently select reaches
                // them — #206 brings count-up and End Race to the phone, along with the screen
                // furniture they need — but the engine has them, and a readout that answered a
                // state by omission would draw a countdown over a running race.
                TimerState.COUNTING_UP, TimerState.RACE_ENDED -> formatElapsed(elapsedMs)
                else -> formatCountdown(remainingMs)
            },
            backgroundArgb = backgroundArgbFor(remainingMs, state),
        )
    }
}

/**
 * Round [rawMs] up to the whole second the screen actually shows.
 *
 * Everything downstream is second-granular — [formatCountdown] rounds up, and the colour thresholds
 * sit on whole seconds — so holding raw milliseconds would change the state on every tick and
 * recompose the screen to draw a frame identical to the last one. Rounding the same way
 * [formatCountdown] does keeps every consumer's output byte-identical, since a threshold on a whole
 * second is crossed by the rounded value at exactly the same instant as by the raw one.
 *
 * The watch does this in `MainActivity`; the phone does it here so it is covered by a unit test.
 */
fun displayedRemainingMs(rawMs: Long): Long =
    if (rawMs <= 0L) 0L else ((rawMs + 999L) / 1_000L) * 1_000L

/**
 * Round [rawMs] *down* to the whole second an elapsed-time readout shows — the same recomposition
 * argument as [displayedRemainingMs], floored rather than ceiled because elapsed race time is a
 * stopwatch reading and claiming a second that has not passed is the same premature-second bug in
 * the other direction.
 */
fun displayedElapsedMs(rawMs: Long): Long =
    if (rawMs <= 0L) 0L else (rawMs / 1_000L) * 1_000L
