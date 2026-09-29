package com.racetimer.phone

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.racetimer.phone.ui.CUSTOM_ENTRY_LABEL
import com.racetimer.phone.ui.LEAD_IN_LABEL
import com.racetimer.shared.BuiltInSequences
import com.racetimer.shared.TimerState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A race the watch started takes the phone to its countdown with nobody touching the phone (#220).
 *
 * The service joins it; nothing on this screen is tapped. Before #220 every race reached the timer
 * screen by the route the tap took, and the app watched the engine only once, when the binding
 * landed. Each test first shows the screen it starts on is really up — the positive control — so a
 * pass cannot be the app simply having been on the timer screen all along.
 */
@RunWith(RobolectricTestRunner::class)
class FollowRemoteStartTest {

    @get:Rule
    val compose = createComposeRule()

    private val app = RaceTimerAppHarness(compose)

    @Test
    fun `from the picker to the countdown, on the watch's sequence`() {
        app.launch()
        compose.onNodeWithText(BuiltInSequences.club.name).performScrollTo().assertIsDisplayed()

        app.joinFromTheWatch(BuiltInSequences.scholastic)
        app.advance(1_000L)

        compose.onNodeWithText("Stop").assertIsDisplayed()
        compose.onNodeWithText(BuiltInSequences.club.name).assertDoesNotExist()
        compose.onNodeWithText(BuiltInSequences.scholastic.name).assertIsDisplayed()
        assertEquals(TimerState.RUNNING, app.runner.engine.currentState)
    }

    @Test
    fun `a race started behind the app's back is not a join, and is not followed`() {
        // The negative control for the one above, and the arrangement #281's confirm dialog is tested
        // from: only a join moves the screen, so the defence against a tap over a live race stays
        // reachable (ConfirmEndRaceTest).
        app.launch()
        app.runner.start()
        app.advance(1_000L)

        compose.onNodeWithText(BuiltInSequences.club.name).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Stop").assertDoesNotExist()
    }

    @Test
    fun `from the Custom stepper, which comes down with it`() {
        app.launch()
        compose.onNodeWithText(CUSTOM_ENTRY_LABEL).performScrollTo().performClick()
        compose.onNodeWithText("Custom sequence").assertIsDisplayed()

        app.joinFromTheWatch()
        app.advance(1_000L)

        compose.onNodeWithText("Stop").assertIsDisplayed()
        compose.onNodeWithText("Custom sequence").assertDoesNotExist()
    }

    @Test
    fun `from the lead-in picker, which comes down with it`() {
        app.launch()
        compose.onNodeWithText(BuiltInSequences.scholasticRaceManager.name).performScrollTo().performClick()
        compose.onNodeWithText(LEAD_IN_LABEL).performClick()
        compose.onNodeWithText("Box alert").assertIsDisplayed()

        app.joinFromTheWatch(BuiltInSequences.usSailing)
        app.advance(1_000L)

        compose.onNodeWithText("Stop").assertIsDisplayed()
        compose.onNodeWithText("Box alert").assertDoesNotExist()
    }

    @Test
    fun `a joined race's flag stands where the officer reads the race`() {
        var notice: String? = null
        app.launch(readPairNotice = { notice })
        app.joinFromTheWatch()
        app.advance(1_000L)
        compose.onNodeWithText(FLAG).assertDoesNotExist()

        notice = FLAG
        app.advance(1_000L)

        compose.onNodeWithText(FLAG).assertIsDisplayed()
    }

    private companion object {
        const val FLAG = "Watch start ±180 ms — tap Sync to confirm"
    }
}
