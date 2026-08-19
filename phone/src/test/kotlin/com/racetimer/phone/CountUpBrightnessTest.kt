package com.racetimer.phone

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
     * [RaceTimerAppHarness.runPastTheGun] lands a few seconds past the gun and the dwell is fifteen.
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
        // *effect's* delay and the constant the same quantity: a `delay` of twice the constant, or
        // half it, passes the coarse test above and fails one of the two assertions here. The spent
        // part of the window is read off the harness rather than restated, so the arithmetic cannot
        // drift out from under the assertions.
        //
        // [MARGIN_MS] is why this is a band and not a knife edge. *Measured 2026-08-19*: with the
        // dwell at 15 s the effect fired at gun **+ 14** harness-seconds — `advance` pumps frames
        // to settle the composition and that costs virtual time the requested advances do not
        // account for, about a second across the ~19 calls this test makes. Three seconds absorbs
        // that with room, and is still an order tighter than any wrong delay worth catching.
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
        // an answer that failed to cancel it would dim the panel fifteen seconds later. Without
        // this advance the two builds are indistinguishable.
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
        // assertion is taken at a moment when the automatic path demonstrably has not fired — the
        // test above proves it needs fifteen seconds to.
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

        // Past the dwell, so "nothing happened" is a statement about a build that had every chance
        // to act rather than about a test that stopped watching too early.
        advancePastTheDwell()
        assertEquals(
            "nothing was applied to a display with no override on it",
            listOf(DisplayChoice.INITIAL),
            applied,
        )
    }
}
