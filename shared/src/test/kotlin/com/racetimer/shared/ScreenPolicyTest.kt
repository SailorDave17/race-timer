package com.racetimer.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the display policy table, and for the one thing a table like this actually gets wrong:
 * the relationship between its two rules.
 *
 * [keepsScreenOn] and [forcesMaxBrightness] disagree in exactly two places, both on purpose: the gun
 * is driven bright without being held awake (#65), and the pre-start screen is held awake without
 * being driven bright (#300). Asserting each rule in isolation would let either divergence be "tidied
 * up" into agreement by anyone who read it as an oversight, so the divergences themselves are asserted
 * here as the point of the exercise.
 */
class ScreenPolicyTest {

    // --- Keep-screen-on -------------------------------------------------------

    @Test fun `the screen is held awake while the countdown runs`() {
        assertTrue(keepsScreenOn(TimerState.RUNNING, onTimerScreen = true))
    }

    @Test fun `the screen is held awake while a finished race time is being read`() {
        // RACE_ENDED exists to hold the final time up for the committee; sleeping would defeat it.
        assertTrue(keepsScreenOn(TimerState.RACE_ENDED, onTimerScreen = true))
    }

    @Test fun `the pre-start screen is held awake until Start is pressed`() {
        // #300. IDLE on the timer screen is the sequence loaded and the sailor waiting on the warning
        // signal, so a sleep here costs the tap its timing. The countdown's own hold takes over at
        // Start: RUNNING is held on this same screen (above), so the two meet with no gap between.
        assertTrue(keepsScreenOn(TimerState.IDLE, onTimerScreen = true))
    }

    @Test fun `the sequence picker still sleeps`() {
        // #300's exclusion. The picker is IDLE too, and nobody waits on it for a signal — which is
        // the whole reason the rule reads the screen as well as the state.
        assertFalse(keepsScreenOn(TimerState.IDLE, onTimerScreen = false))
    }

    @Test fun `a race on screen is held whichever screen is up`() {
        // Off the timer screen neither state is reachable today. This keeps a later route that puts
        // one behind another screen from letting a live race sleep because of where it was reached.
        assertTrue(keepsScreenOn(TimerState.RUNNING, onTimerScreen = false))
        assertTrue(keepsScreenOn(TimerState.RACE_ENDED, onTimerScreen = false))
    }

    @Test fun `the screen may sleep when nobody is waiting on it`() {
        for (onTimerScreen in listOf(true, false)) {
            // PAUSED shows Start on the timer screen, and is not held: decided, not forgotten (#300).
            // Nothing can reach it today, and a story that wires pause decides it there.
            assertFalse(keepsScreenOn(TimerState.PAUSED, onTimerScreen))
            assertFalse(keepsScreenOn(TimerState.FINISHED, onTimerScreen))
            // A committee count-up is unbounded and is allowed to sleep (#59).
            assertFalse(keepsScreenOn(TimerState.COUNTING_UP, onTimerScreen))
        }
    }

    // --- Max brightness (#65) -------------------------------------------------

    @Test fun `every colour of the start sequence is driven at full brightness`() {
        // navy, amber and red-flash are all RUNNING — the background colour is a function of the
        // remaining time, which the brightness rule deliberately does not look at: a sailor reading
        // 4:30 in the sun needs the panel just as hard as one reading 0:09.
        assertTrue(forcesMaxBrightness(TimerState.RUNNING))
        // green, the gun.
        assertTrue(forcesMaxBrightness(TimerState.FINISHED))
        // green again, held open for the race committee.
        assertTrue(forcesMaxBrightness(TimerState.RACE_ENDED))
    }

    @Test fun `the override does not survive the sequence it was raised for`() {
        // AC 3: normal power-saving behaviour resumes. IDLE is where Stop, the post-gun teardown and
        // Done all land, so this single assertion covers every way a sequence can end.
        assertFalse(forcesMaxBrightness(TimerState.IDLE))
        assertFalse(forcesMaxBrightness(TimerState.PAUSED))
    }

    @Test fun `an unbounded count-up is not worth burning the panel for`() {
        // The one state where "a race is on screen" and "drive it bright" come apart: a committee
        // count-up routinely runs for an hour, and is already allowed to sleep.
        assertFalse(forcesMaxBrightness(TimerState.COUNTING_UP))
    }

    // --- The relationship between the two -------------------------------------

    @Test fun `the gun is the one state that is driven bright without being held awake`() {
        // FINISHED is transient — the service returns the engine to IDLE once the gun cue and its
        // "GO!" linger are done — so it inherits a screen that is already on and needs no wake flag.
        // It is also the instant that matters most, so it must not dim. If some later change makes
        // these two rules agree on FINISHED, one of those two facts has been lost.
        for (onTimerScreen in listOf(true, false)) {
            assertFalse(keepsScreenOn(TimerState.FINISHED, onTimerScreen))
            assertTrue(forcesMaxBrightness(TimerState.FINISHED))
        }
    }

    @Test fun `the pre-start screen is held awake without being driven bright`() {
        // #300's divergence, the mirror of the gun's. A postponement can keep a fleet on this screen
        // for an hour, and full brightness for that long is the panel cost #59 and #284 exist to
        // avoid. If these two ever agree here, either the wait sleeps again or the panel burns.
        assertTrue(keepsScreenOn(TimerState.IDLE, onTimerScreen = true))
        assertFalse(forcesMaxBrightness(TimerState.IDLE))
    }

    @Test fun `the two rules agree everywhere except the gun and the pre-start screen`() {
        // Guards both divergences from both directions: off the timer screen only the gun may
        // differ, and on it only the gun and IDLE. A new TimerState arriving with a copy-pasted
        // classification shows up here, and so does a hold that leaks onto the picker.
        fun differing(onTimerScreen: Boolean) =
            TimerState.entries.filter { keepsScreenOn(it, onTimerScreen) != forcesMaxBrightness(it) }
        assertEquals("off the timer screen", listOf(TimerState.FINISHED), differing(onTimerScreen = false))
        assertEquals(
            "on the timer screen",
            listOf(TimerState.IDLE, TimerState.FINISHED),
            differing(onTimerScreen = true),
        )
    }

    // --- The ambient gate (#12) -----------------------------------------------

    @Test fun `bright sun releases the override and indoor light keeps it`() {
        // The whole point of the gate, and it reads backwards until you know why: forcing the panel
        // to "maximum" *disables* the automatic strategy and pins 600 nits, while automatic reaches
        // 1000 in bright light. Direct sun is 10,000-100,000 lux, so up there the override is a
        // downgrade — and there is no API to ask for the panel's sunlight range, only the option to
        // stop suppressing the strategy that can reach it.
        assertFalse("direct sun", ambientPermitsOverride(50_000f, currentlyPermitted = true))
        assertTrue("indoors, where the override is worth up to 8.6x", ambientPermitsOverride(50f, currentlyPermitted = false))
    }

    @Test fun `a watch with no light sensor keeps the shipped behaviour`() {
        // Null is "no reading yet", which is also every sample before the first one arrives. It must
        // answer true: a missing sensor cannot be allowed to cost the large indoor win.
        assertTrue(ambientPermitsOverride(null, currentlyPermitted = true))
        assertTrue(ambientPermitsOverride(null, currentlyPermitted = false))
    }

    @Test fun `the band between the thresholds holds whichever way the gate last went`() {
        // Hysteresis, and the reason the two constants are not one. A sailor standing at the
        // threshold would otherwise oscillate, and every flip is a visible brightness step on a
        // screen someone is reading a clock off.
        val midBand = (OVERRIDE_ENGAGE_LUX + OVERRIDE_RELEASE_LUX) / 2f
        assertTrue("was permitted, stays permitted", ambientPermitsOverride(midBand, currentlyPermitted = true))
        assertFalse("was released, stays released", ambientPermitsOverride(midBand, currentlyPermitted = false))
    }

    @Test fun `the engage threshold sits below the release threshold`() {
        // If these ever cross or meet, the band above is empty and the hysteresis is gone without
        // any test above failing — each of them still passes on its own side.
        assertTrue(
            "engage ($OVERRIDE_ENGAGE_LUX) must stay below release ($OVERRIDE_RELEASE_LUX)",
            OVERRIDE_ENGAGE_LUX < OVERRIDE_RELEASE_LUX,
        )
    }

    @Test fun `both thresholds are decided at their own boundary`() {
        assertFalse("at the release threshold", ambientPermitsOverride(OVERRIDE_RELEASE_LUX, currentlyPermitted = true))
        assertTrue("at the engage threshold", ambientPermitsOverride(OVERRIDE_ENGAGE_LUX, currentlyPermitted = false))
    }

    @Test fun `the ambient gate cannot brighten a state the race says is dark`() {
        // The applied value is the conjunction of the two gates, so permissive ambient must never
        // resurrect a state forcesMaxBrightness excludes. Asserts the composition MainActivity does.
        for (state in TimerState.entries) {
            val applied = forcesMaxBrightness(state) && ambientPermitsOverride(10f, currentlyPermitted = true)
            assertEquals("$state under permissive ambient", forcesMaxBrightness(state), applied)
        }
    }
}
