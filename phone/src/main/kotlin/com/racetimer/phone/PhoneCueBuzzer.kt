package com.racetimer.phone

import android.content.Context
import com.racetimer.android.HapticManager
import com.racetimer.shared.SignalPattern

/**
 * The production [CueBuzzer]: a thin pass-through to the shared [HapticManager] (#208).
 *
 * Thin on purpose, as [PhoneCueSounder] is for the tones. Everything that decides what a cue
 * *feels* like — the per-voice strength, the blast boundaries shared with the tone, the gun's
 * triple-buzz, the sustained buzz for a cue that states its own length, the API-level split in
 * how the usage is attached — lives in `:shared-android`, where the watch measured it on a wrist
 * and re-verified it after the extraction (#144, #187, #201). This class contributes exactly one
 * phone-side decision: **what the vibrations are declared as**, which is [PhoneHapticUsagePolicy]
 * and was measured on this phone by #210. Nothing else here is the phone's to say.
 */
class PhoneCueBuzzer(context: Context) : CueBuzzer {

    private val haptic = HapticManager(context, PhoneHapticUsagePolicy)

    override fun buzz(pattern: SignalPattern, isGun: Boolean) = haptic.play(pattern, isGun = isGun)

    override fun cancel() = haptic.cancel()
}
