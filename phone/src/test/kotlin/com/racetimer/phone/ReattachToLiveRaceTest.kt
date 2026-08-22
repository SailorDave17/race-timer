package com.racetimer.phone

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.racetimer.phone.ui.TAG_CONTINUE
import com.racetimer.shared.BuiltInSequences
import com.racetimer.shared.TimerState
import com.racetimer.shared.formatCountdown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A recreated activity comes back to the race that is still running (#281 AC 1, AC 2, AC 3, AC 5).
 *
 * ### What was measured, and why it was worse than losing your place
 *
 * On the owner's SM-S918U, 2026-08-19: start a race, swipe back — the race correctly survives in
 * `PhoneTimerService` — then reopen. The activity had been recreated, so the launch flow ran from
 * the top and the live race was nowhere on screen while its notification kept counting. There was
 * **no route back**: `offerSavedRace` declines to offer onto a non-IDLE engine, which is right for
 * its own purpose and leaves a recreated UI with nothing. And the natural recovery — tapping the
 * same sequence in the picker — ran `TimerEngine.load`, which sets IDLE unconditionally and killed
 * the race outright. Three individually-correct pieces composing into a dead race.
 *
 * ### The instrument
 *
 * [RaceTimerAppHarness.recreateActivity] disposes the composition and builds it again, so every
 * `remember` is fresh — including `onTimerScreen`, the state whose loss this is about — while the
 * engine and the display choice survive, exactly as the service and the Application's view-model
 * store make them survive in production. The rule's own KDoc states what that reproduces and what
 * it does not.
 */
@RunWith(RobolectricTestRunner::class)
class ReattachToLiveRaceTest {

    @get:Rule
    val compose = createComposeRule()

    private val app = RaceTimerAppHarness(compose)

    private val raceManager = BuiltInSequences.scholasticRaceManager
    private val club = BuiltInSequences.club

    @Test
    fun `a recreated activity comes back to the running countdown, not the picker`() {
        app.launch()
        app.startRace()
        app.advance(5_000L)
        val beforeRecreation = app.readout()

        app.recreateActivity()

        // AC 1 and AC 5. The timer screen, from the engine's own state — no offer, no picker.
        compose.onNodeWithText("Stop").assertIsDisplayed()
        compose.onNodeWithText(club.name).assertDoesNotExist()
        compose.onNodeWithText("Resume").assertDoesNotExist()
        assertEquals(TimerState.RUNNING, app.runner.engine.currentState)

        // And it is the *same* race, still moving — not a fresh one at the top of the sequence, and
        // not a frozen picture of where the old one got to. Both would satisfy every assertion
        // above.
        assertNotEquals(
            "the readout came back at the sequence's full duration, so this is a new race",
            formatCountdown(BuiltInSequences.usSailing.totalMs),
            app.readout(),
        )
        app.advance(2_000L)
        assertNotEquals(
            "the countdown stopped moving after the recreation",
            beforeRecreation,
            app.readout(),
        )
    }

    @Test
    fun `a recreated activity comes back to a race-manager count-up`() {
        app.launch()
        app.startRace(raceManager)
        app.runPastTheGun(raceManager)
        assertEquals(TimerState.COUNTING_UP, app.runner.engine.currentState)

        app.recreateActivity()

        // COUNTING_UP is the other half of AC 1's condition, and the state an officer sits in
        // longest — it is where the phone waits between flights.
        compose.onNodeWithText("End Race").assertIsDisplayed()
        compose.onNodeWithText(club.name).assertDoesNotExist()
        assertEquals(TimerState.COUNTING_UP, app.runner.engine.currentState)
    }

    @Test
    fun `the officer is not asked to set the screen up again mid-race`() {
        app.launch()
        app.startRace()

        app.recreateActivity()

        // #225 ratified the display answers as lasting for the life of the *process*, and #281
        // measured them dying with the activity instead. The harness holds the view-model outside
        // the composition because production resolves it from the Application's store.
        compose.onNodeWithTag(TAG_CONTINUE).assertDoesNotExist()
        compose.onNodeWithText("Stop").assertIsDisplayed()
    }

    @Test
    fun `the controls on a reattached race behave as they do on one never left`() {
        // AC 2, and the reason it is a criterion of its own: reattaching is only worth anything if
        // what you come back to is a working race rather than a read-only picture of one.
        app.launch()
        app.startRace(raceManager)
        app.advance(5_000L)

        app.recreateActivity()

        // Sync snaps the countdown to a whole minute — the engine's rule, reached through the
        // reattached screen's own control.
        compose.onNodeWithText("Sync").performClick()
        compose.waitForIdle()
        assertEquals(
            "Sync did not reach the engine through the reattached screen",
            TimerState.RUNNING,
            app.runner.engine.currentState,
        )
        val afterSync = app.readout()
        assertEquals("Sync did not snap to a whole minute", "00", afterSync.substringAfter(":"))

        // End Race is the count-up's only control, and it has to work from here too.
        app.runPastTheGun(raceManager)
        compose.onNodeWithText("End Race").performClick()
        compose.waitForIdle()
        assertEquals(TimerState.RACE_ENDED, app.runner.engine.currentState)

        // And out of the summary, back to the top — the ordinary way a committee starts the next
        // flight, now reached entirely through a screen that was rebuilt mid-race.
        compose.onNodeWithText("Done").performClick()
        compose.waitForIdle()
        assertEquals(TimerState.IDLE, app.runner.engine.currentState)
    }

    @Test
    fun `Stop on a reattached countdown ends it, as it would on one never left`() {
        // The third control AC 2 names. The other two are exercised above on a race-manager
        // sequence, whose running screen offers End Race rather than Stop — so without this the
        // criterion would be ticked on two of the three controls it lists.
        app.launch()
        app.startRace()
        app.advance(5_000L)

        app.recreateActivity()

        compose.onNodeWithText("Stop").performClick()
        compose.waitForIdle()

        assertEquals(
            "Stop did not reach the engine through the reattached screen",
            TimerState.IDLE,
            app.runner.engine.currentState,
        )
        // Where Stop leaves an officer on a race never detached from: the timer screen, reset to the
        // top of the sequence and offering Start. Asserted rather than assumed, because "the engine
        // is idle" is also true of a screen showing nothing.
        compose.onNodeWithText("Start").assertIsDisplayed()
        assertEquals(
            "the readout did not return to the sequence's full duration",
            formatCountdown(BuiltInSequences.usSailing.totalMs),
            app.readout(),
        )
    }

    @Test
    fun `an idle engine launches exactly as it did before — choice screen, then picker`() {
        // AC 3, and the negative control for every assertion above: the reattach must fire on a
        // live engine and on nothing else. Without this, a change that opened the timer screen
        // unconditionally would pass all four tests above.
        app.launch()

        compose.onNodeWithText(club.name).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Stop").assertDoesNotExist()
        assertEquals(TimerState.IDLE, app.runner.engine.currentState)
    }

    @Test
    fun `a recreated activity over an idle engine still lands on the picker`() {
        // The other half of AC 3: a recreation is not itself a reason to open the timer screen. A
        // reattach keyed on the recreation rather than on the engine would pass everything else
        // here and strand an officer on a dead readout between flights.
        app.launch()
        app.startRace(raceManager)
        app.runPastTheGun(raceManager)
        compose.onNodeWithText("End Race").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Done").performClick()
        compose.waitForIdle()
        assertEquals(TimerState.IDLE, app.runner.engine.currentState)

        app.recreateActivity()

        compose.onNodeWithText(club.name).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Stop").assertDoesNotExist()
    }

    @Test
    fun `a finished countdown is not reattached to — it is over, and the picker is right`() {
        // FINISHED is deliberately outside `raceInProgress`. Asserted here rather than only at the
        // runner level because the two could disagree: the app could have re-derived the condition
        // instead of asking the runner, which is the duplicated-rule defect this module keeps out.
        app.launch()
        app.startRace(BuiltInSequences.usSailing)
        app.runPastTheGun(BuiltInSequences.usSailing)
        assertEquals(TimerState.FINISHED, app.runner.engine.currentState)

        app.recreateActivity()

        compose.onNodeWithText(club.name).performScrollTo().assertIsDisplayed()
    }
}
