package com.racetimer.wear

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.racetimer.shared.SignalPattern

/**
 * Manages haptic feedback for race cues.
 *
 * Each [SignalPattern] maps to a distinct vibration sequence:
 * - Sustained   → one unbroken buzz for [SignalPattern.sustainedMs]
 * - Long blast  → firm buzz  ([CueTiming.LONG_ON] on, [CueTiming.LONG_OFF] off per blast)
 * - Short blast → quick tap  ([CueTiming.SHORT_ON] on, [CueTiming.SHORT_OFF] off per blast)
 * - Gun (multi) → rapid triple buzz, for gun cues that state no sustained duration
 *
 * Blast boundaries come from [CueTiming] because [ToneManager] plays a cue's tones on the same
 * ones; see that object for why they are not constants here.
 *
 * The patterns are composed off the monotonic clock so multiple blasts don't drift.
 */
class HapticManager(context: Context) {

    private val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
        vm.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    // --- Timing constants (ms) ---
    private val LONG_ON = CueTiming.LONG_ON
    private val LONG_OFF = CueTiming.LONG_OFF
    private val SHORT_ON = CueTiming.SHORT_ON
    private val SHORT_OFF = CueTiming.SHORT_OFF
    private val GUN_REPEAT = CueTiming.GUN_REPEAT  // triple-buzz for the gun

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
            // states none — usSailing's and club's — on exactly the behaviour it has today.
            timings += 0L; amplitudes += VibrationEffect.DEFAULT_AMPLITUDE   // lead silence
            timings += pattern.sustainedMs; amplitudes += 255
        } else if (isGun) {
            // Gun: 3 rapid long buzzes
            repeat(GUN_REPEAT) {
                timings += 0L; amplitudes += VibrationEffect.DEFAULT_AMPLITUDE   // lead silence
                timings += LONG_ON; amplitudes += 255
                timings += LONG_OFF; amplitudes += 0
            }
        } else {
            // Long blasts first
            repeat(pattern.longBlasts) {
                timings += 0L; amplitudes += VibrationEffect.DEFAULT_AMPLITUDE
                timings += LONG_ON; amplitudes += 255
                timings += LONG_OFF; amplitudes += 0
            }
            // Then short blasts
            repeat(pattern.shortBlasts) {
                timings += 0L; amplitudes += VibrationEffect.DEFAULT_AMPLITUDE
                timings += SHORT_ON; amplitudes += 200
                timings += SHORT_OFF; amplitudes += 0
            }
        }

        if (timings.isEmpty()) return

        val effect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            VibrationEffect.createWaveform(timings.toLongArray(), amplitudes.toIntArray(), -1)
        } else {
            @Suppress("DEPRECATION")
            VibrationEffect.createOneShot(LONG_ON, VibrationEffect.DEFAULT_AMPLITUDE)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(effect)
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(LONG_ON)
        }
    }

    /** Short distinct haptic for sync feedback. */
    fun playSync() {
        if (!vibrator.hasVibrator()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(
                VibrationEffect.createOneShot(80L, VibrationEffect.DEFAULT_AMPLITUDE)
            )
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(80L)
        }
    }

    /** Cancel any ongoing haptic. */
    fun cancel() {
        vibrator.cancel()
    }
}
