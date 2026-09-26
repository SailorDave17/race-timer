package com.racetimer.shared

// ---------------------------------------------------------------------------
// The time of day on the watch timer screen (#303)
//
// Once the timer is open nothing on the watch showed the time of day: the app drew no clock and
// Wear OS draws none over an app. A sailor waiting for a first warning scheduled at 13:05 had to
// leave the timer to find out what time it was. The clock is Wear OS's own curved `TimeText`, drawn
// at the top rim, in the band above the sequence name. At the rim it cannot be mistaken for the
// countdown (owner decision, 2026-09-25). The two rejected places are on #303.
//
// Its colour is `TIME_OF_DAY_TEXT_ARGB` in `MessageContrast.kt`, beside the backgrounds it is drawn
// straight onto, because rule 1 of `docs/message-surface.md` holds it to all of them.
// ---------------------------------------------------------------------------

/**
 * Whether the time of day shows at the top rim of the watch timer screen (#303).
 *
 * **It gives way to every Tier 3 line**, per rule 6 of `docs/message-surface.md` (one message at a
 * time). The timer screen is one vertically centred column, so a Tier 3 line grows the column
 * upward into the band the clock draws in, and the sequence name moves up with it. The clock would
 * collide with the column at exactly the moment there is a message to read. It returns when the
 * line clears.
 *
 * **And to a Tier 2 blocking notice, for the same reason.** The issue named only Tier 3. The Tier 2
 * panel is taller than the Start button it replaces, so it pushes the column to the top too. With
 * the clock up, *measured on the Wear emulator at the SM-R925U's 450 px and density 340*, the clock
 * and the sequence name had no empty pixel row between them. Hiding it there was the owner's
 * decision on #303. The cost is small: a blocking notice is pre-start only, and it has to be acted
 * on before anything else happens.
 *
 * **Otherwise it shows in every timer state**, which is why there is no state parameter. Hiding it
 * in the final minute was rejected, because it would vanish exactly when someone glances for it.
 * Showing it pre-start only was rejected, because a count-up would then have no clock (owner
 * decisions, 2026-09-25).
 *
 * The arguments are what `TimerScreen` draws a message from, as it receives them: the re-sync
 * prompt, the discard warning, and a notice of either tier.
 *
 * @param discardWarningUp true when `TimerScreen`'s `discardWarning` is non-null.
 * @param startNotice the notice `TimerScreen` was handed: `startNotice` before Start, `armedNotice`
 *        during a running race.
 */
fun showsTimeOfDay(
    showResyncPrompt: Boolean,
    discardWarningUp: Boolean,
    startNotice: StartNotice?,
): Boolean = !showResyncPrompt && !discardWarningUp && startNotice == null
