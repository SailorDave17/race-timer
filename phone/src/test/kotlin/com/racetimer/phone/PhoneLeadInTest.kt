package com.racetimer.phone

import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.racetimer.phone.ui.LEAD_IN_LABEL
import com.racetimer.phone.ui.TAG_BOX_ALERT_CUSTOM
import com.racetimer.phone.ui.TAG_CONTINUE
import com.racetimer.phone.ui.TAG_SET_BOX_ALERT
import com.racetimer.phone.ui.boxAlertTag
import com.racetimer.shared.BOX_ALERT_MIN_SECONDS
import com.racetimer.shared.BuiltInSequences
import com.racetimer.shared.TimerState
import com.racetimer.shared.leadInId
import com.racetimer.shared.withLeadIn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/**
 * #207 AC 2 through the **whole app**: a race-manager sequence's pre-start screen offers the
 * lead-in, the box-alert picker starts the armed race, Custom dials one, the last alert is
 * remembered, the run-up drops Sync until the sequence proper, and the lead is spent with the race.
 *
 * Full-app for cairn's `exported-is-not-reachable` reason, the same one [RaceManagerCountUpTest]
 * gives: shared has had a working lead-in for a month, and the phone had `resolve` rebuilding one
 * from a persisted id since #205, with no route from an officer's thumb to either. The criterion
 * is that the control *surfaces*, so the assertions start at the picker and end at the engine.
 */
@RunWith(RobolectricTestRunner::class)
class PhoneLeadInTest {

    @get:Rule
    val compose = createComposeRule()

    private val app by lazy { RaceTimerAppHarness(compose) }

    private val base = BuiltInSequences.scholasticRaceManager

    /** Pick [sequence] from the picker, landing on its pre-start screen. */
    private fun openPreStart(sequence: com.racetimer.shared.RaceSequence = base) {
        compose.onNodeWithText(sequence.name).performScrollTo().performClick()
    }

    @Test
    fun `a race-manager sequence's pre-start screen offers the lead-in and a sailor's does not`() {
        app.launch()
        openPreStart(BuiltInSequences.usSailing)
        // The negative control first: a sailor has no box to sync to, and a sailor who arms this
        // by accident starts late. The rule is shared's `offersLeadIn`; this proves the phone asks
        // it rather than offering the control to everyone.
        compose.onNodeWithText("Start").assertIsDisplayed()
        compose.onNodeWithText(LEAD_IN_LABEL).assertDoesNotExist()

        // Back to the picker — `Back` is a system gesture the harness cannot send, but the
        // sequence picker is one tap away through the idle screen's own route in production, so
        // this test relaunches instead of asserting a navigation it is not about.
        app.recreateActivity()
        openPreStart()
        compose.onNodeWithText("Start").assertIsDisplayed()
        compose.onNodeWithText(LEAD_IN_LABEL).assertIsDisplayed()
    }

    @Test
    fun `choosing a preset alert starts the armed race on its own clock`() {
        app.launch()
        openPreStart()
        app.assertReadout("3:00")

        compose.onNodeWithText(LEAD_IN_LABEL).performClick()
        // The picker names both numbers on every row: the alert the box is set to, and the lead
        // it produces. Showing only one makes the other a surprise.
        compose.onNodeWithText("Off").assertIsDisplayed()
        compose.onNodeWithText("60 s").assertIsDisplayed()
        compose.onNodeWithText("70 s lead").assertIsDisplayed()
        compose.onNodeWithTag(boxAlertTag(60)).performScrollTo().performClick()

        // The tapped row was the confirm: the race is running, on the armed variant, and the
        // readout is the whole lead plus the sequence — not the plain 3:00.
        assertEquals(TimerState.RUNNING, app.runner.engine.currentState)
        assertEquals(leadInId(base.id, 60), app.runner.selected.id)
        app.assertReadout("4:10")
        // In the run-up: Stop is the sole control and Sync is withheld.
        compose.onNodeWithText("Stop").assertIsDisplayed()
        compose.onNodeWithText("Sync").assertDoesNotExist()
    }

    @Test
    fun `Sync returns on the tick the sequence's own first signal fires`() {
        app.launch()
        openPreStart()
        compose.onNodeWithText(LEAD_IN_LABEL).performClick()
        compose.onNodeWithTag(boxAlertTag(60)).performScrollTo().performClick()

        // 69 s in, still inside the alert window: 3:01 on the clock and no Sync.
        app.advance(69_000L)
        app.assertReadout("3:01")
        compose.onNodeWithText("Sync").assertDoesNotExist()

        // One more second: the 3-long fires, the lead is over, and Sync is back — on a screen whose
        // readout is now the sequence proper's own 3:00.
        app.advance(1_000L)
        app.assertReadout("3:00")
        compose.onNodeWithText("Sync").assertIsDisplayed()
        compose.onNodeWithText("Stop").assertIsDisplayed()
    }

