package com.racetimer.phone

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.racetimer.phone.ui.TAG_CONTINUE
import com.racetimer.phone.ui.TAG_DIM_COUNT_UP
import com.racetimer.phone.ui.TAG_FULL_BRIGHTNESS
import com.racetimer.phone.ui.TAG_KEEP_BRIGHT
import com.racetimer.shared.BuiltInSequences
import com.racetimer.shared.TimerState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The phone's display in the states with no bound and no gun (#284), from the officer's side.
 *
 * The decisions, taken by the owner 2026-11-03 — what was rejected is recorded on the issue:
 *
 *  - **IDLE releases** full brightness, on the picker and the pre-start screen alike, with no
 *    question. Start brings it back.
 *  - **FINISHED is bright for the gun's linger**, then releases. The phone holds the GO! screen
 *    until somebody moves on, so without the bound it would burn the panel indefinitely.
 *  - **RACE_ENDED follows the count-up's answer**: bright only on Keep bright. Its Dim and silence
 *    halves are in `CountUpBrightnessTest`, beside the question they follow.
 *
 * Every history here starts with a release, because the launch surface hands over to the picker
 * and the picker is nothing timed. The rule underneath is tabled exhaustively in
 * `CountUpBrightnessRuleTest`; what this adds is the *transitions*, which a table of phases cannot
 * see — the linger's clock above all.
 */
@RunWith(RobolectricTestRunner::class)
class UnboundedStatesBrightnessTest {

    @get:Rule
    val compose = createComposeRule()

    private val applied = mutableListOf<DisplayChoice>()

    private val app by lazy { RaceTimerAppHarness(compose) }

    private val bright = DisplayChoice(keepScreenOn = true, fullBrightness = true)
    private val dimmed = DisplayChoice(keepScreenOn = true, fullBrightness = false)

    private val sailor = BuiltInSequences.usSailing
    private val raceManager = BuiltInSequences.scholasticRaceManager

    @Test
    fun `the picker and the pre-start screen release brightness, and Start brings it back`() {
        app.launch(fullBrightness = true, applyDisplay = { applied += it })
        compose.onNodeWithText("Select sequence").assertIsDisplayed()
        assertEquals("the picker is nothing timed", listOf(dimmed), applied)

        // The pre-start screen is IDLE too: the sequence is loaded and Start is on screen, and
        // nothing is counting. Picking moves nothing.
        compose.onNodeWithText(sailor.name).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Start").assertIsDisplayed()
        assertEquals("the pre-start screen re-decided the display", listOf(dimmed), applied)

        // The positive control for both assertions above: the same session, one tap later, hands
        // the mechanism the brightness — so "released" was the rule, not a choice that never took.
        compose.onNodeWithText("Start").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Stop").assertIsDisplayed()
        assertEquals("the countdown got the officer's brightness", listOf(dimmed, bright), applied)

        // And Stop puts the pre-start screen back, which releases again.
        compose.onNodeWithText("Stop").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Start").assertIsDisplayed()
        assertEquals("a stopped race kept the panel", listOf(dimmed, bright, dimmed), applied)
    }

    @Test
    fun `the GO! screen is bright for the gun's linger, then releases until the officer moves on`() {
        app.launch(fullBrightness = true, applyDisplay = { applied += it })
        app.startRace(sailor)
        assertEquals(listOf(dimmed, bright), applied)

        // One second past the gun: the GO! screen, inside its linger. The gun is the moment the
        // whole sequence led up to, and dropping the panel at it was the rejected option.
        app.advance(sailor.totalMs + 1_000L, stepMs = 1_000L)
        assertEquals(TimerState.FINISHED, app.runner.engine.currentState)
        assertEquals("the gun dimmed the panel inside its linger", listOf(dimmed, bright), applied)

        // Two seconds clear of the linger's end — the harness's frame pump costs about a second of
        // virtual time over this many advances (measured in `CountUpBrightnessTest`'s boundary case),
        // so the margin is on the side that keeps a correct build green.
        app.advance(PhoneTimerService.GUN_LINGER_MS + 2_000L, stepMs = 1_000L)
        assertEquals("the GO! screen is held until somebody moves on", TimerState.FINISHED, app.runner.engine.currentState)
        assertEquals("the linger outlived its bound", listOf(dimmed, bright, dimmed), applied)

        // Leaving FINISHED leaves its narrowing behind (AC 5): Start on the GO! screen is the next
        // race's countdown, and it gets the whole brightness back.
        compose.onNodeWithText("Start").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Stop").assertIsDisplayed()
        assertEquals(
            "the next countdown inherited the GO! screen's release",
            listOf(dimmed, bright, dimmed, bright),
            applied,
        )
    }

    @Test
    fun `a kept count-up's final time stays bright, asks nothing, and releases at Done`() {
        app.launch(fullBrightness = true, applyDisplay = { applied += it })
        app.startRace(raceManager)
        app.runPastTheGun(raceManager)
        compose.onNodeWithTag(TAG_KEEP_BRIGHT).performClick()
        compose.waitForIdle()
        assertEquals(listOf(dimmed, bright), applied)

        compose.onNodeWithText("End Race").performClick()
        compose.waitForIdle()
        assertEquals(TimerState.RACE_ENDED, app.runner.engine.currentState)

        // AC 4: the summary composes with the question at the gun rather than sitting beside it —
        // no second question appears, and the answer already given governs.
        compose.onNodeWithTag(TAG_KEEP_BRIGHT).assertDoesNotExist()
        compose.onNodeWithTag(TAG_DIM_COUNT_UP).assertDoesNotExist()
        compose.onNodeWithText("Keep the screen bright?").assertDoesNotExist()

        // Held well past anything the count-up question's dwell could explain, so "still bright"
        // is the Keep bright answer and not a timer that has not fired yet.
        app.advance(COUNT_UP_PROMPT_DWELL_MS + 5_000L, stepMs = 1_000L)
        assertEquals("a kept summary dropped the panel", listOf(dimmed, bright), applied)

        // Done is nothing timed, so leaving the summary takes the brightness with it (AC 5).
        compose.onNodeWithText("Done").performClick()
        compose.waitForIdle()
        assertEquals("Done left the panel at full", listOf(dimmed, bright, dimmed), applied)
    }

    @Test
    fun `a countdown whose binding drops and returns never flashes dim`() {
        // In production the runner is the service's, and `onStop` drops it to null; on return there
        // are frames before the binding lands again. With no runner the composition's state reads
        // IDLE by default — and IDLE releases — so without `PanelPhase.NOT_YET_KNOWN` the officer
        // coming back to a live countdown would see it step dim and back.
        var runner by mutableStateOf<PhoneRaceRunner?>(app.runner)
        compose.setContent {
            GlobalSnapshotFlushLoop()
            RaceTimerApp(
                applyDisplay = { applied += it },
                runner = runner,
                displayChoice = app.displayChoice,
            )
        }
        compose.onNodeWithTag(TAG_FULL_BRIGHTNESS).performClick()
        compose.onNodeWithTag(TAG_CONTINUE).performClick()
        compose.waitForIdle()
        app.startRace(sailor)
        assertEquals(listOf(dimmed, bright), applied)

        runner = null
        compose.waitForIdle()
        assertEquals("the dropped binding re-decided the display", listOf(dimmed, bright), applied)

        runner = app.runner
        compose.waitForIdle()
        compose.onNodeWithText("Stop").assertIsDisplayed()
        assertEquals(TimerState.RUNNING, app.runner.engine.currentState)
        assertEquals("the countdown flashed dim across the rebind", listOf(dimmed, bright), applied)
    }
}
