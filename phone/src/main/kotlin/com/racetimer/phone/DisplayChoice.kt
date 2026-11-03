package com.racetimer.phone

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.racetimer.shared.TimerState

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
 * The value sits inside a band `CountUpBrightnessRuleTest` asserts rather than being argued for
 * here in words: a number restated in prose is this workspace's most recurring documentation
 * defect, and a dwell moved inside the band would leave any sentence naming it false while every
 * test stayed green.
 *
 * A count-up ended before this elapses leaves the question *unanswered*, not silently spent: the
 * ask is consumed by an answer, never by having been shown. The same rule is why
 * [countUpBrightnessPromptShows] requires the timer screen — silence is only an answer if the
 * question was askable. Backgrounding cancels the dwell as well; `MainActivity` carries the
 * mechanism, which is **not** the composition being disposed.
 *
 * *(This paragraph named the live route to a not-askable count-up until #281: "an activity
 * recreation mid-count-up lands on the picker with the engine still counting up". #281 closed that
 * route — a bind onto a live race now opens the timer screen — so the screen clause is a defence
 * rather than a description of something reachable. It is kept, and [countUpBrightnessPromptShows]
 * is where it is proven, because the alternative was a clause whose only coverage was an
 * arrangement the product can no longer produce.)*
 */
internal const val COUNT_UP_PROMPT_DWELL_MS = 15_000L

/**
 * Whether the count-up brightness question is on screen this instant (#279, extracted by #281).
 *
 * Inline in `RaceTimerApp` until #281. It moved here for the reason [displayChoiceInEffect] is
 * here: it is a whole rule, made of booleans, exhaustively testable off the JVM — and #281 removed
 * the only *reachable* arrangement that could exercise its [onTimerScreen] clause through the UI,
 * so a clause with real work to do would otherwise have been left with no way to fail.
 *
 * Each conjunct, and what drops out if it goes:
 *
 *  - [answered] — nothing is asked before the officer has been through the launch surface at all.
 *  - [onTimerScreen] — **silence is only an answer if the question was askable.** The prompt renders
 *    only inside `TimerScreen`; a dwell armed without this would spend the one question of the
 *    session on a screen that never showed it. Since #281 a live race opens the timer screen, so
 *    the states this excludes are transient (the frames before the service binding lands) rather
 *    than a screen an officer can sit on — a narrower job than in #279, and still a real one.
 *  - [countingUp] — the one phase whose release is put to the officer. The other unbounded phases
 *    are settled without a question (#284): nothing-timed releases outright, and the race-ended
 *    summary follows this answer, so an officer meets one display question per race, not two.
 *  - [fullBrightnessChosen] — an officer who declined brightness has nothing to release, so asking
 *    would be noise on the one screen that must stay legible.
 *  - [countUpKeepsBrightness] `== null` — asked once. `null` is *not yet answered*; a `true` or a
 *    `false` both mean the question is spent for this process.
 */
fun countUpBrightnessPromptShows(
    answered: Boolean,
    onTimerScreen: Boolean,
    countingUp: Boolean,
    fullBrightnessChosen: Boolean,
    countUpKeepsBrightness: Boolean?,
): Boolean =
    answered &&
        onTimerScreen &&
        countingUp &&
        fullBrightnessChosen &&
        countUpKeepsBrightness == null

/**
 * Where the race is, as far as the panel is concerned (#284).
 *
 * Four answers the rule acts on, and a fifth for the frames in which nobody knows yet. It is the
 * app's own projection of the engine rather than the engine's state, so [displayChoiceInEffect]
 * stays a rule about the officer's choice with nothing about the race in it but this — and
 * [panelPhaseOf] is the one place the projection is made.
 */
enum class PanelPhase {
    /**
     * A countdown, or the gun's own linger: it ends on its own, and it is what the officer asked
     * for the panel *for*. The choice is honoured exactly.
     */
    TIMED,

    /** The unbounded count-up after a race-manager gun, governed by the question at the gun (#279). */
    COUNTING_UP,

    /** That count-up's frozen final time, held until Done — governed by the same answer (#284). */
    RACE_ENDED,

    /**
     * Nothing is being timed: the picker, the pre-start screen, and the GO! screen once its linger
     * is over. Nothing ends on its own here, and nothing on screen is worth the panel (#284).
     */
    NOTHING_TIMED,

    /**
     * The service binding has not landed, so where the race is cannot be read. Honoured exactly,
     * because a countdown returned to from the background passes through here for a frame or two —
     * releasing would flash it dim on the way back, and it is the countdown the choice was for.
     */
    NOT_YET_KNOWN,
}