    @Test
    fun `Custom dials an alert the presets do not cover and starts on it`() {
        app.launch()
        openPreStart()
        compose.onNodeWithText(LEAD_IN_LABEL).performClick()
        compose.onNodeWithTag(TAG_BOX_ALERT_CUSTOM).performScrollTo().performClick()

        // The default alert is Off, which the stepper cannot express, so it opens at the floor —
        // and says so in seconds with the lead it produces beneath.
        compose.onNodeWithText("$BOX_ALERT_MIN_SECONDS s").assertIsDisplayed()
        compose.onNodeWithText("+").performClick()
        compose.onNodeWithText("+").performClick()
        compose.onNodeWithText("7 s").assertIsDisplayed()
        compose.onNodeWithText("17 s lead").assertIsDisplayed()
        compose.onNodeWithTag(TAG_SET_BOX_ALERT).performClick()

        // 7, not 5: the floor plus two steps. An id carrying the floor would pass a test that only
        // checked the shape, and would be exactly the bug where the stepper is decorative.
        assertEquals(leadInId(base.id, 7), app.runner.selected.id)
        assertEquals(TimerState.RUNNING, app.runner.engine.currentState)
        app.assertReadout("3:17")
    }

    @Test
    fun `the lead is spent with the race, so Done returns to a plain sequence and a plain Start`() {
        app.launch()
        openPreStart()
        compose.onNodeWithText(LEAD_IN_LABEL).performClick()
        compose.onNodeWithTag(boxAlertTag(15)).performScrollTo().performClick()
        val armed = withLeadIn(base, 15)!!
        app.assertReadout("3:25")

        app.runPastTheGun(armed)
        assertEquals(TimerState.COUNTING_UP, app.runner.engine.currentState)
        compose.onNodeWithText("End Race").performClick()
        compose.onNodeWithText("Done").performClick()

        // The plain sequence at its own duration, with both ways to begin a race back on screen.
        // A selection still carrying the alert would read 3:25 here and run a lead nobody re-chose
        // on the next Start — the invisible state the two-tap picker exists to rule out.
        assertEquals(TimerState.IDLE, app.runner.engine.currentState)
        assertEquals(base.id, app.runner.selected.id)
        app.assertReadout("3:00")
        compose.onNodeWithText("Start").assertIsDisplayed()
        compose.onNodeWithText(LEAD_IN_LABEL).assertIsDisplayed()
    }

    @Test
    fun `the picker reopens on the alert last armed, and reports it to persistence`() {
        val chosen = mutableListOf<Int>()
        compose.setContent {
            GlobalSnapshotFlushLoop()
            RaceTimerApp(applyDisplay = {}, onBoxAlertChosen = { chosen += it })
        }
        compose.onNodeWithTag(TAG_CONTINUE).performClick()
        openPreStart()

        // First arming: the picker opens on the default, which is Off.
        compose.onNodeWithText(LEAD_IN_LABEL).performClick()
        compose.onNodeWithTag(boxAlertTag(0)).assertIsSelected()
        compose.onNodeWithTag(boxAlertTag(15)).assertIsNotSelected()
        compose.onNodeWithTag(boxAlertTag(15)).performScrollTo().performClick()
        assertEquals(listOf(15), chosen)

        // The race is stopped and re-armed: the picker now opens on 15 s, because a club runs one
        // box and its setting should be a confirm rather than a re-choice. The write-through above
        // is what makes the same true on the next cold launch.
        compose.onNodeWithText("Stop").performClick()
        compose.onNodeWithText(LEAD_IN_LABEL).performClick()
        compose.onNodeWithTag(boxAlertTag(15)).assertIsSelected()
        compose.onNodeWithTag(boxAlertTag(0)).assertIsNotSelected()
    }

    @Test
    fun `a lead-in started through the service persists its armed id, so a kill mid-run-up restores`() {
        // The service half of the route: the activity's lead-in start is the fresh-start intent,
        // and the arm path's persist slot writes the *armed* id — which is the whole mechanism
        // `PhoneRestoreTest` then proves the read side of.
        val svc = Robolectric.buildService(PhoneTimerService::class.java).create().get()
        val armed = withLeadIn(base, 60)!!
        assertTrue(svc.runner.select(armed))
        svc.onStartCommand(
            Intent().setAction(PhoneTimerService.ACTION_START)
                .putExtra(PhoneTimerService.EXTRA_FRESH_START, true),
            0, 1,
        )

        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val saved = PhoneRacePersistence(context).saved()
        assertNotNull("the arm path did not persist the lead-in race", saved)
        assertEquals(armed.id, saved!!.sequenceId)
        assertTrue(svc.runner.engine.remainingMs in 245_000..250_000)
    }

    @Test
    fun `the remembered alert survives a round trip and an out-of-range value reads as the default`() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val persistence = PhoneRacePersistence(context)
        persistence.saveLastBoxAlertSeconds(47)
        assertEquals(47, persistence.lastBoxAlertSeconds())
        // The one lead-in value not reconstructed from a sequence id, so a stored number nothing
        // validated has to be refused on the way out rather than opening the stepper off its range.
        persistence.saveLastBoxAlertSeconds(999)
        assertEquals(0, persistence.lastBoxAlertSeconds())
    }
}
