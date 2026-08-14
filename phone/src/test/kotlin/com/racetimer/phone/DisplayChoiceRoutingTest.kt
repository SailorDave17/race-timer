package com.racetimer.phone

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.racetimer.phone.ui.TAG_CONTINUE
import com.racetimer.shared.BuiltInSequences
import com.racetimer.phone.ui.TAG_FULL_BRIGHTNESS
import com.racetimer.phone.ui.TAG_KEEP_SCREEN_ON
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * All four combinations of the two switches, asserted as **what was handed to the mechanism**
 * (#225 AC 3, and AC 4's "at any timer state" half).
 *
 * Recording the call rather than reading the window is what makes the all-off corner mean anything.
 * On a real window, `DisplayChoice(false, false)` and *no call at all* are the same observation —
 * the flag is clear and brightness is released either way. A recorded call carries the values, so
 * every one of the four cases is positive evidence (cairn
 * `a-stubbed-default-cannot-report-the-platform-moved`: an expected value of false is suspect until
 * something says otherwise). That the recorded call really reaches the window is
 * `DisplayChoiceSurfaceTest`'s job, on the real activity, and that the mechanism then lands both
 * properties independently is `DisplayPropertiesTest`'s — #199, four combinations, mutation-proven.
 *
 * **AC 4 asks that no override is applied at any timer state**, so the last test drives the app into
 * one — sequence picked, Start tapped, engine out of IDLE and into RUNNING, which is precisely the
 * state the watch's table would have used to turn brightness on. Arguing it from structure alone was
 * the first draft and it was weaker: "the mechanism is called once" and "the race reached a state"
 * are different claims, and only the second is what the criterion says.
 */
@RunWith(RobolectricTestRunner::class)
class DisplayChoiceRoutingTest {

    @get:Rule
    val compose = createComposeRule()

    private val applied = mutableListOf<DisplayChoice>()

    private fun answer(keepScreenOn: Boolean, fullBrightness: Boolean) {
        compose.setContent {
            // This class composes the full app, so it is exposed to the dead-flusher hang the same
            // way the timer-screen tests were — #239 measured its last case at 48 s once, two-thirds
            // of the way to the timeout. See GlobalSnapshotFlushLoop for the mechanism.
            GlobalSnapshotFlushLoop()
            RaceTimerApp(applyDisplay = { applied += it })
        }

        if (keepScreenOn != DisplayChoice.INITIAL.keepScreenOn) {
            compose.onNodeWithTag(TAG_KEEP_SCREEN_ON).performClick()
        }
        if (fullBrightness != DisplayChoice.INITIAL.fullBrightness) {
            compose.onNodeWithTag(TAG_FULL_BRIGHTNESS).performClick()
        }
        // Nothing may have reached the mechanism yet: until Continue, the phone behaves as an
        // unmodified one. This is also the control for every assertion below — the list was empty
        // a moment before it was not.
        assertEquals("nothing applied before Continue", emptyList<DisplayChoice>(), applied)

        compose.onNodeWithTag(TAG_CONTINUE).performClick()
        compose.waitForIdle()
    }

    @Test
    fun `neither chosen hands the mechanism both off`() {
        answer(keepScreenOn = false, fullBrightness = false)
        assertEquals(listOf(DisplayChoice(keepScreenOn = false, fullBrightness = false)), applied)
    }

    @Test
    fun `screen-on alone hands the mechanism exactly that`() {
        answer(keepScreenOn = true, fullBrightness = false)
        assertEquals(listOf(DisplayChoice(keepScreenOn = true, fullBrightness = false)), applied)
    }

    @Test
    fun `brightness alone hands the mechanism exactly that`() {
        // The combination that proves neither is read off the other: direct sun, free thumb, and an
        // officer content to tap the screen awake.
        answer(keepScreenOn = false, fullBrightness = true)
        assertEquals(listOf(DisplayChoice(keepScreenOn = false, fullBrightness = true)), applied)
    }

    @Test
    fun `both chosen hands the mechanism both`() {
        answer(keepScreenOn = true, fullBrightness = true)
        assertEquals(listOf(DisplayChoice(keepScreenOn = true, fullBrightness = true)), applied)
    }

    @Test
    fun `a running sequence never re-applies, so an unchosen override never appears`() {
        // AC 4 in full: full brightness declined, then a sequence actually picked and started. The
        // criterion says "at any timer state", so this drives the app into one rather than arguing
        // from structure — the engine moves from IDLE into RUNNING here, which is the state the
        // watch's table would have used to turn brightness on.
        answer(keepScreenOn = true, fullBrightness = false)
        assertEquals("the choice was applied once on continuing", 1, applied.size)

        compose.onNodeWithText(BuiltInSequences.usSailing.name).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Start").performClick()
        compose.waitForIdle()

        // Positive control that the race really is on: the button is a function of engine state, so
        // it reading "Stop" is the app telling us it left IDLE. Without this the assertion below
        // holds just as well on a countdown that never started.
        compose.onNodeWithText("Stop").assertIsDisplayed()

        assertEquals("still exactly one application, mid-race", 1, applied.size)
        assertEquals(
            "and it is the combination the officer chose",
            DisplayChoice(keepScreenOn = true, fullBrightness = false),
            applied.single(),
        )
    }
}