/**
 * Project the engine's [state] onto the panel's phase (#284), or [PanelPhase.NOT_YET_KNOWN] when
 * [state] is null because the binding has not landed.
 *
 * [gunLingerOver] matters only for `FINISHED`: the GO! screen stays bright for the gun's linger,
 * the moment the whole sequence led up to, and only then counts as nothing timed. The phone holds
 * that screen until the officer taps Start or Back, so without a bound it would burn the panel for
 * as long as nobody looks.
 *
 * Exhaustive with no `else`, so a new engine state fails the build here until someone decides what
 * the panel does in it. `PAUSED` is honoured as a countdown, **decided rather than forgotten**: no
 * control on the phone pauses, so it is unreachable, and a story that wires one decides whether a
 * paused countdown is something the officer is reading.
 */
fun panelPhaseOf(state: TimerState?, gunLingerOver: Boolean): PanelPhase = when (state) {
    null -> PanelPhase.NOT_YET_KNOWN
    TimerState.RUNNING,
    TimerState.PAUSED -> PanelPhase.TIMED
    TimerState.FINISHED -> if (gunLingerOver) PanelPhase.NOTHING_TIMED else PanelPhase.TIMED
    TimerState.COUNTING_UP -> PanelPhase.COUNTING_UP
    TimerState.RACE_ENDED -> PanelPhase.RACE_ENDED
    TimerState.IDLE -> PanelPhase.NOTHING_TIMED
}

/**
 * The display properties that apply *right now*, given where the race is (#279, #284).
 *
 * The officer's launch answer is the standing choice, and full brightness is what it narrows —
 * screen-on is never touched, so the two properties stay independent all the way to the window
 * (#199). The choice is read as *the panel for the race*: honoured wherever something is being
 * timed, and released where nothing is or where the officer has said the race's unbounded tail may
 * go without it.
 *
 *  - [PanelPhase.TIMED] and [PanelPhase.NOT_YET_KNOWN] honour it.
 *  - [PanelPhase.NOTHING_TIMED] releases it, with no question: there is no clock on screen to read.
 *  - [PanelPhase.COUNTING_UP] releases only on `false`. `null` is *not yet answered* — the question
 *    may be on screen this second — and a question that dimmed the panel while it was still being
 *    asked would have answered itself (#279).
 *  - [PanelPhase.RACE_ENDED] keeps it only on `true`. The summary is the count-up's own tail, so it
 *    follows the answer given at that count-up's gun rather than asking a second question — and
 *    **silence releases here**, including a count-up ended before the dwell answered for it. The
 *    question is gone from the screen by then, so `null` can no longer mean *still being asked*,
 *    and keeping on it would leave the one unbounded hole in the rule.
 *
 * Nothing here remembers a previous phase, so leaving a phase leaves its narrowing behind with it.
 *
 * **A pure function, on purpose.** It is the whole rule and it is exhaustively testable off the
 * JVM. The shared, state-driven table the watch applies is a different instrument's answer and stays
 * the watch's — `PhoneDisplay.kt` carries why the two form factors diverge.
 */
fun displayChoiceInEffect(
    chosen: DisplayChoice,
    phase: PanelPhase,
    countUpKeepsBrightness: Boolean?,
): DisplayChoice {
    val releases = when (phase) {
        PanelPhase.TIMED,
        PanelPhase.NOT_YET_KNOWN -> false
        PanelPhase.NOTHING_TIMED -> true
        PanelPhase.COUNTING_UP -> countUpKeepsBrightness == false
        PanelPhase.RACE_ENDED -> countUpKeepsBrightness != true
    }
    return if (releases) chosen.copy(fullBrightness = false) else chosen
}

/**
 * Holds the choice for the life of the process, and no longer (#225).
 *
 * Two halves, and both are criteria:
 *
 *  - it **outlives the activity**, so a console phone picked up and turned — or an activity the
 *    system destroys and recreates — does not ask the officer again mid-start, and
 *  - it **dies with the process**, which is the whole retention policy. The right answer is a
 *    property of *the day* — this sun, this boat, whether there is a charger aboard — not of the
 *    officer, so a remembered value would be confidently wrong on the next race day. Nothing here
 *    is written to `SharedPreferences` or a `DataStore`, and
 *    `ModuleBoundaryTest#the display choice is written to no persistent store` asserts that by
 *    reading the module's own source rather than trusting this paragraph.
 *
 * **Which lifetime this actually gets is decided by the store it is resolved from, not by this
 * class** — and that is what #281 found wrong. The first half said *survives configuration change*
 * and credited "a `ViewModel`", which is true of a rotation and false of a destroy-and-recreate:
 * an activity-scoped `viewModel()` is cleared when the activity finishes, so #281 measured "Screen
 * for today" re-asked over a race still running in a live process. `MainActivity` now resolves this
 * from [RaceTimerPhoneApplication]'s process-scoped store, which is what makes the second bullet
 * true for the first time. A `viewModel()` default elsewhere — the test seam in `RaceTimerApp` —
 * still gets a per-owner instance, which is what keeps those tests isolated.
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
