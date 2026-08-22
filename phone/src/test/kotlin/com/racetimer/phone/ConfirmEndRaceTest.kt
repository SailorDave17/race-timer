package com.racetimer.phone

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.racetimer.phone.ui.ConfirmEndRaceDialog
import com.racetimer.phone.ui.TAG_CONFIRM_END_RACE
import com.racetimer.phone.ui.TAG_KEEP_RUNNING_RACE
import com.racetimer.shared.BuiltInSequences
import com.racetimer.shared.TimerState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The confirm-before-discard dialog (#281 AC 4) — the officer's route past `select`'s refusal.
 *
 * Two levels, because they answer different questions and only one of them is reachable:
 *
 *  - **The dialog itself**, rendered directly: each control fires its own callback and nothing
 *    else's. Deterministic, and the same idiom `TimerScreenResumeOfferTest` uses for the same
 *    reason.
 *  - **The wiring**, driven through the whole app: a refused select parks the selection, raises the
 *    question, and the two answers do what they say. That arrangement is **not reachable in the
 *    shipped app** after AC 1 — a live race opens the timer screen and Back is disabled while it
 *    runs, so the picker cannot be sat on with a race in progress. It is arranged here on purpose:
 *    this is a defence, and a defence with no way to fire is indistinguishable from dead code to
 *    whoever tidies up next.
 */
@RunWith(RobolectricTestRunner::class)
class ConfirmEndRaceTest {

    @get:Rule
    val compose = createComposeRule()

    private val taps = mutableListOf<String>()

    private val raceManager = BuiltInSequences.scholasticRaceManager
    private val club = BuiltInSequences.club

    // --- the dialog on its own -------------------------------------------------------------

    private fun renderDialog() {
        compose.setContent {
            ConfirmEndRaceDialog(
                sequenceName = club.name,
                onConfirm = { taps += "confirm" },
                onDismiss = { taps += "dismiss" },
            )
        }
    }

    @Test
    fun `the question names the sequence being switched to, so it is about a concrete swap`() {
        renderDialog()
        compose.onNodeWithText("A race is still running").assertIsDisplayed()
        compose.onNodeWithText(club.name, substring = true).assertIsDisplayed()
    }

    @Test
    fun `End it and switch fires only the destructive callback`() {
        renderDialog()
        compose.onNodeWithTag(TAG_CONFIRM_END_RACE).performClick()
        assertEquals(listOf("confirm"), taps)
    }

    @Test
    fun `Keep running fires only the dismiss callback`() {
        renderDialog()
        compose.onNodeWithTag(TAG_KEEP_RUNNING_RACE).performClick()
        assertEquals(listOf("dismiss"), taps)
    }

    // --- the wiring, through the whole app -------------------------------------------------

    private val app = RaceTimerAppHarness(compose)

    /**
     * Reach the picker with a race running — the state the guard defends against.
     *
     * Arranged rather than caused, and it has to be: the reattach in AC 1 keys on the runner
     * arriving, so starting the race *after* the composition already has one leaves the picker
     * showing over a live engine. In the shipped app nothing starts a race except the timer
     * screen's own control, so this state has no route to it — which is the point.
     */
    private fun pickerOverALiveRace() {
        app.launch()
        app.runner.select(raceManager)
        app.runner.start()
        compose.waitForIdle()
        assertEquals(TimerState.RUNNING, app.runner.engine.currentState)
        // Positive control on the arrangement: the picker really is what is on screen, so what
        // follows is a claim about a tap that would have destroyed a race rather than about a
        // screen that was never there.
        compose.onNodeWithText(club.name).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `picking a sequence over a running race asks instead of destroying it`() {
        pickerOverALiveRace()

        compose.onNodeWithText(club.name).performScrollTo().performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(TAG_CONFIRM_END_RACE).assertIsDisplayed()
        // Nothing has happened to the race yet — the question is the whole of what the tap did.
        assertEquals(TimerState.RUNNING, app.runner.engine.currentState)
        assertEquals(raceManager, app.runner.selected)
    }

    @Test
    fun `Keep running leaves the race alone and drops the selection`() {
        pickerOverALiveRace()
        compose.onNodeWithText(club.name).performScrollTo().performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(TAG_KEEP_RUNNING_RACE).performClick()
        compose.waitForIdle()

        assertEquals(
            "the race was ended by declining to end it",
            TimerState.RUNNING,
            app.runner.engine.currentState,
        )
        assertEquals("the refused selection was applied anyway", raceManager, app.runner.selected)
        compose.onNodeWithTag(TAG_CONFIRM_END_RACE).assertDoesNotExist()
        // Back exactly where they were: the branch that raised the question stayed composed under it.
        compose.onNodeWithText(club.name).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `End it and switch ends the race and opens the new one`() {
        pickerOverALiveRace()
        compose.onNodeWithText(club.name).performScrollTo().performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(TAG_CONFIRM_END_RACE).performClick()
        compose.waitForIdle()

        assertEquals("the old race survived a confirmed switch", TimerState.IDLE, app.runner.engine.currentState)
        assertEquals("the confirmed selection was not applied", club, app.runner.selected)
        compose.onNodeWithTag(TAG_CONFIRM_END_RACE).assertDoesNotExist()
        // And it opens the race it was asked to open, rather than dropping the officer on the picker
        // having just spent their running race.
        compose.onNodeWithText("Start").assertIsDisplayed()
    }
}
