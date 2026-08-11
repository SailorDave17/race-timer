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

    // --- Timbre, which is a separate contract from length ----------------------
    //
    // Everything above measures *how long* a cue sounds. Nothing measured *what it sounds like*, and
    // the warble is a deliberate, load-bearing property: #61 reproduced `TONE_CDMA_HIGH_L`'s own
    // alternation on purpose, so that a fix about length did not also change the sound and put two
    // variables into one on-water judgement. Found 2026-08-11 by mutating
    // `CueWaveform.BLAST_WARBLE_SEGMENT_MS` from 25 to 50 and watching **nothing** go red.
    //
    // The expected values here are deliberately literals rather than reads of `CueWaveform`'s own
    // constants. They come from the AOSP CDMA tone descriptor table, which is an external source —
    // and a test taking its expectation from the constant under test passes whatever that constant
    // says, which is exactly how this property went unguarded in the first place.

    /**
     * Sample indices where [pcm] crosses zero, over the half-open window `[from, to)`.
     *
     * Frequency is measured from crossing spacing rather than by transform, because the quantity
     * under test is *when the frequency changes* and a windowed transform smears precisely that.
     */
    private fun zeroCrossings(pcm: ShortArray, from: Int, to: Int): List<Int> {
        val crossings = mutableListOf<Int>()
        for (i in (from + 1) until minOf(to, pcm.size)) {
            val a = pcm[i - 1].toInt()
            val b = pcm[i].toInt()
            if ((a <= 0 && b > 0) || (a >= 0 && b < 0)) crossings += i
        }
        return crossings
    }

    /**
     * The tone's frequency in Hz at each crossing, taken from the spacing to a later one.
     *
     * A half-cycle per crossing, so `f = rate * window / (2 * span)`. Smoothed over four gaps
     * because a single gap is a whole number of samples and quantises to roughly 8% at these
     * pitches — the same size as the difference being detected.
     */
    private fun frequencyTrace(
        pcm: ShortArray,
        from: Int,
        to: Int,
        sampleRateHz: Int,
    ): List<Pair<Int, Double>> {
        val crossings = zeroCrossings(pcm, from, to)
        val window = 4
        if (crossings.size <= window) return emptyList()
        return (0..(crossings.size - window - 1)).map { i ->
            val span = crossings[i + window] - crossings[i]
            crossings[i] to (sampleRateHz.toDouble() * window / (2.0 * span))
        }
    }

    @Test fun `a long blast warbles between two partials rather than holding one`() {
        val pcm = CueWaveform.render(blast(long = 1), rate48k)
        // Interior only: the ramps at each end are amplitude changes, not pitch ones, and a
        // crossing-spacing measure says nothing useful while the signal is still climbing.
        val trace = frequencyTrace(pcm, rate48k * 20 / 1000, rate48k * 480 / 1000, rate48k)
        assertTrue("expected a usable frequency trace, got ${trace.size} points", trace.size > 200)

        val low = trace.minOf { it.second }
        val high = trace.maxOf { it.second }
        assertTrue(
            "a single-partial blast would have no spread; measured ${low.toInt()}..${high.toInt()} Hz",
            high - low > 150.0,
        )
        // AOSP TONE_CDMA_HIGH_L: 3700 Hz and 4000 Hz. Generous bands, because the claim is which two
        // partials are present, not the resolution of the estimator.
        assertTrue("lower partial should be near 3700 Hz, was ${low.toInt()}", low in 3400.0..3850.0)
        assertTrue("upper partial should be near 4000 Hz, was ${high.toInt()}", high in 3850.0..4300.0)
    }

    @Test fun `the blast warble alternates every 25ms, the CDMA segment length`() {
        val pcm = CueWaveform.render(blast(long = 1), rate48k)
        val fromMs = 20L
        val toMs = 480L
        val trace = frequencyTrace(
            pcm,
            (rate48k * fromMs / 1000).toInt(),
            (rate48k * toMs / 1000).toInt(),
            rate48k,
        )
        assertTrue("expected a usable frequency trace, got ${trace.size} points", trace.size > 200)

        // Each segment boundary is one crossing of the midpoint between the two partials, so the
        // count of crossings over a known span gives the segment length without reading it off the
        // constant under test.
        val midpoint = 3850.0
        var transitions = 0
        var above = trace.first().second > midpoint
        for ((_, hz) in trace) {
            val nowAbove = hz > midpoint
            if (nowAbove != above) {
                transitions++
                above = nowAbove
            }
        }
        assertTrue("expected the blast to alternate at all; counted $transitions", transitions > 0)

        val segmentMs = (toMs - fromMs).toDouble() / transitions
        // 25 ms is the AOSP descriptor value. The band is wide enough for the smoothing lag and for
        // where the window happens to start, and far too narrow to admit the neighbours a mistake
        // would land on: doubling the constant gives 50 ms and halving it 12.5 ms.
        assertTrue(
            "measured warble segment ${"%.1f".format(segmentMs)}ms over $transitions boundaries, expected 25ms",
            segmentMs in 20.0..31.0,
        )
    }

    /**
     * Zero-crossing count per [windowMs] window across `[from, to)`, as a rate in crossings/second.
     *
     * The stationarity instrument for a **dual-tone**. [frequencyTrace]'s gap-spacing estimate beats
     * between the two partials of a DTMF pair and reads as spread on a perfectly stationary tone —
     * measured here first: the tick and the gun both failed a spread assertion built on it. Counting
     * crossings over a whole window averages the beat out, so a stationary tone gives near-constant
     * windows while the blast's warble still shows, because its windows alternate between the two
     * partials' rates.
     */
    private fun windowedCrossingRates(
        pcm: ShortArray,
        from: Int,
        to: Int,
        sampleRateHz: Int,
        windowMs: Int,
    ): List<Double> {
        val windowSamples = sampleRateHz * windowMs / 1000
        val rates = mutableListOf<Double>()
        var start = from
        while (start + windowSamples <= minOf(to, pcm.size)) {
            val count = zeroCrossings(pcm, start, start + windowSamples).size
            rates += count * 1000.0 / windowMs
            start += windowSamples
        }
        return rates
    }

    /**
     * Expected mean zero-crossing rate of an equal-amplitude two-tone sum: twice the *higher*
     * partial, slightly under.
     *
     * Empirical, and the instrument's own calibration history is the argument for it. A
     * stationarity assertion failed on correct audio first (a dual-tone's beat envelope swings
     * individual windows by 240-300 crossings/s), then Rice's formula — 2× the RMS frequency —
     * under-predicted by ~20%, because it assumes noise and this is a deterministic pair whose
     * higher partial dominates the crossings. *Measured*: the tick reads 2375/s against 2×1209 =
     * 2418 (98%), the gun 3173/s against 2×1633 = 3266 (97%). The band is ±10% around 2×f_high;
     * every wrong answer is far outside it — a warble leak reads ~7700/s, and a swapped pair moves
     * f_high itself.
     */
    private fun dualToneCrossingRate(fHigh: Double): Double = 2.0 * fHigh

    @Test fun `a sync tick keeps its DTMF pair, not the blast's warble`() {
        // The tick is TONE_DTMF_1 — 697 + 1209 Hz, a fixed pair. If the blast's warble leaked into
        // it a sailor would lose one of the two things separating a tick from a signal, and every
        // length assertion in this file would still pass. The expectation is computed from the
        // published DTMF frequencies, an external source, never from CueWaveform's own constants.
        val pcm = CueWaveform.render(sync(short = 1), rate48k)
        val onSamples = (rate48k * CueTiming.SYNC_ON / 1000).toInt()
        val rates = windowedCrossingRates(pcm, onSamples / 6, onSamples * 5 / 6, rate48k, windowMs = 10)
        assertTrue("expected usable windows, got ${rates.size}", rates.size >= 3)

        val mean = rates.sum() / rates.size
        val expected = dualToneCrossingRate(1209.0) // 2418/s; measured 2375
        assertTrue(
            "tick crossing rate ${mean.toInt()}/s, expected ~${expected.toInt()}/s +/-10%",
            mean in (expected * 0.90)..(expected * 1.10),
        )
    }

    @Test fun `the gun keeps its DTMF pair, not the blast's warble`() {
        // The one cue a sailor starts on, and the more expensive to get wrong: three seconds of
        // unintended warble is the gun sounding like a signal repeated. TONE_DTMF_D: 941 + 1633 Hz.
        val pcm = CueWaveform.render(blast(sustained = 3_000L), rate48k)
        val rates = windowedCrossingRates(
            pcm,
            rate48k * 100 / 1000,
            rate48k * 2_900 / 1000,
            rate48k,
            windowMs = 25,
        )
        assertTrue("expected usable windows, got ${rates.size}", rates.size > 50)

        val mean = rates.sum() / rates.size
        val expected = dualToneCrossingRate(1633.0) // 3266/s; measured 3173
        assertTrue(
            "gun crossing rate ${mean.toInt()}/s, expected ~${expected.toInt()}/s +/-10%",
            mean in (expected * 0.90)..(expected * 1.10),
        )
    }

    @Test fun `the same instrument does see the blast warble, so a quiet tick means something`() {
        // Positive control for the two stationarity cases above: an instrument that reported *no*
        // swing on the warbling blast would also report no swing on a broken tick, and both would
        // pass. The blast's windows alternate between the partials' rates, so its swing must be
        // large where the tick's and gun's are small — same window size as the tick's case.
        val pcm = CueWaveform.render(blast(long = 1), rate48k)
        val rates = windowedCrossingRates(
            pcm,
            rate48k * 25 / 1000,
            rate48k * 475 / 1000,
            rate48k,
            windowMs = 10,
        )
        assertTrue("expected usable windows, got ${rates.size}", rates.size > 20)

        val swing = rates.max() - rates.min()
        assertTrue(
            "the blast should swing between ~7400 and ~8000 crossings/s; measured ${swing.toInt()}/s",
            swing > 300.0,
        )
    }
}
