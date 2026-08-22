package com.racetimer.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The whole count-up brightness rule, exhaustively (#279 AC 4).
 *
 * `displayChoiceInEffect` is a pure function of three inputs with 2 x 2 x 2 x 3 = 24 combinations,
 * so the table below is not a sample — it is the function. That is the argument for the rule being
 * a free function at all: the behaviour the officer sees is a Compose test with a clock in it, and
 * the *rule* underneath it costs nothing to state completely, on the JVM, with no Robolectric and
 * no frame pump.
 *
 * Two properties are asserted here that no screen test could isolate:
 *
 *  - **screen-on is never touched.** #199 made the two window properties independent and #279 moves
 *    exactly one of them, so every row carries both values and the keep-screen-on column is a copy
 *    of its input in all 24.
 *  - **`null` keeps.** Unanswered is a third state, not a synonym for "dim" — the question is on
 *    screen at that moment, and a rule that dimmed while still asking would have answered itself.
 *    Writing the predicate as `!= true` instead of `== false` changes the *output* of exactly
 *    **two** of the 24 rows — *measured*, not counted by eye. Eight rows have an unanswered
 *    officer, but the released branch only shows where `fullBrightness` is true as well, and on the
 *    other six `copy(fullBrightness = false)` is identity. The first draft of this line said eight,
 *    which would have had the next mutation pass predict eight, measure two, and have to decide
 *    whether the test had decayed or the harness lied.
 */
class CountUpBrightnessRuleTest {

    @Test
    fun `only a released count-up drops brightness, and it drops nothing else`() {
        val actual = mutableListOf<String>()
        val expected = mutableListOf<String>()

        for (keepScreenOn in listOf(false, true)) {
            for (fullBrightness in listOf(false, true)) {
                for (countingUp in listOf(false, true)) {
                    for (answer in listOf(null, true, false)) {
                        val chosen = DisplayChoice(keepScreenOn, fullBrightness)
                        val inEffect = displayChoiceInEffect(chosen, countingUp, answer)

                        // Written out rather than derived from the function under test: an expected
                        // value computed by re-calling the subject is the shape that passes whatever
                        // the subject does (cairn `a-mutation-cannot-see-what-no-test-reaches`).
                        val released = countingUp && answer == false
                        val case = "keepScreenOn=$keepScreenOn fullBrightness=$fullBrightness " +
                            "countingUp=$countingUp answer=$answer"
                        expected += "$case -> keepScreenOn=$keepScreenOn " +
                            "fullBrightness=${fullBrightness && !released}"
                        actual += "$case -> keepScreenOn=${inEffect.keepScreenOn} " +
                            "fullBrightness=${inEffect.fullBrightness}"
                    }
                }
            }
        }

        // The count is asserted first: a loop that stopped iterating would agree with itself
        // perfectly and prove nothing (cairn `an-absent-result-reads-as-a-clean-one`).
        assertEquals("every combination of the rule's three inputs", 24, actual.size)
        assertEquals(expected, actual)
    }

    @Test
    fun `the only case that changes anything is a released count-up over chosen brightness`() {
        // The positive statement of the same rule, so the table above cannot pass by returning its
        // input in all 24 rows. Exactly two of the 24 differ from what was chosen, and they differ
        // in one field.
        val chosen = DisplayChoice(keepScreenOn = true, fullBrightness = true)

        assertEquals(
            "a released count-up drops brightness and keeps screen-on",
            DisplayChoice(keepScreenOn = true, fullBrightness = false),
            displayChoiceInEffect(chosen, countingUp = true, countUpKeepsBrightness = false),
        )
        assertEquals(
            "an unanswered count-up keeps what the officer chose — the question is still on screen",
            chosen,
            displayChoiceInEffect(chosen, countingUp = true, countUpKeepsBrightness = null),
        )
        assertEquals(
            "a kept count-up keeps what the officer chose",
            chosen,
            displayChoiceInEffect(chosen, countingUp = true, countUpKeepsBrightness = true),
        )
        assertEquals(
            "the countdown is untouched by the answer — it has a gun to justify the cost",
            chosen,
            displayChoiceInEffect(chosen, countingUp = false, countUpKeepsBrightness = false),
        )
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
