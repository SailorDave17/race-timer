package com.racetimer.phone

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel

/**
 * What the officer decided the console screen should do today (#225).
 *
 * Two independent switches, because **the wants come apart**: shore power wants both, a dying
 * battery under overcast wants neither, and direct sun with a free thumb wants brightness alone.
 * #199 kept them independent at the window; coupling them here would put the coupling back in the
 * only place it could still do harm.
 */
data class DisplayChoice(val keepScreenOn: Boolean, val fullBrightness: Boolean) {
    companion object {
        /**
         * Where the surface opens.
         *
         * Screen-on preselected and full brightness not, so that an officer who taps straight
         * through gets a clock that does not sleep and does not silently pay the panel cost. These
         * two positions are the ratified default and the thing a test has to pin — a tap-through is
         * the commonest path, so its outcome is a product decision rather than an initialiser.
         */
        val INITIAL = DisplayChoice(keepScreenOn = true, fullBrightness = false)
    }
}

/**
 * How long the count-up brightness question stands before it answers itself (#279).
 *
 * **Reasoned, not measured.** The officer at a gun is looking at the water, which is the whole
 * reason the question can be asked there at all — so it has to survive long enough for a glance
 * down and a tap, while every second it stands is a second of the panel cost it exists to stop.
 * Fifteen seconds is long enough to notice and act on a console phone at arm's length, and short
 * enough that a count-up nobody is watching costs a quarter of a minute of full brightness rather
 * than the hour an unbounded one can run to.
 *
 * A count-up ended before this elapses leaves the question *unanswered*, not silently spent: the
 * ask is consumed by an answer, never by having been shown. Backgrounding the app has the same
 * effect — the composition goes and the dwell restarts on return — which is right for the same
 * reason.
 */
internal const val COUNT_UP_PROMPT_DWELL_MS = 15_000L

/**
 * The display properties that apply *right now*, given where the race is (#279).
 *
 * The officer's launch answer is the standing choice and this narrows it in exactly one place: an
 * unbounded count-up, once they have said full brightness may go there. Nothing else moves, and
 * screen-on is never touched — releasing brightness is what was asked about, and the two properties
 * stay independent all the way to the window (#199).
 *
 * The three values of [countUpKeepsBrightness] are three different states and only one of them
 * dims. `null` is *not yet answered* — the question may be on screen this second — and it keeps the
 * officer's brightness, because a question that dimmed the panel while it was still being asked
 * would have answered itself. `true` is a deliberate keep. Only `false` releases.
 *
 * **A pure function of booleans, on purpose.** It is the whole rule, it is exhaustively testable
 * off the JVM, and it takes `countingUp` rather than an engine state so that where the race is
 * stays a question the app answers once, at the edge, rather than a dependency this rule carries.
 * The shared, state-driven table the watch applies is a different instrument's answer and stays
 * the watch's — `PhoneDisplay.kt` carries why the two form factors diverge.
 */
fun displayChoiceInEffect(
    chosen: DisplayChoice,
    countingUp: Boolean,
    countUpKeepsBrightness: Boolean?,
): DisplayChoice =
    if (countingUp && countUpKeepsBrightness == false) {
        chosen.copy(fullBrightness = false)
    } else {
        chosen
    }

/**
 * Holds the choice for the life of the process, and no longer (#225).
 *
 * A `ViewModel` is doing exact double duty here, and both halves are criteria:
 *
 *  - it **survives configuration change**, so a console phone picked up and turned does not ask the
 *    officer again mid-start, and
 *  - it **dies with the process**, which is the whole retention policy. The right answer is a
 *    property of *the day* — this sun, this boat, whether there is a charger aboard — not of the
 *    officer, so a remembered value would be confidently wrong on the next race day. Nothing here
 *    is written to `SharedPreferences` or a `DataStore`, and
 *    `ModuleBoundaryTest#the display choice is written to no persistent store` asserts that by
 *    reading the module's own source rather than trusting this paragraph.
 *
 * [answered] is separate from [choice] on purpose: the initial positions are *a choice already
 * populated*, so "what is selected" cannot tell you "has the officer been through the surface". One
 * flag would conflate a tap-through with never having been asked, and AC 1 turns on exactly that
 * distinction.
 */
class DisplayChoiceViewModel : ViewModel() {

    var choice by mutableStateOf(DisplayChoice.INITIAL)
        private set

    /** False until the officer continues past the surface. Never reset — see the class doc. */
    var answered by mutableStateOf(false)
        private set

    fun setKeepScreenOn(on: Boolean) {
        choice = choice.copy(keepScreenOn = on)
    }

    fun setFullBrightness(on: Boolean) {
        choice = choice.copy(fullBrightness = on)
    }

    fun confirm() {
        answered = true
    }

    /**
     * What the officer said about full brightness during a count-up, or null until they say (#279).
     *
     * Held here rather than in the composition for the same two reasons [choice] is: it survives a
     * console phone being picked up and turned mid-count-up, and it dies with the process, so the
     * next race day starts by asking again. It is deliberately **not** part of [DisplayChoice] —
     * that type is the answer to the launch surface, and every one of its fields is applied to the
     * window unconditionally, which this is not.
     */
    var countUpKeepsBrightness by mutableStateOf<Boolean?>(null)
        private set

    /**
     * Answer the count-up question, once per process.
     *
     * Reached three ways and they are one answer: the officer taps Keep bright, the officer taps
     * Dim, or the dwell in [COUNT_UP_PROMPT_DWELL_MS] elapses with the phone unattended. Silence
     * dims, deliberately — at a gun an unanswered question is the *expected* case rather than the
     * exception, so a default of keeping the panel lit would make the prompt decorative on the one
     * path it was built for.
     */
    fun answerCountUpBrightness(keepBright: Boolean) {
        countUpKeepsBrightness = keepBright
    }
}
