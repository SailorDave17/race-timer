package com.racetimer.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the display policy table, and for the one thing a table like this actually gets wrong:
 * the relationship between its two rules.
 *
 * [keepsScreenOn] and [forcesMaxBrightness] agree on five of the six states. Asserting each in
 * isolation would let the sixth be "tidied up" into agreement by anyone who read the divergence as an
 * oversight, so the divergence itself is asserted here as the point of the exercise.
 */
class ScreenPolicyTest {

    // --- Keep-screen-on -------------------------------------------------------

    @Test fun `the screen is held awake while the countdown runs`() {
        assertTrue(keepsScreenOn(TimerState.RUNNING))
    }

    @Test fun `the screen is held awake while a finished race time is being read`() {
        // RACE_ENDED exists to hold the final time up for the committee; sleeping would defeat it.
        assertTrue(keepsScreenOn(TimerState.RACE_ENDED))
    }

    @Test fun `the screen may sleep when no countdown is on it`() {
        assertFalse(keepsScreenOn(TimerState.IDLE))
        assertFalse(keepsScreenOn(TimerState.PAUSED))
        assertFalse(keepsScreenOn(TimerState.FINISHED))
        // A committee count-up is unbounded and is allowed to sleep (#59).
        assertFalse(keepsScreenOn(TimerState.COUNTING_UP))
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
        assertFalse(keepsScreenOn(TimerState.FINISHED))
        assertTrue(forcesMaxBrightness(TimerState.FINISHED))
    }

    @Test fun `the two rules agree on every state except the gun`() {
        // Guards the divergence from both directions: exactly one state may differ, and it is
        // FINISHED. A new TimerState arriving with a copy-pasted classification shows up here.
        val differing = TimerState.entries.filter { keepsScreenOn(it) != forcesMaxBrightness(it) }
        assertEquals(listOf(TimerState.FINISHED), differing)
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
