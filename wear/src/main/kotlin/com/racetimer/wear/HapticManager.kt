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
 * - Long blast  → sustained buzz (~500 ms on, ~200 ms off per blast)
 * - Short blast → quick tap   (~150 ms on, ~150 ms off per blast)
 * - Gun (multi) → rapid triple buzz
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
    private val LONG_ON = 500L
    private val LONG_OFF = 250L
    private val SHORT_ON = 150L
    private val SHORT_OFF = 150L
    private val GUN_REPEAT = 3  // triple-buzz for the gun

    /**
     * Play the haptic pattern for [pattern].
     * If [isGun] is true, override with the gun triple-buzz regardless of pattern.
     */
    fun play(pattern: SignalPattern, isGun: Boolean = false) {
        if (!vibrator.hasVibrator()) return

        val timings = mutableListOf<Long>()
        val amplitudes = mutableListOf<Int>()

        if (isGun) {
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
