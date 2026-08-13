package com.racetimer.phone

import android.view.Window
import android.view.WindowManager
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/**
 * The phone's two display properties land on the real window, and land independently (#199).
 *
 * The subject is deliberately `MainActivity`'s own window rather than a fabricated one: the question
 * these criteria ask is whether the property reaches *the window the officer is looking at*, and a
 * test window would answer a different one. This stays inside #160's scoped yes for Robolectric on
 * this module — a real `Context` and the framework's own `Window`, no audio and no haptics.
 *
 * **Every assertion here is preceded by its own opposite.** A fresh window already has
 * `FLAG_KEEP_SCREEN_ON` clear and `screenBrightness` at `BRIGHTNESS_OVERRIDE_NONE`, so an assertion
 * that either is absent passes just as well when nothing ran at all — the shape cairn
 * `a-stubbed-default-cannot-report-the-platform-moved` and `an-absent-result-reads-as-a-clean-one`
 * both name. Driving the window to the opposite value first, and asserting *that* took, is what makes
 * the negative direction evidence rather than a restatement of the platform default.
 *
 * **What no test here can see, stated rather than left to be discovered.** `Window.getAttributes`
 * hands back the window's live `LayoutParams`, so mutating that object and *assigning* it read
 * identically from this side — dropping the assignment in `PhoneDisplay.kt` reddens **nothing here**
 * (measured, #199 mutation pass). On a device the assignment is what applies the change; in a unit
 * test the object is the same either way. The route is held by the guard in `ModuleBoundaryTest` and
 * by the reasoning in `PhoneDisplay.kt`; the instrument that would actually catch it is the owner's
 * eye on the panel, which is #215.
 */
@RunWith(RobolectricTestRunner::class)
class DisplayPropertiesTest {

    /** A window from a freshly created activity — the app's real one, at its untouched defaults. */
    private fun freshWindow(): Window =
        Robolectric.buildActivity(MainActivity::class.java).create().get().window

    /** Read back from the window's own attributes, not from a flag this test remembers setting. */
    private val Window.holdsScreenOn: Boolean
        get() = (attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0

    private val Window.appliedBrightness: Float
        get() = attributes.screenBrightness

    @Test
    fun `screen-on sets the flag when asked and clears it when not`() {
        val window = freshWindow()

        window.applyDisplayProperties(keepScreenOn = true, fullBrightness = false)
        assertEquals("FLAG_KEEP_SCREEN_ON after requesting screen-on", true, window.holdsScreenOn)

        // The positive control for the assertion below: the flag was demonstrably settable on this
        // window a line ago, so its absence now is this call clearing it rather than nothing running.
        window.applyDisplayProperties(keepScreenOn = false, fullBrightness = false)
        assertEquals("FLAG_KEEP_SCREEN_ON after withdrawing screen-on", false, window.holdsScreenOn)
    }

    @Test
    fun `full brightness applies the override and releases it back to the system`() {
        val window = freshWindow()

        window.applyDisplayProperties(keepScreenOn = false, fullBrightness = true)
        // The applied layoutParams value, not a return code: cairn `android-window-brightness-override`
        // records that the obvious success indicator reports 100% of a range short of the panel's max.
        assertEquals(
            "screenBrightness after requesting full brightness",
            WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL,
            window.appliedBrightness,
            0f,
        )

        window.applyDisplayProperties(keepScreenOn = false, fullBrightness = false)
        assertEquals(
            "screenBrightness after releasing the override — the system's own brightness governs",
            WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE,
            window.appliedBrightness,
            0f,
        )
    }

    @Test
    fun `each of the four combinations lands whole, neither property readable off the other`() {
        for (keepScreenOn in listOf(false, true)) {
            for (fullBrightness in listOf(false, true)) {
                val case = "keepScreenOn=$keepScreenOn fullBrightness=$fullBrightness"
                val window = freshWindow()

                // Drive both to the opposite first, so that a `false` in this combination has to be
                // *applied* rather than inherited from a fresh window. Without this, the (false,
                // false) case is indistinguishable from a call that never happened.
                window.applyDisplayProperties(!keepScreenOn, !fullBrightness)
                assertEquals("$case — precondition, screen-on inverted", !keepScreenOn, window.holdsScreenOn)
                assertEquals(
                    "$case — precondition, brightness inverted",
                    expectedBrightness(!fullBrightness),
                    window.appliedBrightness,
                    0f,
                )

                window.applyDisplayProperties(keepScreenOn, fullBrightness)
                assertEquals("$case — screen-on", keepScreenOn, window.holdsScreenOn)
                assertEquals("$case — brightness", expectedBrightness(fullBrightness), window.appliedBrightness, 0f)
            }
        }
    }

    private fun expectedBrightness(fullBrightness: Boolean): Float =
        if (fullBrightness) {
            WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL
        } else {
            WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
}
