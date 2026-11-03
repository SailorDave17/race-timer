package com.racetimer.phone

import com.racetimer.shared.TimerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The whole display rule, exhaustively (#279 AC 4, #284 ACs 1–5).
 *
 * `displayChoiceInEffect` is a pure function of three inputs with 2 x 2 x 5 x 3 = 60 combinations,
 * so the table below is not a sample — it is the function. That is the argument for the rule being
 * a free function at all: the behaviour the officer sees is a Compose test with a clock in it, and
 * the *rule* underneath it costs nothing to state completely, on the JVM, with no Robolectric and
 * no frame pump. `panelPhaseOf`, which feeds it, is tabled the same way below.
 *
 * Three properties are asserted here that no screen test could isolate:
 *
 *  - **screen-on is never touched.** #199 made the two window properties independent and both
 *    #279 and #284 move exactly one of them, so every row carries both values and the keep-screen-on
 *    column is a copy of its input in all 60.
 *  - **`null` means two different things in two phases.** In a count-up it keeps — the question is
 *    on screen at that moment, and a rule that dimmed while still asking would have answered itself.
 *    In the race-ended summary it releases — the question has left the screen, and keeping on it
 *    would leave an unbounded hole (#284). Only rows with `fullBrightness` true can show either,
 *    because on the others `copy(fullBrightness = false)` is identity.
 *  - **nothing depends on a previous phase**, which is AC 5: a narrowing cannot outlive the phase
 *    that caused it when the rule has no memory to carry it in. The table is that claim, row by row.
 */
class CountUpBrightnessRuleTest {

    @Test
    fun `brightness is released where nothing is timed or the race's tail was let go, and nowhere else`() {
        val actual = mutableListOf<String>()
        val expected = mutableListOf<String>()

        for (keepScreenOn in listOf(false, true)) {
            for (fullBrightness in listOf(false, true)) {
                for (phase in PanelPhase.values()) {
                    for (answer in listOf(null, true, false)) {
                        val chosen = DisplayChoice(keepScreenOn, fullBrightness)
                        val inEffect = displayChoiceInEffect(chosen, phase, answer)

                        // Written out rather than derived from the function under test: an expected
                        // value computed by re-calling the subject is the shape that passes whatever
                        // the subject does (cairn `a-mutation-cannot-see-what-no-test-reaches`).
                        val released = when (phase.name) {
                            "NOTHING_TIMED" -> true
                            "COUNTING_UP" -> answer == false
                            "RACE_ENDED" -> answer != true
                            else -> false
                        }
                        val case = "keepScreenOn=$keepScreenOn fullBrightness=$fullBrightness " +
                            "phase=$phase answer=$answer"
                        expected += "$case -> keepScreenOn=$keepScreenOn " +
                            "fullBrightness=${fullBrightness && !released}"
                        actual += "$case -> keepScreenOn=${inEffect.keepScreenOn} " +
                            "fullBrightness=${inEffect.fullBrightness}"
                    }
                }
            }
        }

        // The count is asserted first: a loop that stopped iterating would agree with itself
        // perfectly and prove nothing (cairn `an-absent-result-reads-as-a-clean-one`). It also
        // pins the phase count at five, so a sixth phase has to be decided here, not inherited.
        assertEquals("every combination of the rule's three inputs", 60, actual.size)
        assertEquals(expected, actual)
    }

    @Test
    fun `each phase does what was decided for it, on a choice that asked for the panel`() {
        // The positive statement of the same rule, so the table above cannot pass by returning its
        // input in all 60 rows — and one line per decision, so a reader deleting a branch has the
        // sentence that says what breaks.
        val chosen = DisplayChoice(keepScreenOn = true, fullBrightness = true)
        val released = DisplayChoice(keepScreenOn = true, fullBrightness = false)

        assertEquals(
            "a countdown, or the gun's linger, gets what the officer chose whatever the count-up answer",
            chosen,
            displayChoiceInEffect(chosen, PanelPhase.TIMED, countUpKeepsBrightness = false),
        )
        assertEquals(
            "before the binding lands the race is unknown, and a countdown returned to from the " +
                "background must not flash dim on the way back",
            chosen,
            displayChoiceInEffect(chosen, PanelPhase.NOT_YET_KNOWN, countUpKeepsBrightness = false),
        )
        assertEquals(
            "nothing timed releases brightness without asking, even after a Keep bright (#284)",
            released,
            displayChoiceInEffect(chosen, PanelPhase.NOTHING_TIMED, countUpKeepsBrightness = true),
        )
        assertEquals(
            "a released count-up drops brightness and keeps screen-on",
            released,
            displayChoiceInEffect(chosen, PanelPhase.COUNTING_UP, countUpKeepsBrightness = false),
        )
        assertEquals(
            "an unanswered count-up keeps what the officer chose — the question is still on screen",
            chosen,
            displayChoiceInEffect(chosen, PanelPhase.COUNTING_UP, countUpKeepsBrightness = null),
        )
        assertEquals(
            "a kept count-up keeps what the officer chose",
            chosen,
            displayChoiceInEffect(chosen, PanelPhase.COUNTING_UP, countUpKeepsBrightness = true),
        )
        assertEquals(
            "a kept count-up's final time stays bright — it follows the gun's answer (#284)",
            chosen,
            displayChoiceInEffect(chosen, PanelPhase.RACE_ENDED, countUpKeepsBrightness = true),
        )
        assertEquals(
            "a dimmed count-up's final time stays dimmed (#284)",
            released,
            displayChoiceInEffect(chosen, PanelPhase.RACE_ENDED, countUpKeepsBrightness = false),
        )
        assertEquals(
            "a count-up ended before anyone answered leaves a final time that releases — silence " +
                "dims, and the question is no longer on screen to be answered (#284)",
            released,
            displayChoiceInEffect(chosen, PanelPhase.RACE_ENDED, countUpKeepsBrightness = null),
        )
    }

    @Test
    fun `every engine state maps to the phase decided for it`() {
        // 7 x 2 = 14 rows: six engine states and the unbound null, each with the linger over and
        // not. Written out by name, so the expected column is a statement rather than a re-run of
        // the `when` under test.
        val expectedByState = mapOf(
            null to listOf("NOT_YET_KNOWN", "NOT_YET_KNOWN"),
            TimerState.IDLE to listOf("NOTHING_TIMED", "NOTHING_TIMED"),
            TimerState.RUNNING to listOf("TIMED", "TIMED"),
            TimerState.PAUSED to listOf("TIMED", "TIMED"),
            TimerState.FINISHED to listOf("TIMED", "NOTHING_TIMED"),
            TimerState.COUNTING_UP to listOf("COUNTING_UP", "COUNTING_UP"),
            TimerState.RACE_ENDED to listOf("RACE_ENDED", "RACE_ENDED"),
        )
        assertEquals(
            "every engine state is in the expected table — a new one must be decided here",
            TimerState.values().toSet() + setOf<TimerState?>(null),
            expectedByState.keys,
        )

        val actual = mutableListOf<String>()
        val expected = mutableListOf<String>()
        for ((state, phases) in expectedByState) {
            for ((index, lingerOver) in listOf(false, true).withIndex()) {
                val case = "state=$state gunLingerOver=$lingerOver"
                expected += "$case -> ${phases[index]}"
                actual += "$case -> ${panelPhaseOf(state, lingerOver).name}"
            }
        }
        assertEquals("every combination of the projection's two inputs", 14, actual.size)
        assertEquals(expected, actual)
    }

    @Test
    fun `the dwell stays inside the band the product argues for`() {
        // Added after a mutation pass measured the hole (#279). Widening the dwell from 15 s to
        // 10 min reddened **nothing**: the screen test waits `COUNT_UP_PROMPT_DWELL_MS + 5 s`
        // before asserting the panel dimmed, so its observation window moves with the constant and
        // the assertion is true at every value — `prove-tests` shape 4, an expected value derived
        // from the code under test. Deriving the wait is right for the *negative* assertions there
        // (they have to outlast the window or they prove nothing), and it leaves the value itself
        // unpinned. This is what pins it.
        //
        // The edges are product statements, not the current value spelled twice. Below about five
        // seconds the officer glancing up from the water has no chance to answer, and the question
        // becomes decoration on the path it was written for. Above about a minute an unattended
        // count-up burns the panel for a material part of a race, which is the cost the whole
        // story exists to bound. A change that leaves the band is a product decision and should
        // have to move this line to say so.
        //
        // The current value is deliberately not written here, or anywhere else in prose: it would
        // be a second copy that a legitimate in-band change silently falsifies, which is this
        // repo's most recurring documentation defect. The band is the claim; the constant is the
        // value; the assertion below is the only place they meet.
        assertTrue(
            "the count-up question's dwell is $COUNT_UP_PROMPT_DWELL_MS ms, outside the 5 s-60 s " +
                "band: too short to answer from the water, or long enough that an unattended " +
                "count-up costs what this story exists to save",
            COUNT_UP_PROMPT_DWELL_MS in 5_000L..60_000L,
        )
    }

    @Test
    fun `the question shows on exactly one combination of the five conditions`() {
        // `countUpBrightnessPromptShows` was inline in `RaceTimerApp` until #281, and #281 is why it
        // is out here: closing the navigation gap removed the only arrangement that could exercise
        // its `onTimerScreen` clause through the UI. #279's screen test reached that clause by
        // starting a count-up and letting the fresh composition open on the **picker** — which is
        // precisely the defect #281 fixes, so after it the clause kept a real job (the frames before
        // the service binding lands) and no way to fail. Extracting it gives that job a subject a
        // test can reach, which is the alternative to deleting a clause on the strength of a green
        // suite.
        //
        // 2 x 2 x 2 x 2 x 3 = 48, so this is the function rather than a sample of it.
        val actual = mutableListOf<String>()
        val expected = mutableListOf<String>()

        for (answered in listOf(false, true)) {
            for (onTimerScreen in listOf(false, true)) {
                for (countingUp in listOf(false, true)) {
                    for (fullBrightness in listOf(false, true)) {
                        for (answer in listOf(null, true, false)) {
                            val shows = countUpBrightnessPromptShows(
                                answered = answered,
                                onTimerScreen = onTimerScreen,
                                countingUp = countingUp,
                                fullBrightnessChosen = fullBrightness,
                                countUpKeepsBrightness = answer,
                            )
                            // Written out rather than derived from the subject: an expected value
                            // computed by re-calling the function under test agrees with whatever
                            // it does (cairn `a-mutation-cannot-see-what-no-test-reaches`).
                            val shouldShow =
                                answered && onTimerScreen && countingUp &&
                                    fullBrightness && answer == null
                            val case = "answered=$answered onTimerScreen=$onTimerScreen " +
                                "countingUp=$countingUp fullBrightness=$fullBrightness answer=$answer"
                            expected += "$case -> $shouldShow"
                            actual += "$case -> $shows"
                        }
                    }
                }
            }
        }

        // Asserted first: a loop that stopped iterating agrees with itself perfectly and proves
        // nothing (cairn `an-absent-result-reads-as-a-clean-one`).
        assertEquals("every combination of the predicate's five inputs", 48, actual.size)
        assertEquals(expected, actual)

        // And the positive statement, so the table above cannot pass by returning false in all 48.
        assertEquals(
            "exactly one of the 48 combinations puts the question on screen",
            1,
            actual.count { it.endsWith("-> true") },
        )
    }

    @Test
    fun `dropping any single condition would put the question somewhere it must not go`() {
        // The table above proves the function is what it is. This says why each clause is *there*,
        // by naming the state that clause alone excludes — so a later reader deleting one has a
        // sentence explaining what breaks rather than a row of booleans.
        val shows = { answered: Boolean, onScreen: Boolean, up: Boolean, bright: Boolean, ans: Boolean? ->
            countUpBrightnessPromptShows(answered, onScreen, up, bright, ans)
        }

        assertTrue("the one case that must show", shows(true, true, true, true, null))

        assertEquals(
            "asked before the officer had been through the launch surface at all",
            false,
            shows(false, true, true, true, null),
        )
        assertEquals(
            "asked while the officer is not looking at the screen it renders on — silence is only " +
                "an answer if the question was askable",
            false,
            shows(true, false, true, true, null),
        )
        assertEquals(
            "asked during a countdown, which has a gun to justify the panel cost",
            false,
            shows(true, true, false, true, null),
        )
        assertEquals(
            "asked of an officer who declined brightness and so has nothing to release",
            false,
            shows(true, true, true, false, null),
        )
        assertEquals(
            "asked again after the officer already answered keep",
            false,
            shows(true, true, true, true, true),
        )
        assertEquals(
            "asked again after the officer already answered dim",
            false,
            shows(true, true, true, true, false),
        )
    }
}
