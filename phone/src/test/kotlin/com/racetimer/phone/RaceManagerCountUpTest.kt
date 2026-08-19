package com.racetimer.phone

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.racetimer.phone.ui.PhoneReadout
import com.racetimer.shared.BuiltInSequences
import com.racetimer.shared.TimerState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * #206 AC 1: the race-manager modes reach the officer, the gun turns the clock around, and End Race
 * freezes it.
 *
 * Driven through the **whole app** rather than through [PhoneRaceRunner] alone, because the
 * criterion is about what an officer can reach and do — "the display transitions to count-up and an
 * End Race control appears". A runner-level test would prove the engine changes state and say
 * nothing about whether anyone can see it, which is the distinction cairn's `exported-is-not-
 * reachable` is about: the phone had `COUNTING_UP` rendering correctly in [PhoneReadout] for four
 * stories while no sequence the picker offered could reach it.
 */
@RunWith(RobolectricTestRunner::class)
class RaceManagerCountUpTest {

    @get:Rule
    val compose = createComposeRule()

    private val app by lazy { RaceTimerAppHarness(compose) }

    private val sequence = BuiltInSequences.scholasticRaceManager

    @Test
    fun `a race-manager sequence is on the picker and starts`() {
        app.launch()
        // The picker half of AC 1, asserted before anything is tapped: a count-up that works
        // perfectly behind a mode nobody can select is the state this story found the phone in.
        compose.onNodeWithText(BuiltInSequences.usSailingRaceManager.name).assertIsDisplayed()
        app.startRace(sequence)
        assertEquals(TimerState.RUNNING, app.runner.engine.currentState)
    }

    @Test
    fun `the gun turns the countdown into a count-up and offers End Race`() {
        app.launch()
        app.startRace(sequence)
        // A positive control on the arrangement, not decoration: it pins that what follows is a
        // *change*. Without it a screen that had said 0:04 all along would pass the assertion below.
        app.assertReadout("3:00")

        app.runPastTheGun(sequence)

        assertEquals(TimerState.COUNTING_UP, app.runner.engine.currentState)
        // Counting *up*: four seconds past the gun reads 0:04, where the countdown would have read
        // 0:04 on its way down four seconds *before* it. Same string, opposite meaning — which is
        // why the state assertion above and the control assertion below both have to be here.
        app.assertReadout("0:04")
        compose.onNodeWithText("End Race").assertIsDisplayed()
        // The countdown's controls are gone. Stop surviving into a count-up would put a
        // discard-the-race control under the thumb of an officer reaching for End Race.
        compose.onNodeWithText("Stop").assertDoesNotExist()
        compose.onNodeWithText("Sync").assertDoesNotExist()
    }

    @Test
    fun `End Race drives RACE_ENDED and freezes the final time`() {
        app.launch()
        app.startRace(sequence)
        app.runPastTheGun(sequence)

        compose.onNodeWithText("End Race").performClick()
        assertEquals(TimerState.RACE_ENDED, app.runner.engine.currentState)
        app.assertReadout("0:04")

        // Frozen, and this is the half that needs the clock to keep moving to mean anything: five
        // more seconds pass and the number does not. An `endRace()` that merely stopped the display
        // poll would pass the assertion above and fail here.
        app.advance(5_000L)
        app.assertReadout("0:04")
        assertEquals(TimerState.RACE_ENDED, app.runner.engine.currentState)
    }

    @Test
    fun `Done dismisses the summary and returns the sequence to the top`() {
        app.launch()
        app.startRace(sequence)
        app.runPastTheGun(sequence)
        compose.onNodeWithText("End Race").performClick()

        // Without this the console runs exactly one race per launch: RACE_ENDED offers no Start —
        // the engine refuses one — so a summary with no way out strands the officer on a frozen
        // clock, and the epic's first bar condition is a whole start *day*.
        compose.onNodeWithText("Done").performClick()
        assertEquals(TimerState.IDLE, app.runner.engine.currentState)
        app.assertReadout("3:00")
    }

    @Test
    fun `a sailor sequence still ends at the gun`() {
        app.launch()
        app.startRace(BuiltInSequences.scholastic)
        app.runPastTheGun(BuiltInSequences.scholastic)

        // The negative control for the whole story, and the one that would catch `countUpAfterFinish`
        // being ignored — which is a single dropped condition away and would turn every sailor
        // sequence into a stopwatch that never stops.
        assertEquals(TimerState.FINISHED, app.runner.engine.currentState)
        app.assertReadout(PhoneReadout.GUN_LABEL)
        compose.onNodeWithText("End Race").assertDoesNotExist()
    }
}
