package com.racetimer.phone

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The full app on the running timer screen — the tests #204 wanted and could not keep (#239).
 *
 * Both drive [RaceTimerApp] itself rather than [com.racetimer.phone.ui.TimerScreen] alone, which is
 * the whole point: the two-line `onSync` glue between the screen's callback and the runner is code
 * no screen-only test can reach, and it is exactly the code a mis-wiring would break.
 *
 * The dwell is what makes the first test more than a tap: the countdown is advanced through two
 * whole minutes in quarter-second slices, so the composition's display poll runs some hundreds of
 * times while the assertions bracket it. See [RaceTimerAppHarness] for why both clocks have to move
 * for that to mean anything, and [GlobalSnapshotFlusher] for the harness defect that made tests of
 * this shape hang before #239.
 */
@RunWith(RobolectricTestRunner::class)
class RaceTimerAppTimerScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val app by lazy { RaceTimerAppHarness(compose) }

    @Test
    fun `the countdown follows the engine through a dwell on the running screen`() {
        app.launch()
        app.startRace()
        // The full duration, before anything has moved: the control for every assertion below.
        app.assertReadout("5:00")

        app.advance(61_000L)
        app.assertReadout("3:59")

        app.advance(60_000L)
        app.assertReadout("2:59")

        // And the screen is still the running one — a dwell that silently fell back to the picker
        // would satisfy a readout assertion on the idle screen just as well.
        compose.onNodeWithText("Stop").performClick()
        compose.onNodeWithText("Start").assertExists()
    }

    @Test
    fun `a Sync tap through the whole app reaches the runner and the display follows`() {
        app.launch()
        app.startRace()

        app.advance(35_000L)
        // 4:25 left: 25 s past the 4:00 boundary, so the nearest minute is below and the correction
        // is inside the engine's 30 s limit. Both halves matter — a sync that overshot the limit
        // would be refused, and the test would then be asserting the refusal.
        app.assertReadout("4:25")

        compose.onNodeWithText("Sync").performClick()

        // The engine really snapped — not just the screen, which a display-only fix could fake.
        assertEquals("the engine's remaining time after the snap", 240_000L, app.runner.engine.remainingMs)
        // ...and the display followed it, in the same pass, with no clock advance in between.
        app.assertReadout("4:00")
    }
}
