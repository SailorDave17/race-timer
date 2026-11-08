package com.racetimer.phone

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.racetimer.phone.ui.TAG_CUSTOM_ENTRY
import com.racetimer.phone.ui.TAG_SET_DURATION
import com.racetimer.shared.BuiltInSequences
import com.racetimer.shared.TimerState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A setup the watch sends while the phone shows its sequence picker (#336).
 *
 * Found on the owner's pair in #223: *Back* from *GO!* lands on the picker, which marks no current
 * choice, so a watch pick taken there moved the runner's selection while nothing on screen changed.
 * The owner's form at pickup is that the phone opens the pre-start screen for it. Driven through the
 * real `RaceTimerApp`, with the setup arriving as `MainActivity` delivers it: the runner's selection
 * moved by the service, then `selectionVersion` bumped (`RaceTimerAppHarness.setupFromTheWatch`).
 *
 * Every test checks the screen it starts on before the setup arrives, so an assertion that the
 * picker is "still" up cannot pass on a picker that was never there.
 */
@RunWith(RobolectricTestRunner::class)
class SetupOnPickerTest {

    @get:Rule
    val compose = createComposeRule()

    private val app = RaceTimerAppHarness(compose)

    @Test
    fun `a setup the watch sends while the picker is up opens the pre-start screen for it`() {
        app.launch()
        assertOnPicker()

        app.setupFromTheWatch(BuiltInSequences.scholastic)
        app.advance(1_000L)

        // The pre-start screen, set to the watch's sequence: its name, its length, and Start.
        compose.onNodeWithText(PICKER_TITLE).assertDoesNotExist()
        compose.onNodeWithText(BuiltInSequences.scholastic.name).assertIsDisplayed()
        compose.onNodeWithText("Start").assertIsDisplayed()
        app.assertReadout("3:00")
        // Nothing started: the screen shows a setup, and the race is still the officer's to start.
        assertEquals(TimerState.IDLE, app.runner.engine.currentState)
    }

    @Test
    fun `with nothing from the watch the picker stays as it is`() {
        app.launch()
        assertOnPicker()
        app.advance(10_000L)
        assertOnPicker()
    }

    @Test
    fun `a setup taken earlier does not move the officer off a picker opened since`() {
        // The setup lands on the pre-start screen, where it has always shown (#221).
        app.launch()
        compose.onNodeWithText(BuiltInSequences.club.name).performScrollTo().performClick()
        app.setupFromTheWatch(BuiltInSequences.scholastic)
        app.advance(1_000L)
        compose.onNodeWithText(BuiltInSequences.scholastic.name).assertIsDisplayed()

        // A recreation over an idle engine opens on the picker (#281). The setup is already shown
        // and spent; acting on the counter's value rather than its move would re-open the pre-start
        // screen here, on every rotation.
        app.recreateActivity()
        assertOnPicker()
        app.advance(2_000L)
        assertOnPicker()
    }

    @Test
    fun `a setup taken under the Custom stepper leaves the officer dialling`() {
        app.launch()
        compose.onNodeWithTag(TAG_CUSTOM_ENTRY).performScrollTo().performClick()
        compose.onNodeWithTag(TAG_SET_DURATION).assertIsDisplayed()

        app.setupFromTheWatch(BuiltInSequences.scholastic)
        app.advance(1_000L)

        compose.onNodeWithTag(TAG_SET_DURATION).assertIsDisplayed()
        compose.onNodeWithText("Start").assertDoesNotExist()
    }

    @Test
    fun `a setup taken under the launch surface leaves the picker up once it is answered`() {
        app.launch(answerDisplay = false)
        compose.onNodeWithText(PICKER_TITLE).assertDoesNotExist()

        app.setupFromTheWatch(BuiltInSequences.scholastic)
        app.advance(1_000L)
        app.answerDisplaySurface()

        assertOnPicker()
    }

    private fun assertOnPicker() {
        compose.onNodeWithText(PICKER_TITLE).assertIsDisplayed()
        compose.onNodeWithText("Start").assertDoesNotExist()
    }

    private companion object {
        const val PICKER_TITLE = "Select sequence"
    }
}
