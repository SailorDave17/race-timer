package com.racetimer.wear

import com.racetimer.shared.SignalPattern

/**
 * The on/off boundaries of a blast, in milliseconds.
 *
 * These define the *shape* of a cue for both of its channels: [HapticManager] builds its vibration
 * waveform from them and [ToneManager] schedules its tones on them, so a three-long cue is three
 * buzzes against three tones, aligned. They live here rather than inside either manager because
 * the shape is only right while both agree on it — tuning one in isolation is exactly how the two
 * drift apart.
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

    /** Buzzes in the legacy gun triple-buzz, used by cues that state no [SignalPattern.sustainedMs]. */
    const val GUN_REPEAT = 3

    /**
     * How long a cue occupies the wrist and the speaker, trailing silence included.
     *
     * Returns the longer of the two channels where they differ, so a caller sizing a teardown delay
     * off this value cannot cut either one short. [isGun] matters only for a cue with no
     * [SignalPattern.sustainedMs], whose vibration is the triple-buzz rather than the pattern.
     */
    fun durationMs(pattern: SignalPattern, isGun: Boolean = false): Long = when {
        pattern.sustainedMs > 0L -> pattern.sustainedMs
        isGun -> GUN_REPEAT * (LONG_ON + LONG_OFF)
        else -> pattern.longBlasts * (LONG_ON + LONG_OFF) + pattern.shortBlasts * (SHORT_ON + SHORT_OFF)
    }
}
