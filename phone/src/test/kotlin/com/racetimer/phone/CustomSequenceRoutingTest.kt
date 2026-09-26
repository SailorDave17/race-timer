package com.racetimer.phone

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.racetimer.phone.ui.CUSTOM_ENTRY_LABEL
import com.racetimer.phone.ui.CustomDurationScreen
import com.racetimer.phone.ui.SequencePickerScreen
import com.racetimer.phone.ui.TAG_CONTINUE
import com.racetimer.phone.ui.TAG_CUSTOM_ENTRY
import com.racetimer.phone.ui.TAG_SET_DURATION
import com.racetimer.shared.BuiltInSequences
import com.racetimer.shared.RaceSequence
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * #209 AC 1's screen half: Custom is reachable, the stepper yields the duration the officer dialled,
 * and the entry is withheld where it would be dead.
 *
 * The two screens directly rather than through the whole app — the full-app harness is #239's flake,
 * and the question here is what each surface hands its caller, which is exactly what a callback
 * records. [PhoneCustomSequenceTest] covers what happens to the value afterwards.
 */
@RunWith(RobolectricTestRunner::class)
class CustomSequenceRoutingTest {

    @get:Rule
    val compose = createComposeRule()

    private val picked = mutableListOf<String>()
    private val confirmed = mutableListOf<Int>()

    private fun picker(withCustom: Boolean = true) {
        compose.setContent {
            SequencePickerScreen(
                sequences = PhoneRaceRunner.CONSOLE_SEQUENCES,
                onSelect = { picked += it.id },
                onCustomSelected = if (withCustom) ({ picked += "custom-tapped" }) else null,
            )
        }
    }

    private fun stepper(initialMinutes: Int) {
        compose.setContent {
            CustomDurationScreen(initialMinutes = initialMinutes, onConfirm = { confirmed += it })
        }
    }

    @Test
    fun `Custom sits alongside the sailor sequences and routes instead of selecting`() {
        picker()
        // The built-in entries are still there and still select — a Custom entry that displaced one
        // would pass a test that only looked for Custom.
        compose.onNodeWithText(BuiltInSequences.usSailing.name).assertIsDisplayed()
        // Scrolled to first, and that is a fact about the screen rather than test ceremony: with
        // three sequences above it the Custom entry sits below the fold on a small phone, so the
        // picker's `verticalScroll` is load-bearing and a tap has to reach it the way a thumb does.
        // Measured — without this the click lands on nothing and the callback never fires.
        compose.onNodeWithTag(TAG_CUSTOM_ENTRY).performScrollTo().performClick()
        assertEquals(listOf("custom-tapped"), picked)
    }

    @Test
    fun `a caller with nowhere to route Custom gets no Custom entry`() {
        picker(withCustom = false)
        compose.onNodeWithText(CUSTOM_ENTRY_LABEL).assertDoesNotExist()
        // The positive control: the screen rendered, so the absence above is the entry being
        // withheld and not the whole picker failing to compose.
        compose.onNodeWithText(BuiltInSequences.usSailing.name).assertIsDisplayed()
    }

    @Test
    fun `the stepper confirms the duration on screen, not the one it opened with`() {
        stepper(initialMinutes = 5)
        compose.onNodeWithText("5:00").assertIsDisplayed()
        compose.onNodeWithText("+").performClick()
        compose.onNodeWithText("+").performClick()
        compose.onNodeWithText("7:00").assertIsDisplayed()
        compose.onNodeWithTag(TAG_SET_DURATION).performClick()
        assertEquals(listOf(7), confirmed)
    }

    @Test
    fun `the stepper reopens where the last custom race was left`() {
        stepper(initialMinutes = 8)
        compose.onNodeWithText("8:00").assertIsDisplayed()
        compose.onNodeWithTag(TAG_SET_DURATION).performClick()
        assertEquals(listOf(8), confirmed)
    }

    @Test
    fun `the floor is enforced by the control, not by a clamp the officer cannot see`() {
        stepper(initialMinutes = BuiltInSequences.CUSTOM_MIN_MINUTES)
        compose.onNodeWithText("1:00").assertIsDisplayed()
        compose.onNodeWithText("−").assertIsNotEnabled()
        // + stays live at the floor, so "disabled" cannot be a property of the row rather than of
        // the one control that should have it.
        compose.onNodeWithText("+").assertIsEnabled()
    }

    @Test
    fun `a duration below the floor opens at the floor rather than below it`() {
        // Only reachable from a persisted value nothing validated, which is why the screen clamps
        // rather than trusting its caller.
        stepper(initialMinutes = 0)
        compose.onNodeWithText("1:00").assertIsDisplayed()
        compose.onNodeWithTag(TAG_SET_DURATION).performClick()
        assertEquals(listOf(BuiltInSequences.CUSTOM_MIN_MINUTES), confirmed)
    }

    /**
     * The glue between a tap and the prefs file, through the whole app (#209 AC 1).
     *
     * *Measured*: deleting `selectAndOpen`'s call to `onSequencePicked` reddened **0 of 79** before
     * this test existed, against a written prediction of 0. Every test above holds a screen or the
     * service in isolation, so nothing covered the one line that carries a choice from the officer's
     * thumb to persistence — the half of AC 1 that says the choice *persists*.
     *
     * Composes the full app, so it takes #239's flush loop for the same reason
     * `DisplayChoiceRoutingTest` does.
     */
    private fun app(): MutableList<String> {
        val remembered = mutableListOf<String>()
        compose.setContent {
            GlobalSnapshotFlushLoop()
            RaceTimerApp(
                applyDisplay = {},
                onSequencePicked = { remembered += it.id },
            )
        }
        compose.onNodeWithTag(TAG_CONTINUE).performClick()
        // The control: a launch remembers nothing until something is chosen, so a non-empty list
        // below is the tap and not the app writing on its own.
        assertEquals(emptyList<String>(), remembered)
        return remembered
    }

    @Test
    fun `picking a built-in sequence remembers it`() {
        val remembered = app()
        compose.onNodeWithText(BuiltInSequences.scholastic.name).performClick()
        assertEquals(listOf(BuiltInSequences.scholastic.id), remembered)
    }

    @Test
    fun `dialling a custom duration remembers the id that encodes it`() {
        val remembered = app()
        compose.onNodeWithTag(TAG_CUSTOM_ENTRY).performScrollTo().performClick()
        compose.onNodeWithText("+").performClick()
        compose.onNodeWithTag(TAG_SET_DURATION).performClick()

        // 6, not 5: the default plus one step. An id carrying the default would pass a test that
        // only checked the shape, and would be exactly the bug where the stepper is decorative.
        assertEquals(listOf("custom_6m"), remembered)
    }

    @Test
    fun `the sequence a confirmed duration names is the one shared code builds`() {
        // The seam this story owns: minutes in, id out. Everything downstream reads that string.
        val sequence: RaceSequence = BuiltInSequences.custom(7)
        assertEquals("custom_7m", sequence.id)
        assertEquals(sequence.cues, BuiltInSequences.resolve("custom_7m")?.cues)
    }
}
