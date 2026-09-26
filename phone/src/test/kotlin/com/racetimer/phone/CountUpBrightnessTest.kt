package com.racetimer.phone

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.racetimer.phone.ui.TAG_DIM_COUNT_UP
import com.racetimer.phone.ui.TAG_KEEP_BRIGHT
import com.racetimer.shared.BuiltInSequences
import com.racetimer.shared.TimerState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The count-up brightness question, from the gun to the answer to the next race (#279).
 *
 * The decision this holds, taken by the owner 2026-08-19 against the three shapes the issue tabled:
 * **ask once, at the gun**, with silence dimming and the answer standing for the rest of the
 * session — but governing count-ups only, so every later countdown gets the full brightness the
 * officer asked for at launch. What was rejected is recorded on the issue.
 *
 * ## Every negative assertion here has a clock, and that is the point
 *
 * The dwell answers the question by itself. So *"nothing dimmed"* is not a claim until you know how
 * long the test waited: taken one second after the gun it is true of a build with no rule at all,
 * and true of a correct one, and the two are the same observation. race-timer #206 shipped exactly
 * that — an assertion that the service had not torn down, taken four seconds before the teardown it
 * denied could have fired (cairn `prove-a-guard-test-can-fail`, fourteenth outcome). Every test
 * below that asserts an absence therefore runs [advancePastTheDwell] first, and the margin is taken
 * from the production constant rather than typed in, so a change to the dwell moves the observation
 * window with it instead of quietly making these vacuous.
 *
 * The subject is the whole app rather than `TimerScreen`, for the reason
 * `RaceManagerVoicingAndDisplayTest` gives: what is under test is the *transitions* — gun, count-up,
 * End Race, next Start — and a test that rendered one screen in one state could not see them.
 */
@RunWith(RobolectricTestRunner::class)
class CountUpBrightnessTest {

    @get:Rule
    val compose = createComposeRule()

    private val applied = mutableListOf<DisplayChoice>()

    private val app by lazy { RaceTimerAppHarness(compose) }

    private val bright = DisplayChoice(keepScreenOn = true, fullBrightness = true)
    private val dimmed = DisplayChoice(keepScreenOn = true, fullBrightness = false)

    private val raceManager = BuiltInSequences.scholasticRaceManager

    /** How far either side of the dwell the boundary test observes — see its own comment. */
    private val MARGIN_MS = 3_000L

    /**
     * Open with full brightness chosen, and run a race-manager sequence to just past its gun.
     *
     * Returns with the engine in `COUNTING_UP` and the question on screen, well inside the dwell —
     * [RaceTimerAppHarness.runPastTheGun] lands a few seconds past the gun, which the band on
     * [COUNT_UP_PROMPT_DWELL_MS] keeps well inside the dwell.
     */
    private fun runToTheGun() {
        app.launch(fullBrightness = true, applyDisplay = { applied += it })
        assertEquals("the officer's launch choice, applied once", listOf(bright), applied)

        app.startRace(raceManager)
        app.runPastTheGun(raceManager)
        assertEquals(TimerState.COUNTING_UP, app.runner.engine.currentState)
    }

    /** Move past the point an unanswered question answers itself, with margin. */
    private fun advancePastTheDwell() =
        app.advance(COUNT_UP_PROMPT_DWELL_MS + 5_000L, stepMs = 1_000L)

    @Test
    fun `the question is asked at the gun and nothing has moved while it stands`() {
        runToTheGun()

        compose.onNodeWithText("Keep the screen bright?").assertIsDisplayed()
        compose.onNodeWithTag(TAG_KEEP_BRIGHT).assertIsDisplayed()
        compose.onNodeWithTag(TAG_DIM_COUNT_UP).assertIsDisplayed()

        // The question shares the screen with the only control a count-up has, and the prompt is a
        // third child in a column whose readout takes a fixed fraction of the whole box — so "the
        // prompt is displayed" and "End Race is still displayed" are two claims, and this story
        // only ever asserted the first. Every other test here taps End Race *after* answering, with
        // the prompt already gone, so without this line the one layout state the story creates is
        // asserted by nothing.
        compose.onNodeWithText("End Race").assertIsDisplayed()

        // Asking is not answering. The panel is still doing what the officer asked for while the
        // question is on screen — a prompt that dimmed on appearing would have answered itself.
        assertEquals("the question alone changed the display", listOf(bright), applied)
    }

    @Test
    fun `silence dims, once the dwell has actually elapsed`() {
        runToTheGun()
        advancePastTheDwell()

        assertEquals(
            "an unanswered count-up releases the brightness override and nothing else",
            listOf(bright, dimmed),
            applied,
        )
        // The question is spent: it answered itself, so it is no longer on screen.
        compose.onNodeWithTag(TAG_KEEP_BRIGHT).assertDoesNotExist()
        assertEquals(
            "and the race is still counting up",
            TimerState.COUNTING_UP,
            app.runner.engine.currentState,
        )
    }

    @Test
    fun `the question stands right up to the dwell and answers itself just after`() {
        runToTheGun()

        // Straddling the boundary rather than clearing it by a mile, which is what makes the
        // *effect's* delay and the constant the same quantity. Precisely: a `delay` of **half** the
        // constant is caught here and nowhere else, since the coarse test above waits long enough to
        // see an early dim as a correct one. Doubling is caught by both, so this test is not what
        // holds that case — said exactly, because the loose version of this sentence is the argument
        // somebody would use to delete the coarse test as redundant. The spent part of the window is
        // read off the harness rather than restated, so the arithmetic cannot drift.
        //
        // [MARGIN_MS] is why this is a band and not a knife edge. *Measured 2026-08-19*: with the
        // dwell at 15 s the effect fired at gun **+ 14** harness-seconds — `advance` pumps frames
        // to settle the composition and that costs virtual time the requested advances do not
        // account for, about a second across the ~19 calls this test makes. So the lower assertion
        // has **two** seconds of real margin rather than three, and the slop grows with the number
        // of advance calls rather than with wall time — which is why a much larger dwell reddens
        // this test as well as the band. Still an order tighter than any wrong delay worth catching.
        app.advance(
            COUNT_UP_PROMPT_DWELL_MS - RaceTimerAppHarness.PAST_GUN_MS - MARGIN_MS,
            stepMs = 1_000L,
        )
        compose.onNodeWithTag(TAG_KEEP_BRIGHT).assertIsDisplayed()
        assertEquals("short of the dwell, the question still stands", listOf(bright), applied)

        app.advance(2 * MARGIN_MS, stepMs = 1_000L)
        assertEquals(
            "and past it, it has answered itself",
            listOf(bright, dimmed),
            applied,
        )
    }

    @Test
    fun `Keep bright is honoured, and still honoured after the dwell would have fired`() {
        runToTheGun()
        compose.onNodeWithTag(TAG_KEEP_BRIGHT).performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(TAG_KEEP_BRIGHT).assertDoesNotExist()
        assertEquals("keeping it bright re-applies nothing", listOf(bright), applied)

        // The assertion that needed the clock: the dwell was armed when the question appeared, and
        // an answer that failed to cancel it would dim the panel once the dwell elapsed.
        // Without this advance the two builds are indistinguishable.
        advancePastTheDwell()
        assertEquals("the answer survived the dwell", listOf(bright), applied)
        assertEquals(TimerState.COUNTING_UP, app.runner.engine.currentState)
    }

    @Test
    fun `Dim is the same answer as silence, taken immediately`() {
        runToTheGun()
        compose.onNodeWithTag(TAG_DIM_COUNT_UP).performClick()
        compose.waitForIdle()

        // No advance before this one on purpose: tapping Dim must not wait out the dwell, so the
        // assertion is taken at a moment when the automatic path demonstrably has not fired —
        // the boundary test above proves it needs the whole dwell to.
        assertEquals("Dim releases the override on the tap", listOf(bright, dimmed), applied)
        compose.onNodeWithTag(TAG_DIM_COUNT_UP).assertDoesNotExist()
    }

    @Test
    fun `the next countdown gets its full brightness back`() {
        runToTheGun()
        compose.onNodeWithTag(TAG_DIM_COUNT_UP).performClick()
        compose.waitForIdle()
        assertEquals(listOf(bright, dimmed), applied)

        // End Race leaves the count-up, and the officer's choice comes straight back: the frozen
        // final time is read across a boat in the same sun the countdown was.
        compose.onNodeWithText("End Race").performClick()
        compose.waitForIdle()
        assertEquals(TimerState.RACE_ENDED, app.runner.engine.currentState)
        assertEquals(
            "leaving the count-up restores what the officer chose",
            listOf(bright, dimmed, bright),
            applied,
        )

        // And through the next race's countdown, which is the state with a gun to justify the cost
        // — this is the whole difference between the shape chosen and revising the launch answer.
        compose.onNodeWithText("Done").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Start").performClick()
        app.advance(3_000L, stepMs = 1_000L)
        assertEquals(TimerState.RUNNING, app.runner.engine.currentState)
        assertEquals(
            "the countdown is bright and was not re-decided",
            listOf(bright, dimmed, bright),
            applied,
        )
    }

    @Test
    fun `the second gun applies the answer without asking again`() {
        runToTheGun()
        compose.onNodeWithTag(TAG_DIM_COUNT_UP).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("End Race").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Done").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Start").performClick()
        app.runPastTheGun(raceManager)
        assertEquals(TimerState.COUNTING_UP, app.runner.engine.currentState)

        // Asked once per launch: the officer already said, so the second count-up drops the
        // override on arrival rather than putting the question up again.
        compose.onNodeWithTag(TAG_KEEP_BRIGHT).assertDoesNotExist()
        assertEquals(
            "the standing answer applied to the second count-up",
            listOf(bright, dimmed, bright, dimmed),
            applied,
        )
    }

    @Test
    fun `a count-up rejoined after a recreation still has its question, unspent`() {
        // **This replaced a test #281 made unaskable, and the measurement is the keeper.**
        //
        // It used to arrange a count-up behind the **picker** — the state a recreated activity
        // landed in before #281 — and assert the dwell did not fire there. #281 abolished that
        // state: a composition that finds a live race opens the timer screen.
        //
        // A first rewrite tried to reach it anyway, by starting the race *after* the composition
        // already had its runner. That was **measured vacuous**: the composition's `state` is
        // refreshed by the display poll, which runs on the timer screen alone, so `countingUp` was
        // false in the composition and the assertion held for a reason with nothing to do with the
        // clause it claimed to prove. Deleting `onTimerScreen` from the predicate reddened it
        // **zero** — predicted 3 red across the pass, actual 2.
        //
        // There is no screen-level arrangement that separates them any more: the reattach sets
        // `onTimerScreen` and refreshes `state` in the same effect, so `countingUp && !onTimerScreen`
        // is unreachable through the UI by construction. The clause is therefore proven where it
        // can be — `CountUpBrightnessRuleTest`, exhaustively over all 48 combinations — and this
        // test asserts the reachable thing #281 actually created, which nothing else covers.
        runToTheGun()
        compose.onNodeWithTag(TAG_KEEP_BRIGHT).assertIsDisplayed()

        // The officer has not answered. Before #281 this is where the question was lost: the
        // recreated activity ran the launch flow from the top and the count-up was nowhere.
        app.recreateActivity()

        // Rejoined on the timer screen, with the question still standing — the ask is consumed by
        // an answer, never by having been shown, and a recreation is not an answer.
        compose.onNodeWithTag(TAG_KEEP_BRIGHT).assertIsDisplayed()
        assertEquals(
            "the recreation spent the question and dropped the panel the officer asked for",
            emptyList<DisplayChoice>(),
            applied.filter { it == dimmed },
        )

        // And the rebuilt composition carries its own dwell: silence still dims, from the new one.
        // Without this the test would assert only that a question *appeared*, and a prompt that
        // could never answer itself would pass it.
        advancePastTheDwell()
        assertEquals("the rejoined question never answered itself", dimmed, applied.last())
        compose.onNodeWithTag(TAG_KEEP_BRIGHT).assertDoesNotExist()
    }

    @Test
    fun `a count-up ended inside the dwell leaves the question unspent`() {
        // Stated twice in production comments — "the ask is consumed by an answer, never by having
        // been shown" — and guarded by nothing until this test. Every other case here reaches its
        // second count-up with the question already answered, so a change making the prompt
        // one-shot per process would have reddened none of them.
        runToTheGun()
        compose.onNodeWithTag(TAG_KEEP_BRIGHT).assertIsDisplayed()

        // End Race well inside the dwell, so the question leaves the screen unanswered.
        compose.onNodeWithText("End Race").performClick()
        compose.waitForIdle()
        assertEquals(TimerState.RACE_ENDED, app.runner.engine.currentState)

        // And the dwell must not fire behind the summary either — the same claim as the test above,
        // reached by the other route off the timer screen's count-up branch.
        advancePastTheDwell()
        assertEquals("the ended race's dwell answered anyway", listOf(bright), applied)

        compose.onNodeWithText("Done").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Start").performClick()
        app.runPastTheGun(raceManager)
        assertEquals(TimerState.COUNTING_UP, app.runner.engine.currentState)

        compose.onNodeWithTag(TAG_KEEP_BRIGHT).assertIsDisplayed()
        assertEquals("the second gun re-offers an unspent question", listOf(bright), applied)
    }

    @Test
    fun `an officer who declined full brightness is never asked`() {
        // Nothing to release, so the question would be noise on the one screen that has to stay
        // legible. This is also the case that keeps `RaceManagerVoicingAndDisplayTest` — which runs
        // a whole race-manager race on the initial choice — a true statement after this story.
        app.launch(fullBrightness = false, applyDisplay = { applied += it })
        assertEquals(listOf(DisplayChoice.INITIAL), applied)

        app.startRace(raceManager)
        app.runPastTheGun(raceManager)
        assertEquals(TimerState.COUNTING_UP, app.runner.engine.currentState)

        compose.onNodeWithTag(TAG_KEEP_BRIGHT).assertDoesNotExist()
        compose.onNodeWithTag(TAG_DIM_COUNT_UP).assertDoesNotExist()

        // The two assertions above are this test's content, and they redden: dropping the
        // brightness precondition from the prompt's predicate turns them red (measured, 1/1).
        //
        // The advance and the assertion below are **not** doing the work the first draft of this
        // comment claimed. With brightness declined, the rule's only possible act is setting
        // `fullBrightness = false` on a choice that already has it false — an identical object, so
        // the effect's key never moves and `applied` cannot grow whatever the rule does. *Measured*:
        // mutating the rule to release unconditionally left this test green. They are kept as a
        // cheap statement that the run reached the end in the expected state, and they are not
        // evidence about #279; the claim that they were is what would stop anyone re-reading them.
        advancePastTheDwell()
        assertEquals(
            "the run ended with the officer's declined choice still the only thing applied",
            listOf(DisplayChoice.INITIAL),
            applied,
        )
    }
}
