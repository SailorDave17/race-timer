package com.racetimer.wear

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.racetimer.shared.CueTiming
import com.racetimer.shared.CueVoice
import com.racetimer.shared.SignalPattern

/**
 * Manages haptic feedback for race cues.
 *
 * Each [SignalPattern] maps to a distinct vibration sequence:
 * - Sustained   → one unbroken buzz for [SignalPattern.sustainedMs]
 * - Long blast  → firm buzz  ([CueTiming.LONG_ON] on, [CueTiming.LONG_OFF] off per blast)
 * - Short blast → quick tap  ([CueTiming.SHORT_ON] on, [CueTiming.SHORT_OFF] off per blast)
 * - Sync tick   → light, quick tap at [CueTiming.SYNC_AMPLITUDE], for [CueVoice.SYNC] cues
 * - Gun (multi) → rapid triple buzz, for gun cues that state no sustained duration
 *
 * Blast boundaries come from [CueTiming] because [ToneManager] plays a cue's tones on the same
 * ones; see that object for why they are not constants here.
 *
 * The patterns are composed off the monotonic clock so multiple blasts don't drift.
 */
class HapticManager(context: Context) {

    private val vibrator: Vibrator = systemVibrator(context)

    /**
     * Play the haptic pattern for [pattern].
     *
     * A pattern carrying [SignalPattern.sustainedMs] wins over everything else, [isGun] included.
     * Otherwise, if [isGun] is true, override with the gun triple-buzz regardless of pattern.
     */
    fun play(pattern: SignalPattern, isGun: Boolean = false) {
        if (!vibrator.hasVibrator()) return

        val timings = mutableListOf<Long>()
        val amplitudes = mutableListOf<Int>()

        if (pattern.sustainedMs > 0L) {
            // One unbroken buzz. Ahead of the isGun branch deliberately: a cue that states its own
            // sustained length means it, and the triple-buzz behind this keeps every gun cue that
            // states none — club's is the only one left — on exactly the behaviour it has today.
            timings += 0L; amplitudes += VibrationEffect.DEFAULT_AMPLITUDE   // lead silence
            timings += pattern.sustainedMs; amplitudes += 255
        } else if (isGun) {
            // Gun: 3 rapid long buzzes
            repeat(CueTiming.GUN_REPEAT) {
                timings += 0L; amplitudes += VibrationEffect.DEFAULT_AMPLITUDE   // lead silence
                timings += CueTiming.LONG_ON; amplitudes += 255
                timings += CueTiming.LONG_OFF; amplitudes += 0
            }
        } else {
            // Each voice buzzes at its own strength, and the strength comes from CueTiming along
            // with the timing — so the buzz and the tone stay the same length as each other, and a
            // wearer can tell the three apart with the speaker off. A sync tick is lighter and
            // quicker than any blast; a prompt is quicker still and at full strength, because it is
            // the one cue that has to be *acted on* rather than merely heard.
            //
            // Read through CueTiming.amplitude rather than a `voice == SYNC` boolean: a boolean
            // silently absorbs a third voice into the else branch, which is exactly how a new
            // enum value ships doing nothing (#59 lost a build to this class of thing).
            val strongAmplitude = CueTiming.amplitude(pattern, long = true)
            val shortAmplitude = CueTiming.amplitude(pattern, long = false)

            // Long blasts first
            repeat(pattern.longBlasts) {
                timings += 0L; amplitudes += VibrationEffect.DEFAULT_AMPLITUDE
                timings += CueTiming.onMs(pattern, long = true); amplitudes += strongAmplitude
                timings += CueTiming.offMs(pattern, long = true); amplitudes += 0
            }
            // Then short blasts
            repeat(pattern.shortBlasts) {
                timings += 0L; amplitudes += VibrationEffect.DEFAULT_AMPLITUDE
                timings += CueTiming.onMs(pattern, long = false); amplitudes += shortAmplitude
                timings += CueTiming.offMs(pattern, long = false); amplitudes += 0
            }
        }

        if (timings.isEmpty()) return

        vibrator.vibrate(
            VibrationEffect.createWaveform(timings.toLongArray(), amplitudes.toIntArray(), -1)
        )
    }

    /** Short distinct haptic for sync feedback. */
    fun playSync() {
        if (!vibrator.hasVibrator()) return
        vibrator.vibrate(VibrationEffect.createOneShot(SYNC_FEEDBACK_MS, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    /** Cancel any ongoing haptic. */
    fun cancel() {
        vibrator.cancel()
    }

    companion object {
        /** Confirmation buzz for a sync tap. Not a cue, so it has no [CueTiming] shape to follow. */
        private const val SYNC_FEEDBACK_MS = 80L

        /**
         * The system vibrator, resolved the one way that covers this app's whole minSdk range.
         *
         * Public and here rather than inline in the constructor since #13, which needs the same
         * lookup from `MainActivity` to warn a sailor that a cue will never reach their wrist. Two
         * copies of an API-level branch is how one of them silently stops matching the other — the
         * same argument that moved the message colours into `shared/MessageContrast.kt`.
         */
        fun systemVibrator(context: Context): Vibrator =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }

        /**
         * Whether this watch can vibrate at all (#13).
         *
         * Answers a *hardware* question, and only that one. It says nothing about whether a cue will
         * actually be felt — Do Not Disturb drops multi-pulse effects on a watch whose vibrator is
         * present and working, which is #144 and a different warning entirely. Conflating the two
         * would put "No haptics — watch the screen" on screen for a condition this cannot see.
         */
        fun hasVibrator(context: Context): Boolean = systemVibrator(context).hasVibrator()
    }
}
