package com.racetimer.phone

import android.content.ComponentName
import android.content.Intent
import android.view.WindowManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.racetimer.phone.ui.TAG_CONTINUE
import com.racetimer.phone.ui.TAG_FULL_BRIGHTNESS
import com.racetimer.phone.ui.TAG_KEEP_SCREEN_ON
import com.racetimer.shared.BuiltInSequences
import com.racetimer.shared.TimerState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController

/**
 * The phone's pre-start screen holds the window on exactly when the officer chose screen-on at
 * launch (#300 AC 5).
 *
 * #300 keeps the watch's pre-start screen awake, because that is the screen a start is waited on.
 * The phone already behaves as asked — the officer's launch choice governs its window in every state
 * (#225, #199) — but nothing pinned it on this screen. Both directions are pinned here, because the
 * owner rejected forcing the screen on regardless (2026-09-25): that would reverse #225's model, in
 * which the officer owns the phone's display. An officer who declined screen-on gets a pre-start
 * screen that sleeps.
 *
 * The subject is the real `MainActivity` and its real window, for `DisplayChoiceSurfaceTest`'s
 * reason. Reaching the pre-start screen takes a runner, which production gets from the service
 * binding, so [boundService] gives Robolectric's `bindService` a real `PhoneTimerService` binder to
 * hand back. Without one the picker's entries select nothing and the test never leaves the picker.
 */
@RunWith(RobolectricTestRunner::class)
class PreStartScreenHoldTest {

    private lateinit var serviceController: ServiceController<PhoneTimerService>

    private val service: PhoneTimerService
        get() = serviceController.get()

    /**
     * Registers the binder before the compose rule launches the activity, which is why it sits
     * outside that rule: `MainActivity.onStart` binds during the launch, before any test body runs.
     */
    private val boundService = object : ExternalResource() {
        override fun before() {
            serviceController = Robolectric.buildService(PhoneTimerService::class.java).create()
            val app = RuntimeEnvironment.getApplication()
            shadowOf(app).setComponentNameAndServiceForBindService(
                ComponentName(app, PhoneTimerService::class.java),
                service.onBind(Intent(app, PhoneTimerService::class.java)),
            )
        }

        override fun after() {
            serviceController.destroy()
        }
    }

    private val compose = createAndroidComposeRule<MainActivity>()

    /** The flusher outermost, for the reason `GlobalSnapshotFlushRule` gives (#239, #281). */
    @get:Rule
    val rules: RuleChain =
        RuleChain.outerRule(GlobalSnapshotFlushRule()).around(boundService).around(compose)

    private val holdsScreenOn: Boolean
        get() = (
            compose.activity.window.attributes.flags and
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            ) != 0

    private val brightness: Float
        get() = compose.activity.window.attributes.screenBrightness

    /**
     * Pick a sequence and land on the pre-start screen, and prove the landing.
     *
     * Both checks are positive controls. Start on screen is the app reporting the pre-start screen,
     * and an `IDLE` engine is the service agreeing that nothing has started. Without them, every
     * assertion after this could be about the picker or a race rather than the screen in question.
     */
    private fun openPreStartScreen() {
        compose.onNodeWithText(BuiltInSequences.usSailing.name).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Start").assertIsDisplayed()
        assertEquals("the engine on the pre-start screen", TimerState.IDLE, service.runner.engine.currentState)
    }

    @Test
    fun `an officer who chose screen-on has the pre-start screen held`() {
        assertEquals("control — a fresh window is not holding the screen on", false, holdsScreenOn)

        // Screen-on is preselected (#225 AC 2), so a tap-through is this choice.
        compose.onNodeWithTag(TAG_CONTINUE).performClick()
        compose.waitForIdle()
        openPreStartScreen()

        // Positive: the flag is SET, which a window nobody touched would not be.
        assertEquals("screen-on held on the pre-start screen", true, holdsScreenOn)
    }

    @Test
    fun `an officer who declined screen-on has a pre-start screen that sleeps`() {
        // On a real window a declined screen-on reads the same as nothing having run — a fresh
        // window has the flag clear too (`DisplayChoiceSurfaceTest` records why its all-off corner is
        // not asserted). So full brightness is chosen alongside as the control: its override landing
        // on this same window is what proves the officer's answer reached it, which makes the flag's
        // absence the answer rather than a silence.
        compose.onNodeWithTag(TAG_KEEP_SCREEN_ON).performClick()
        compose.onNodeWithTag(TAG_FULL_BRIGHTNESS).performClick()
        compose.onNodeWithTag(TAG_CONTINUE).performClick()
        compose.waitForIdle()
        openPreStartScreen()

        assertEquals(
            "control — the officer's answer reached this window",
            WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL,
            brightness,
            0f,
        )
        assertEquals("a declined screen-on stays declined on the pre-start screen", false, holdsScreenOn)
    }
}
