package com.racetimer.phone

import com.racetimer.shared.SignalPattern

/**
 * The felt half of the phone's cue path, as [PhoneRaceRunner] sees it (#208).
 *
 * The twin of [CueSounder], for the same reason: nothing a JVM test can hold stands in for a
 * buzz reaching a hand, so the runner's *dispatch* — which pattern, in which order relative to
 * the tone, with the gun flag where shared put it — is proven against a recording fake, and the
 * production implementation stays a thin pass-through to the shared haptic manager, whose
 * waveforms the watch already measured on a wrist (#144, #187, #201).
 *
 * The pattern handed over is shared's own object, voice intact. Which waveform a voice becomes —
 * a blast's strength, a sync tick's lightness, a prompt's stutter — is decided once in
 * `:shared-android` from `CueTiming`, and the phone re-declares none of it. `ModuleBoundaryTest`
 * refuses a phone source that tries.
 */
interface CueBuzzer {

    /**
     * Buzz [pattern] as one cue. [isGun] selects the gun's triple-buzz for a cue that states no
     * sustained length, exactly as the watch's `haptic.play(cue.signal, isGun = cue.isGun)` does.
     * Returns at once; the platform plays the waveform.
     */
    fun buzz(pattern: SignalPattern, isGun: Boolean)

    /** Stop any buzz in flight. The owner is going away; nothing is felt after this. */
    fun cancel()

    /**
     * The no-op the runner's default parameter names, so a unit test or a preview can construct
     * the runner without a haptic stack.
     *
     * **Never the production wiring**, for [CueSounder.SILENT]'s reason: `PhoneTimerService`
     * supplies [PhoneCueBuzzer], and a production runner that ends up here is the app shipping
     * with a cue channel silently missing — the failure class this repo fears most, because every
     * call reports success and nothing reaches the person.
     */
    object STILL : CueBuzzer {
        override fun buzz(pattern: SignalPattern, isGun: Boolean) {}
        override fun cancel() {}
    }
}
