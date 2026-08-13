package com.racetimer.wear

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
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

        emit(
            VibrationEffect.createWaveform(timings.toLongArray(), amplitudes.toIntArray(), -1),
            Usage.CUE,
        )
    }

    /** Short distinct haptic for sync feedback. */
    fun playSync() {
        if (!vibrator.hasVibrator()) return
        emit(
            VibrationEffect.createOneShot(SYNC_FEEDBACK_MS, VibrationEffect.DEFAULT_AMPLITUDE),
            Usage.FEEDBACK,
        )
    }

    /**
     * What a vibration is *for*, which the platform will decide on your behalf if you don't say.
     *
     * Not a boolean, for the reason `play` gives above: a boolean silently absorbs a third case into
     * the else branch. The two values are genuinely different promises — a cue must reach the wrist
     * even when the watch has been asked for quiet, and a tap confirmation need not.
     *
     * Both currently resolve to `USAGE_TOUCH`, which looks like the enum earning nothing. It is not:
     * the values differ in *intent* and only coincide in what the platform will honour today. See
     * `emit` for why, and keep them separate — collapsing them would delete the record of a decision
     * that is expected to be revisited.
     */
    private enum class Usage { CUE, FEEDBACK }

    /**
     * Issue [effect], declaring what it is for (#144).
     *
     * ### Why this is not just `vibrator.vibrate(effect)`
     *
     * An unattributed vibration is **not** sent unclassified — the platform classifies it by *effect
     * duration*, and then restricts what it inferred. *Measured* on an SM-R925U (API 36) 2026-08-10:
     * effects totalling 120 ms and 300 ms came back `usage: TOUCH`, while 600 ms, 900 ms and the
     * 3000 ms gun came back `usage: UNKNOWN` — and under Do Not Disturb the platform drops `UNKNOWN`
     * and permits `TOUCH`. So the app's *longest and most important* cues were the ones silently
     * discarded, `ignored_app_ops`, never started, while the short ticks survived. Exactly backwards.
     *
     * The sailor felt the minute pips and the sync ticks and got nothing for the prep signals, the
     * final five seconds, or the gun.
     *
     * ### Why `USAGE_TOUCH` and not `USAGE_ALARM`, which is the honest answer
     *
     * Because `USAGE_ALARM` does not work here, and that was **measured rather than assumed** — this
     * issue and the cairn note both warned it might not. *SM-R925U, API 36, one full race per arm:*
     *
     * | declared usage | `zen_mode=2` | `zen_mode=0` |
     * |---|---|---|
     * | none (duration-inferred) | 20 of 30 | 30 of 30 |
     * | `USAGE_ALARM` | **0 of 30**, every one `ignored_app_ops` | 29 of 29 |
     * | `USAGE_ALARM` + `FLAG_BYPASS_INTERRUPTION_POLICY` | **0 of 30** — the flag is *stripped*, the
     *   record reads `flags: 0`, and no error is raised. Only platform apps keep it. |  |
     * | **`USAGE_TOUCH`** | **30 of 30**, gun at 3032 ms | **30 of 30** |
     *
     * Total-silence DND restricts the alarm usage class on this device and permits the feedback
     * class. So the honest declaration is the one that gets the sailor's gun silenced, and the
     * dishonest one is the only thing that delivers it.
     *
     * **This is a known lie, taken deliberately.** A race gun is not touch feedback. It is declared
     * that way because the platform offers no unprivileged usage that is both accurate and audible
     * under DND, and a silent gun is a safety failure while a mislabelled one is a taxonomy failure.
     *
     * **What would break it, and why nothing here would notice**: if DND policy ever restricts the
     * feedback class, every cue goes silent under DND again, with no error, no crash and no failing
     * test — `wear/` has no test source set (#160) and no unit test can reach a `vibrate` call. The
     * only instrument that has ever detected this class is a race run on the wrist reading
     * `dumpsys vibrator_manager`. Re-run it after any platform upgrade; the numbers above are the
     * baseline to compare against. That obligation is tracked as
     * [#186](https://github.com/SailorDave17/race-timer/issues/186) rather than left to this comment,
     * because a comment is not a thing anybody is scheduled to read.
     *
     * A *declared* usage is honoured on effects that inference would never have classified that way —
     * the 3000 ms gun goes through as TOUCH, which duration-inference only ever assigned to effects
     * under ~300 ms. That is the mechanism, and it is why declaring anything at all is still right.
     *
     * ### The API-level split, which is not optional
     *
     * `Vibrator.vibrate(VibrationEffect, VibrationAttributes)` is **API 33**, and `minSdk` here is
     * **30**. The pre-33 route is the `AudioAttributes` overload — deprecated at 33, not removed, and
     * the documented way to attribute a vibration before `VibrationAttributes` could be passed.
     * `VibrationAttributes` *itself* is API 30, so the fields below are safe to build at any level
     * this app runs on; it is only the call that is gated.
     *
     * This repo has **no lint step** by deliberate decision, so `NewApi` is caught by nothing here,
     * and the only watch available is API 36 — which cannot execute the else branch at all. The
     * levels above were read out of `platforms/android-35/data/api-versions.xml` rather than
     * remembered. **The pre-33 branch is therefore unverified on hardware** and is written to be
     * obviously correct rather than clever.
     *
     * ### Why no test asserts what is passed here (#144 AC 4)
     *
     * Nothing CI runs can see it, and that is a property of the module layout rather than an
     * oversight. `shared/` is pure JVM and cannot reference `android.os`, so the decision cannot be
     * moved there without inventing a second enum whose only consumer is this file; and `wear/` has
     * **no test source set at all**, which is [#160](https://github.com/SailorDave17/race-timer/issues/160).
     * Until that lands, the only assertions available are the bytecode read taken when this shipped
     * (`bipush 17` reaching `setUsage`, both `vibrate` overloads present) and the on-watch
     * `dumpsys vibrator_manager` table in #144 — neither of which is a regression test. **A later
     * edit that drops the attributes will go green.**
     */
    private fun emit(effect: VibrationEffect, usage: Usage) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            vibrator.vibrate(effect, vibrationAttributes(usage))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(effect, audioAttributes(usage))
        }
    }

    private fun vibrationAttributes(usage: Usage): VibrationAttributes =
        when (usage) {
            Usage.CUE -> cueVibrationAttributes
            Usage.FEEDBACK -> feedbackVibrationAttributes
        }

    private fun audioAttributes(usage: Usage): AudioAttributes =
        when (usage) {
            Usage.CUE -> cueAudioAttributes
            Usage.FEEDBACK -> feedbackAudioAttributes
        }

    // Built once. Constructing these is cheap, but a cue is issued on a deadline and allocation on
    // that path is the kind of thing that ends up in a timing investigation later.
    private val cueVibrationAttributes: VibrationAttributes =
        VibrationAttributes.Builder().setUsage(VibrationAttributes.USAGE_TOUCH).build()

    private val feedbackVibrationAttributes: VibrationAttributes =
        VibrationAttributes.Builder().setUsage(VibrationAttributes.USAGE_TOUCH).build()

    private val cueAudioAttributes: AudioAttributes =
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

    private val feedbackAudioAttributes: AudioAttributes =
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

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
