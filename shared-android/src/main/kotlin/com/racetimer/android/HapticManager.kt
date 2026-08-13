package com.racetimer.android

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
class HapticManager(
    context: Context,
    private val usagePolicy: HapticUsagePolicy,
) {

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
            HapticUsage.CUE,
        )
    }

    /** Short distinct haptic for sync feedback. */
    fun playSync() {
        if (!vibrator.hasVibrator()) return
        emit(
            VibrationEffect.createOneShot(SYNC_FEEDBACK_MS, VibrationEffect.DEFAULT_AMPLITUDE),
            HapticUsage.FEEDBACK,
        )
    }

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
     * ### Which usage, and why this class does not decide
     *
     * `USAGE_ALARM` is the honest declaration for a race gun and it is the *silent* one on the watch:
     * total-silence Do Not Disturb restricts the alarm class there and permits the feedback class, so
     * the declaration that delivers the gun is a known lie taken deliberately. That is a fact about
     * one device's zen policy, measured on one watch, and it is supplied through [usagePolicy] rather
     * than written here — see [HapticUsagePolicy] for why, and the app module's implementation of it
     * for the measured table and the obligation to re-run it.
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
     * oversight. Neither `:shared-android` nor `:wear` has a test source set — the second is
     * [#160](https://github.com/SailorDave17/race-timer/issues/160), the first is a decision recorded
     * in this module's build file — and no unit test can reach a `vibrate` call regardless. The only
     * assertions available are a bytecode read and the on-watch `dumpsys vibrator_manager` table in
     * #144 — neither of which is a regression test. **A later edit that drops the attributes will go
     * green.**
     *
     * *Re-read 2026-08-13 on the #200 release build*: `HapticUsagePolicy.vibrationUsageFor` reaches
     * `VibrationAttributes$Builder.setUsage` for both usages, the supplied constant is `bipush 18`
     * (`USAGE_TOUCH`), the pre-33 path still carries `bipush 13` (`USAGE_ASSISTANCE_SONIFICATION`),
     * and the `bipush 33` API gate is intact — so both `vibrate` overloads remain reachable. This
     * paragraph cited **`bipush 17`** from #144 until that read: 17 is `USAGE_ALARM`, and #187
     * replaced the constant with `USAGE_TOUCH` while rewriting the prose around this sentence and
     * leaving the number alone. A constant quoted in prose ages the moment the constant moves, and
     * the surrounding rewrite is what made it look current.
     *
     * #200 moved the *value* out of this file and did not change that. What it did change is the
     * older claim made here, that the decision could not leave without "inventing a second enum whose
     * only consumer is this file": [HapticUsage] now has a second consumer, which is the policy that
     * answers for it. The seam is a place to put a per-device answer, not a place a test can reach.
     */
    private fun emit(effect: VibrationEffect, usage: HapticUsage) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            vibrator.vibrate(effect, vibrationAttributes(usage))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(effect, audioAttributes(usage))
        }
    }

    private fun vibrationAttributes(usage: HapticUsage): VibrationAttributes =
        when (usage) {
            HapticUsage.CUE -> cueVibrationAttributes
            HapticUsage.FEEDBACK -> feedbackVibrationAttributes
        }

    private fun audioAttributes(usage: HapticUsage): AudioAttributes =
        when (usage) {
            HapticUsage.CUE -> cueAudioAttributes
            HapticUsage.FEEDBACK -> feedbackAudioAttributes
        }

    // Built once, and once per instance rather than once per class since #200 — the usage is now the
    // app module's answer. Constructing these is cheap, but a cue is issued on a deadline and
    // allocation on that path is the kind of thing that ends up in a timing investigation later, so
    // the policy is read here at construction and never on a cue's path.
    private val cueVibrationAttributes: VibrationAttributes =
        VibrationAttributes.Builder()
            .setUsage(usagePolicy.vibrationUsageFor(HapticUsage.CUE))
            .build()

    private val feedbackVibrationAttributes: VibrationAttributes =
        VibrationAttributes.Builder()
            .setUsage(usagePolicy.vibrationUsageFor(HapticUsage.FEEDBACK))
            .build()

    // The pre-33 pair is deliberately NOT injected, and the difference is the point of the seam.
    // The vibration usage above is a measured answer to a measured question — the app module
    // supplying it carries the table — so shared code must not state it.
    // `USAGE_ASSISTANCE_SONIFICATION` here is the
    // generic, documented attribution for a non-media vibration and has never been measured on any
    // device this app runs on ([emit] says so, and the branch is unreachable on the only watch
    // available). Injecting an unmeasured default would dress it as a per-device decision somebody
    // took, which is precisely the reading this seam exists to prevent. It stays one shared value
    // until a device measurement gives it a reason not to be.
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
         * lookup from the app module's UI to warn a sailor that a cue will never reach their wrist —
         * on the watch that caller is `MainActivity`. Two
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
