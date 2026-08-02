package com.racetimer.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers [CueWaveform], the audible half of the cue contract.
 *
 * These tests exist to hold the thing #61 was about. `CueTimingTest` asserts that both channels read
 * the same constants; nothing asserted that the audio channel then *honoured* them, and for the
 * whole life of the `ToneGenerator` path it did not — a 60 ms tick sounded for 80 ms, a 150 ms blast
 * for 160 ms, and a 500 ms blast for 512 ms or 520 ms depending on the run.
 *
 * So the assertions here are deliberately made against [CueTiming] rather than against literals:
 * they read the sounded length back out of the rendered buffer and check it is the number the
 * vibration waveform is built from. A change that moves one channel without the other fails here.
 *
 * Lengths are measured in milliseconds off the buffer rather than in samples, because milliseconds
 * are what [CueTiming] is written in and what a defect would be reported in.
 */
class CueWaveformTest {

    /** The output rate of the SM-R925U this app is developed against. */
    private val rate48k = 48_000

    /** A rate where a millisecond is *not* a whole number of samples — 44.1 of them. */
    private val rate44k1 = 44_100

    private fun blast(long: Int = 0, short: Int = 0, sustained: Long = 0L) =
        SignalPattern(longBlasts = long, shortBlasts = short, sustainedMs = sustained)

    private fun sync(short: Int) = SignalPattern(shortBlasts = short, voice = CueVoice.SYNC)

    // --- Reading a cue back out of its buffer ----------------------------------

    /** A stretch of the rendered buffer that is either sounding or silent, measured in ms. */
    private data class Run(val sounding: Boolean, val ms: Long)

    /**
     * Split [pcm] into alternating sounding and silent runs, at millisecond resolution.
     *
     * Windowed rather than sample-by-sample because a tone is a sine: it passes through zero every
     * few samples, so a per-sample "is this zero" test would report hundreds of runs inside one
     * blast. A window is sounding if anything in it is meaningfully above zero.
     *
     * The threshold is well below the [CueWaveform] output level and well above nothing, so it is
     * not sensitive to where in its ramp a tone happens to be at a window boundary.
     */
    private fun runsOf(pcm: ShortArray, sampleRateHz: Int): List<Run> {
        val windowSamples = sampleRateHz / 1000
        val threshold = Short.MAX_VALUE * 0.05
        val runs = mutableListOf<Run>()
        var windowStart = 0
        while (windowStart < pcm.size) {
            val windowEnd = minOf(windowStart + windowSamples, pcm.size)
            var peak = 0
            for (i in windowStart until windowEnd) {
                val magnitude = if (pcm[i] < 0) -pcm[i].toInt() else pcm[i].toInt()
                if (magnitude > peak) peak = magnitude
            }
            val sounding = peak > threshold
            val last = runs.lastOrNull()
            if (last != null && last.sounding == sounding) {
                runs[runs.lastIndex] = last.copy(ms = last.ms + 1)
            } else {
                runs += Run(sounding, 1)
            }
            windowStart = windowEnd
        }
        return runs
    }

    /** Sounded lengths in order, ignoring the silences between them. */
    private fun soundedMs(pcm: ShortArray, sampleRateHz: Int): List<Long> =
        runsOf(pcm, sampleRateHz).filter { it.sounding }.map { it.ms }

    /** Silent lengths in order, ignoring the tones around them. */
    private fun silentMs(pcm: ShortArray, sampleRateHz: Int): List<Long> =
        runsOf(pcm, sampleRateHz).filter { !it.sounding }.map { it.ms }

    /**
     * Assert a measured length is [expectedMs], allowing one millisecond either way.
     *
     * The slack is for the window boundary and for the ramp, whose first and last samples are
     * exactly zero and so read as silence. It is three orders of magnitude tighter than the defect
     * this file guards: the old path was out by 10-20 ms, and the sync tick by 20.
     */
    private fun assertMs(message: String, expectedMs: Long, actualMs: Long) {
        assertTrue(
            "$message: expected ${expectedMs}ms +/-1, was ${actualMs}ms",
            actualMs in (expectedMs - 1)..(expectedMs + 1),
        )
    }

    // --- The defect #61 was filed for ------------------------------------------

    @Test fun `a short blast sounds for exactly SHORT_ON, not the 160ms ToneGenerator delivered`() {
        val pcm = CueWaveform.render(blast(short = 1), rate48k)
        assertEquals(1, soundedMs(pcm, rate48k).size)
        assertMs("short blast", CueTiming.SHORT_ON, soundedMs(pcm, rate48k).single())
    }

    @Test fun `a long blast sounds for exactly LONG_ON, not the 512-or-520ms ToneGenerator delivered`() {
        // The 500 ms request is the one that proved the overshoot could not be padded away: the
        // same call returned 512 ms five times and 520 ms once inside a single race. A rendered
        // buffer has no such freedom - the length is the sample count.
        val pcm = CueWaveform.render(blast(long = 1), rate48k)
        assertMs("long blast", CueTiming.LONG_ON, soundedMs(pcm, rate48k).single())
    }

    @Test fun `a sync tick sounds for exactly SYNC_ON, closing the worst overshoot in the app`() {
        // 60 ms requested delivered 80 ms - a third longer than the buzz beside it.
        val pcm = CueWaveform.render(sync(short = 1), rate48k)
        assertMs("sync tick", CueTiming.SYNC_ON, soundedMs(pcm, rate48k).single())
    }

