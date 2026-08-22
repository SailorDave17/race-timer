package com.racetimer.phone

import android.view.WindowManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.racetimer.phone.ui.TAG_CONTINUE
import com.racetimer.phone.ui.TAG_FULL_BRIGHTNESS
import com.racetimer.phone.ui.TAG_KEEP_SCREEN_ON
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The officer is asked, cannot get past without answering, and what they said reaches the real
 * window (#225 ACs 1, 2, 6, and the wiring half of AC 3).
 *
 * **This is the repo's first Compose test**, granted for this story specifically (owner decision
 * 2026-08-13) and bounded by the reason it was granted. #64 rejected Robolectric because *"a shadow
 * records that the call happened, it does not model what the call does"* — fatal when the property
 * is what a real `AudioTrack` delivers. The property here is **navigation**: whether a person tapping
 * the screen can reach sequence selection without answering. For that, "records that the tap
 * happened" is exactly sufficient — the same reasoning that flipped #160 the other way. The audio
 * and haptic NO is untouched and nothing here reaches for it.
 *
 * It runs on the JVM. No device is involved, and no timing claim is made or could be.
 *
 * The subject is the real `MainActivity`, so the window these assertions read is the window an
 * officer would be looking at — the same choice `DisplayPropertiesTest` makes, for the same reason.
 *
 * **The all-off corner of AC 3 is deliberately not asserted here.** A fresh window already has the
 * flag clear and brightness released, so "neither was applied" and "nothing ran" are the same
 * observation, and no control within one activity can separate them — the rule launches one activity
 * and the surface is answerable once. `DisplayChoiceRoutingTest` covers all four as *what was handed
 * to the mechanism*, which is positive evidence in every case; the corners asserted here are the
 * ones where the real window can say something a fresh window would not.
 */
@RunWith(RobolectricTestRunner::class)
class DisplayChoiceSurfaceTest {

    private val compose = createAndroidComposeRule<MainActivity>()

    /**
     * The flusher runs **outside** the compose rule, because the rule launches `MainActivity` during
     * its own setup and that is where the hang can start (#239, wired here by #281).
     *
     * This class is the module's only `createAndroidComposeRule` one, so it has no `setContent` to
     * compose `GlobalSnapshotFlushLoop` into and was never covered by #239's fix — it has been
     * relying on running first, which is the one position where compose's global-snapshot collector
     * is still alive. *Measured 2026-08-21:* four unrelated new test classes moved it and all five
     * tests here failed with `AppNotIdleException`; the same five pass when the class is run alone.
     * See `GlobalSnapshotFlushRule`.
     */
    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(GlobalSnapshotFlushRule()).around(compose)

    private val brightness: Float
        get() = compose.activity.window.attributes.screenBrightness

    private val holdsScreenOn: Boolean
        get() = (
            compose.activity.window.attributes.flags and
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            ) != 0

    @Test
    fun `sequence selection is unreachable until the surface has been answered`() {
        compose.onNodeWithTag(TAG_CONTINUE).assertIsDisplayed()
        compose.onNodeWithText("Select sequence").assertDoesNotExist()

        compose.onNodeWithTag(TAG_CONTINUE).performClick()
        compose.waitForIdle()

        // The positive control for the assertion above: "Select sequence" is a node this harness can
        // find when it is present, so its absence a moment ago was the gate rather than a matcher
        // that never matches anything.
        compose.onNodeWithText("Select sequence").assertIsDisplayed()
        compose.onNodeWithTag(TAG_CONTINUE).assertDoesNotExist()
    }

    @Test
    fun `the surface opens with screen-on preselected and full brightness not`() {
        // The ratified initial positions (#225 AC 2). A tap-through is the commonest path, so what
        // it yields is a product decision and not an initialiser — hence a test on the positions
        // themselves rather than only on what they produce.
        compose.onNodeWithTag(TAG_KEEP_SCREEN_ON).assertIsOn()
        compose.onNodeWithTag(TAG_FULL_BRIGHTNESS).assertIsOff()
    }

    @Test
    fun `a tap-through holds the real window awake`() {
        assertEquals("control — a fresh window is not holding the screen on", false, holdsScreenOn)

        compose.onNodeWithTag(TAG_CONTINUE).performClick()
        compose.waitForIdle()

        // Positive: the flag is SET, which a window nobody touched would not be.
        assertEquals("the tap-through default reached the window", true, holdsScreenOn)
    }

    @Test
    fun `choosing full brightness drives the real panel override`() {
        assertEquals(
            "control — a fresh window has no override",
            WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE,
            brightness,
            0f,
        )

        compose.onNodeWithTag(TAG_FULL_BRIGHTNESS).performClick()
        compose.onNodeWithTag(TAG_CONTINUE).performClick()
        compose.waitForIdle()

        // Positive, and it is the end-to-end wiring proof: a switch tapped on the surface reaches
        // #199's mechanism and lands on the window an officer is looking at.
        assertEquals(
            "full brightness reached the window",
            WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL,
            brightness,
            0f,
        )
    }

    @Test
    fun `the choice survives a configuration change and is not asked again`() {
        compose.onNodeWithTag(TAG_FULL_BRIGHTNESS).performClick()
        compose.onNodeWithTag(TAG_CONTINUE).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Select sequence").assertIsDisplayed()

        compose.activityRule.scenario.recreate()
        compose.waitForIdle()

        // AC 6: rotation is a configuration change, and the officer is mid-start day. Being asked
        // again here would put the surface between them and the race.
        compose.onNodeWithTag(TAG_CONTINUE).assertDoesNotExist()
        compose.onNodeWithText("Select sequence").assertIsDisplayed()
        assertEquals(
            "the chosen brightness is re-applied to the new window",
            WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL,
            compose.activity.window.attributes.screenBrightness,
            0f,
        )
    }
}
