package com.racetimer.wear

import com.racetimer.shared.CueVoice
import com.racetimer.shared.SignalPattern

/**
 * The on/off boundaries of a blast, in milliseconds.
 *
 * These define the *shape* of a cue for both of its channels: [HapticManager] builds its vibration
 * waveform from them and [ToneManager] schedules its tones on them, so a three-long cue is three
 * buzzes against three tones, aligned. They live here rather than inside either manager because
 * the shape is only right while both agree on it — tuning one in isolation is exactly how the two
 * drift apart.
 *
 * The same reasoning covers [CueVoice.SYNC]: its lighter, quicker shape is defined here once so the
 * buzz and the tone stay the same length as each other, and so [durationMs] reports that length
 * rather than a blast's.
 */
internal object CueTiming {

    /** Sound and buzz time of one long blast. */
    const val LONG_ON = 500L

    /** Silence after a long blast, before the next blast of the same cue. */
    const val LONG_OFF = 250L

    /** Sound and buzz time of one short blast. */
    const val SHORT_ON = 150L

    /** Silence after a short blast. */
    const val SHORT_OFF = 150L

    /**
     * Sound and buzz time of one [CueVoice.SYNC] tick.
     *
     * Well under [SHORT_ON] on purpose: brevity is most of what separates a tick from a signal by
     * feel, and ten of these fire at one-second spacing where a full short blast would sprawl.
     */
    const val SYNC_ON = 60L

    /** Silence after a sync tick. */
    const val SYNC_OFF = 60L

    /**
     * Vibration strength of a sync tick, against 255 for a long blast and 200 for a short one.
     *
     * The other half of what makes a tick feel unlike a signal on a muted watch — a sailor should be
     * able to tell the two apart with the speaker off, which amplitude alone can carry.
     */
    const val SYNC_AMPLITUDE = 110

    /** Buzzes in the legacy gun triple-buzz, used by cues that state no [SignalPattern.sustainedMs]. */
    const val GUN_REPEAT = 3

    /** Sound/silence of one blast of [pattern], picked by its [SignalPattern.voice]. */
    fun onMs(pattern: SignalPattern, long: Boolean): Long = when (pattern.voice) {
        CueVoice.SYNC -> SYNC_ON
        CueVoice.BLAST -> if (long) LONG_ON else SHORT_ON
    }

    /** Silence after one blast of [pattern]. Pairs with [onMs]. */
    fun offMs(pattern: SignalPattern, long: Boolean): Long = when (pattern.voice) {
        CueVoice.SYNC -> SYNC_OFF
        CueVoice.BLAST -> if (long) LONG_OFF else SHORT_OFF
    }

    /**
     * How long a cue occupies the wrist and the speaker, trailing silence included.
     *
     * Returns the longer of the two channels where they differ, so a caller sizing a teardown delay
     * off this value cannot cut either one short. [isGun] matters only for a cue with no
     * [SignalPattern.sustainedMs], whose vibration is the triple-buzz rather than the pattern.
     *
     * Reads [SignalPattern.voice] through [onMs]/[offMs] rather than assuming blast timings: a sync
     * tick is a quarter the length of a short blast, and reporting it as a blast would overstate
     * every sync cue to both callers that depend on this — the gap check in the sequence tests and
     * the audio teardown delay in [ToneManager].
     */
    fun durationMs(pattern: SignalPattern, isGun: Boolean = false): Long = when {
        pattern.sustainedMs > 0L -> pattern.sustainedMs
        isGun -> GUN_REPEAT * (LONG_ON + LONG_OFF)
        else ->
            pattern.longBlasts * (onMs(pattern, long = true) + offMs(pattern, long = true)) +
                pattern.shortBlasts * (onMs(pattern, long = false) + offMs(pattern, long = false))
    }
}