    @Test fun `silence between blasts is the full CueTiming gap, not a tone's overrun`() {
        // The other half of the same defect, and the half that is easy to forget: a tone running
        // long eats the silence after it, so SHORT_OFF = 150 was 140 ms of actual quiet. The gap
        // inside a cue is load-bearing - the doubled ticks of the final five seconds only read as
        // pairs while the gap within a pair stays clearly shorter than the gap between them.
        val pcm = CueWaveform.render(blast(short = 3), rate48k)
        val silences = silentMs(pcm, rate48k)
        assertEquals("two gaps between three blasts, and the cue's own trailing silence", 3, silences.size)
        for ((index, silence) in silences.withIndex()) {
            assertMs("gap $index", CueTiming.SHORT_OFF, silence)
        }
    }

    // --- Cue shape --------------------------------------------------------------

    @Test fun `a cue renders one tone per blast, long ones first`() {
        // Same order as HapticManager's two loops. If these ever disagree the channels are
        // describing different signals, which is the drift CueTiming exists to prevent.
        val pcm = CueWaveform.render(blast(long = 1, short = 3), rate48k)
        val sounded = soundedMs(pcm, rate48k)
        assertEquals(4, sounded.size)
        assertMs("the long blast leads", CueTiming.LONG_ON, sounded.first())
        for (index in 1..3) {
            assertMs("short blast $index", CueTiming.SHORT_ON, sounded[index])
        }
    }

    @Test fun `a sustained cue is one unbroken tone of its stated length`() {
        // The gun. Three seconds, and the one cue a sailor most needs to hear in full.
        val pattern = blast(sustained = 3_000L)
        val pcm = CueWaveform.render(pattern, rate48k)
        val sounded = soundedMs(pcm, rate48k)
        assertEquals("unbroken", 1, sounded.size)
        assertMs("gun", 3_000L, sounded.single())
    }

    @Test fun `a rendered cue is exactly as long as CueTiming says it is`() {
        val patterns = listOf(
            blast(long = 1),
            blast(short = 1),
            blast(long = 3),
            blast(short = 3),
            blast(long = 1, short = 3),
            blast(sustained = 3_000L),
            sync(short = 1),
            sync(short = 2),
        )
        for (pattern in patterns) {
            val expectedSamples = CueWaveform.samplesAt(CueTiming.durationMs(pattern), rate48k)
            assertEquals(
                "$pattern",
                expectedSamples,
                CueWaveform.render(pattern, rate48k).size,
            )
        }
    }

    @Test fun `an empty pattern renders nothing rather than throwing`() {
        assertEquals(0, CueWaveform.render(blast(), rate48k).size)
    }

    // --- Rates where a millisecond is not a whole number of samples -------------

    @Test fun `blast boundaries do not drift down a cue at 44 point 1 kHz`() {
        // 44.1 samples per millisecond. Rounding each blast independently and summing would let the
        // error accumulate, so a late blast in a long cue would sit a millisecond off the buzz
        // beside it while the first one looked fine. Every boundary is measured from the start of
        // the cue instead, so this holds at the end as well as the beginning.
        val pcm = CueWaveform.render(blast(short = 3), rate44k1)
        for ((index, sounded) in soundedMs(pcm, rate44k1).withIndex()) {
            assertMs("short blast $index at 44.1 kHz", CueTiming.SHORT_ON, sounded)
        }
    }

    @Test fun `total length at 44 point 1 kHz is within one sample of the exact duration`() {
        val pattern = blast(long = 1, short = 3)
        val exactSamples = CueTiming.durationMs(pattern) * rate44k1 / 1000.0
        val rendered = CueWaveform.render(pattern, rate44k1).size
        assertTrue(
            "rendered $rendered samples against an exact $exactSamples",
            kotlin.math.abs(rendered - exactSamples) <= 1.0,
        )
    }

    // --- Signal quality ---------------------------------------------------------

    @Test fun `a tone never clips`() {
        // Two summed partials at full scale would wrap around Short and turn the gun into a buzz.
        val pcm = CueWaveform.render(blast(sustained = 3_000L), rate48k)
        var peak = 0
        for (sample in pcm) {
            val magnitude = if (sample < 0) -sample.toInt() else sample.toInt()
            if (magnitude > peak) peak = magnitude
        }
        assertTrue("peak $peak should approach full scale", peak > Short.MAX_VALUE * 0.7)
        assertTrue("peak $peak should stay inside it", peak <= Short.MAX_VALUE.toInt())
    }

    @Test fun `every tone starts and ends at silence`() {
        // The ramp. A tone that begins mid-cycle is a step change, and a step change is a click -
        // which would be a new audible defect shipped by the fix for an inaudible one.
        val pcm = CueWaveform.render(blast(short = 2), rate48k)
        assertEquals("first sample", 0, pcm.first().toInt())
        assertEquals("last sample of the final tone", 0, pcm[CueWaveform.samplesAt(CueTiming.SHORT_ON, rate48k) - 1].toInt())
    }

    @Test fun `a beep is exactly as long as it was asked for`() {
        val pcm = CueWaveform.renderBeep(400L, rate48k)
        assertEquals(CueWaveform.samplesAt(400L, rate48k), pcm.size)
        assertMs("beep", 400L, soundedMs(pcm, rate48k).single())
    }
}
