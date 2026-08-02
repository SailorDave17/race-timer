package com.racetimer.shared

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Renders a whole cue — every tone and every silence between them — into one PCM buffer.
 *
 * ### Why this exists
 *
 * [CueTiming] states the shape of a cue for both of its channels, and until #61 only one of them
 * kept it. `VibrationEffect.createWaveform` honours its `timings` array exactly, because the whole
 * pattern is handed over in a single call and the vibrator service clocks it. `ToneGenerator`'s
 * duration is a *cap* the platform overshoots: measured on an SM-R925U, 60 ms delivered 80 ms
 * (+33%), 150 ms delivered 160 ms, and 500 ms delivered **512 ms five times and 520 ms once in the
 * same race**. So the tone ran past its buzz on every blast, and the silence after it was
 * correspondingly short — `SHORT_OFF = 150` was 140 ms of actual silence.
 *
 * Neither cheap fix survived measurement. Swapping tone family changed nothing (the CDMA and DTMF
 * families overshot a 150 ms request identically), and padding the request cannot work against a
 * call that returns two different lengths from identical arguments.
 *
 * What removes the class of bug is giving audio the same submission model the vibration already has:
 * build the entire cue up front and hand it over in one piece. A PCM buffer is exact by
 * construction — a sample is a sample — so a blast is as long as the number of samples in it, and
 * the platform has no duration to round.
 *
 * ### Why the numbers are here rather than in the wear module
 *
 * This is arithmetic over [SignalPattern] and [CueTiming] with no Android SDK anywhere in it, and it
 * is the half of the cue contract that had no test. `wear/` has no test source set (#64), so logic
 * that lives there cannot be guarded; `CueTimingTest` exists precisely because the same reasoning
 * applied to the constants. Keeping the renderer beside [CueTiming] is also the point structurally:
 * the two channels drifted apart while the numbers driving them lived in different modules.
 *
 * The wear module keeps only the parts that need a device — opening an `AudioTrack` at the output's
 * native rate, and playing the buffer this produces.
 */
object CueWaveform {

    /**
     * The alternating pair that makes a blast, and the segment each one holds.
     *
     * These are `ToneGenerator.TONE_CDMA_HIGH_L`'s own numbers, read off the AOSP tone descriptor
     * table: 25 ms at 3700 Hz, 25 ms at 4000 Hz, alternating. Reproducing them rather than choosing
     * a fresh tone keeps the blast sounding like the blast a sailor has already been trained on
     * across three field-test sessions — the fix is about *length*, and changing the sound as well
     * would put two variables into one on-water judgement.
     *
     * The warble is most of what makes this tone carry: two closely spaced high partials beat
     * against each other in a way a single sine does not, which is what "high and piercing" was
     * describing.
     */
    const val BLAST_WARBLE_SEGMENT_MS = 25L

    /** Sine partials of a blast, alternating every [BLAST_WARBLE_SEGMENT_MS]. */
    private val BLAST_SEGMENTS = listOf(doubleArrayOf(3700.0), doubleArrayOf(4000.0))

    /**
     * Sine partials of a [CueVoice.SYNC] tick — `TONE_DTMF_1`, the lowest DTMF pair.
     *
     * Chosen for pitch distance from the blast rather than timbre, and kept at exactly the pair the
     * tick has always used: a sailor tells a tick from a signal by how low and how short it is.
     */
    private val SYNC_SEGMENTS = listOf(doubleArrayOf(697.0, 1209.0))

    /** Sine partials of a sustained cue — `TONE_DTMF_D`, the pair the gun has always sounded. */
    private val SUSTAINED_SEGMENTS = listOf(doubleArrayOf(941.0, 1633.0))

    /**
     * Peak amplitude as a fraction of full scale, before it is split across a tone's partials.
     *
     * Just under full scale so summing two partials cannot clip. Audibility outdoors is the whole
     * requirement for this app, so there is no headroom to spare beyond that.
     */
    private const val PEAK = 0.9

    /**
     * Fade applied at each end of a tone, inside its own length.
     *
     * A tone that starts and stops at a non-zero sample is a step change, and a step change is a
     * click — which would be a *new* audible defect introduced by the fix. Two milliseconds is
     * enough to remove it and short enough to stay imperceptible against a 60 ms tick, the shortest
     * thing here. Taken out of the tone's own samples rather than added around them, so the cue's
     * total length is still exactly what [CueTiming] says.
     */
    private const val RAMP_MS = 2L

    /**
     * One cycle of a sine, sampled [TABLE_SIZE] times, with phase tracked as a fixed-point integer.
     *
     * This is not a micro-optimisation. The first version of this renderer called `sin()` and did a
     * floating-point division per partial per sample, and **measured on an SM-R925U it took 588-861
     * ms to render a 400 ms cue** — so the cue started the better part of a second after its buzz,
     * which is far worse than the 10-20 ms defect the whole change exists to remove. A watch CPU
     * running an unoptimised debug build interprets that loop, and the cost is not theoretical.
     *
     * A table lookup with an integer phase accumulator does the same job with one array read and one
     * add per partial. 4096 entries puts the quantisation error well below anything audible from a
     * watch speaker, and the fixed-point phase carries continuously across a warble's frequency
     * change so the waveform still has no step in it.
     */
    private const val TABLE_BITS = 12

    private const val TABLE_SIZE = 1 shl TABLE_BITS

    private const val TABLE_MASK = TABLE_SIZE - 1

    /**
     * Fractional bits of the phase accumulator.
     *
     * [TABLE_BITS] + this is 31, so a phase fits an [Int] with the sign bit clear and wraps by
     * masking rather than by overflowing — one cycle of the table, exactly, every time round.
     */
    private const val PHASE_FRACTION_BITS = 19

    private const val PHASE_MASK = (TABLE_SIZE shl PHASE_FRACTION_BITS) - 1

    private val SINE = DoubleArray(TABLE_SIZE) { sin(2.0 * PI * it / TABLE_SIZE) }

    private const val SHORT_MIN = -32768

    private const val SHORT_MAX = 32767

    /**
     * Samples occupied by [ms] at [sampleRateHz], as an offset from the start of a cue.
     *
     * Always call this against a *cumulative* offset rather than summing per-segment sample counts.
     * At 48 kHz a millisecond is exactly 48 samples and it makes no difference, but at 44.1 kHz it
     * is 44.1, and rounding each segment independently would let the error accumulate down a cue
     * until a late blast sat a millisecond off the buzz beside it. Measuring every boundary from the
     * cue's start instead caps the total error at a single sample, wherever the boundary falls.
     */
    fun samplesAt(ms: Long, sampleRateHz: Int): Int = (ms * sampleRateHz / 1000L).toInt()

    /**
     * Render [pattern] as 16-bit mono PCM at [sampleRateHz].
     *
     * The returned buffer is exactly [CueTiming.durationMs] long — trailing silence included, so
     * that a caller which plays it back to back with anything else keeps the gap the cue asks for.
     *
     * Blast boundaries come from [CueTiming.onMs] and [CueTiming.offMs], the same two calls
     * `HapticManager` builds its waveform from. That is the whole point: one set of numbers, read by
     * both channels, and now honoured by both.
     */
    fun render(pattern: SignalPattern, sampleRateHz: Int): ShortArray {
        require(sampleRateHz > 0) { "sampleRateHz must be positive, was $sampleRateHz" }

        val segments = segmentsOf(pattern)
        val totalMs = segments.sumOf { it.onMs + it.offMs }
        val out = ShortArray(samplesAt(totalMs, sampleRateHz))

        var cursorMs = 0L
        for (segment in segments) {
            writeTone(
                out = out,
                fromSample = samplesAt(cursorMs, sampleRateHz),
                toSample = samplesAt(cursorMs + segment.onMs, sampleRateHz),
                tone = segment.tone,
                sampleRateHz = sampleRateHz,
            )
            // Silence needs no writing — the array is already zero — but it still has to be counted,
            // because the next tone's position is measured from the start of the cue.
            cursorMs += segment.onMs + segment.offMs
        }
        return out
    }

    /**
     * Render a standalone feedback beep of [durationMs] — a sync snap, say, which carries no blast
     * pattern and so has no [CueTiming] shape to follow.
     */
    fun renderBeep(durationMs: Long, sampleRateHz: Int): ShortArray {
        require(sampleRateHz > 0) { "sampleRateHz must be positive, was $sampleRateHz" }
        require(durationMs >= 0L) { "durationMs must not be negative, was $durationMs" }

        val out = ShortArray(samplesAt(durationMs, sampleRateHz))
        writeTone(out, 0, out.size, BlastTone, sampleRateHz)
        return out
    }

    // --- Internals ------------------------------------------------------------

    /**
     * A tone's spectral content: [segments] of summed sine partials, each held for [segmentMs]
     * before the next takes over, cycling.
     *
     * One representation covers both shapes in the app. A steady dual tone is a single segment and
     * never advances; a warbling blast is two single-partial segments alternating. Frequency changes
     * carry the running phase across rather than restarting it, which keeps the waveform continuous
     * at a segment boundary — the warble sounds the same and the click does not happen.
     */
    private class ToneSpec(val segmentMs: Long, val segments: List<DoubleArray>)

    private val BlastTone = ToneSpec(BLAST_WARBLE_SEGMENT_MS, BLAST_SEGMENTS)
    private val SyncTone = ToneSpec(0L, SYNC_SEGMENTS)
    private val SustainedTone = ToneSpec(0L, SUSTAINED_SEGMENTS)

    /** One blast of a cue: [onMs] of [tone], then [offMs] of silence. */
    private class Segment(val onMs: Long, val offMs: Long, val tone: ToneSpec)

    /**
     * The blasts of [pattern], in the order they sound.
     *
     * Long blasts before short ones, matching `HapticManager`'s two loops — a `1 long 3 short` cue
     * is one long buzz and then three taps on both channels, and the two orders have to agree or the
     * channels describe different signals.
     */
    private fun segmentsOf(pattern: SignalPattern): List<Segment> {
        if (pattern.sustainedMs > 0L) {
            return listOf(Segment(pattern.sustainedMs, 0L, SustainedTone))
        }
        // The voice picks the tone and the boundaries together. A sync tick is a different pitch on
        // a shorter beat than any blast, which is what stops a sailor hearing it as a signal.
        val tone = when (pattern.voice) {
            CueVoice.SYNC -> SyncTone
            CueVoice.BLAST -> BlastTone
        }
        val segments = mutableListOf<Segment>()
        repeat(pattern.longBlasts) {
            segments += Segment(
                CueTiming.onMs(pattern, long = true),
                CueTiming.offMs(pattern, long = true),
                tone,
            )
        }
        repeat(pattern.shortBlasts) {
            segments += Segment(
                CueTiming.onMs(pattern, long = false),
                CueTiming.offMs(pattern, long = false),
                tone,
            )
        }
        return segments
    }

    /** Write [tone] into `out[fromSample until toSample]`, ramped at both ends. */
    private fun writeTone(
        out: ShortArray,
        fromSample: Int,
        toSample: Int,
        tone: ToneSpec,
        sampleRateHz: Int,
    ) {
        val length = toSample - fromSample
        if (length <= 0) return

        // Never let the two ramps overlap: on a tone shorter than 2 * RAMP_MS they would each want
        // more than half of it, and the tone would be all fade and no body.
        val rampSamples = minOf(samplesAt(RAMP_MS, sampleRateHz), length / 2)
        val partialCount = tone.segments.maxOf { it.size }
        val scale = PEAK / partialCount * Short.MAX_VALUE
        val phases = IntArray(partialCount)
        val segmentSamples =
            if (tone.segmentMs > 0L) samplesAt(tone.segmentMs, sampleRateHz) else 0

        // Phase increments per partial per segment, worked out once rather than per sample, and held
        // in arrays rather than lists so the hot loop below does no interface dispatch.
        val increments = Array(tone.segments.size) { segment ->
            IntArray(tone.segments[segment].size) {
                phaseIncrement(tone.segments[segment][it], sampleRateHz)
            }
        }

        // Written a whole segment at a time, with the envelope applied afterwards, so that the
        // per-sample work is an add, a shift, a table read and a store — and nothing else. The
        // straightforward version of this loop, with a function call for the envelope and a list
        // lookup for the segment, measured **2223 ms to render the three-second gun** on an
        // SM-R925U. That is not a number a cue can absorb.
        var i = 0
        var segmentIndex = 0
        while (i < length) {
            val increment = increments[segmentIndex]
            val runEnd = if (segmentSamples > 0) minOf(i + segmentSamples, length) else length
            if (increment.size == 1) {
                // A blast: one partial, alternating frequency. The common case, and the longest.
                val step = increment[0]
                var phase = phases[0]
                var j = i
                while (j < runEnd) {
                    phase = (phase + step) and PHASE_MASK
                    out[fromSample + j] =
                        (SINE[(phase shr PHASE_FRACTION_BITS) and TABLE_MASK] * scale).toInt()
                            .coerceIn(SHORT_MIN, SHORT_MAX)
                            .toShort()
                    j++
                }
                phases[0] = phase
            } else {
                var j = i
                while (j < runEnd) {
                    var value = 0.0
                    for (partial in increment.indices) {
                        val phase = (phases[partial] + increment[partial]) and PHASE_MASK
                        phases[partial] = phase
                        value += SINE[(phase shr PHASE_FRACTION_BITS) and TABLE_MASK]
                    }
                    // [PEAK] keeps the summed partials inside full scale, so the clamp guards against
                    // a future change to it rather than firing — but silent wrap-around would turn
                    // the gun into a buzz, which is not worth saving a comparison over.
                    out[fromSample + j] = (value * scale).toInt()
                        .coerceIn(SHORT_MIN, SHORT_MAX)
                        .toShort()
                    j++
                }
            }
            i = runEnd
            // The warble steps to its other frequency. The phase accumulators carry straight over,
            // so the waveform is continuous across the step and there is nothing to click.
            segmentIndex = (segmentIndex + 1) % increments.size
        }

        applyRamp(out, fromSample, length, rampSamples)
    }

    /**
     * Fade the first and last [rampSamples] of a tone in and out.
     *
     * A second pass over a few hundred samples rather than a branch on every one of tens of
     * thousands. Both ends reach exactly zero, which is what makes the join to silence silent — and
     * what a test reading blast lengths back out of a buffer has to allow for, since the first and
     * last sample of a tone then read as silence.
     */
    private fun applyRamp(out: ShortArray, fromSample: Int, length: Int, rampSamples: Int) {
        if (rampSamples <= 0) return
        for (k in 0 until rampSamples) {
            val gain = 0.5 * (1.0 - cos(PI * k / rampSamples))
            val head = fromSample + k
            val tail = fromSample + length - 1 - k
            out[head] = (out[head] * gain).toInt().toShort()
            out[tail] = (out[tail] * gain).toInt().toShort()
        }
    }

    /**
     * Fixed-point phase step for [freqHz] at [sampleRateHz], in table entries scaled by
     * `2^PHASE_FRACTION_BITS`.
     */
    private fun phaseIncrement(freqHz: Double, sampleRateHz: Int): Int =
        (freqHz / sampleRateHz * TABLE_SIZE * (1 shl PHASE_FRACTION_BITS)).roundToInt()

}
