package com.racetimer.phone

import com.racetimer.shared.SignalPattern

/**
 * The audible half of the phone's cue path, as the [PhoneTimerViewModel] sees it (#202).
 *
 * An interface for one reason: the audio and haptic path is a firm no for unit tests in this repo
 * (#64, #160 — a shadow `AudioTrack` reports success while a real one is silent), so the ViewModel's
 * *dispatch* logic is proven against a recording fake and the real implementation stays a thin
 * pass-through to `ToneManager`, whose behaviour is measured on hardware rather than asserted in a
 * JVM.
 */
interface CueSounder {

    /**
     * Build the audio track and start it idling, ahead of any cue (#114).
     *
     * Call well before a race — at app launch, and again when a race is armed — so the measured
     * 138-297 ms `startOutput` cost lands here and never on the first cue's deadline.
     */
    fun prepare()

    /** Render [patterns] ahead of the race, so no cue is synthesised on its own deadline (#98). */
    fun warmUp(patterns: List<SignalPattern>)

    /** Sound [pattern] as one cue. Returns immediately; the deadline work happens off-thread. */
    fun playCue(pattern: SignalPattern)

    /** Tear the audio path down. The owner is going away; nothing plays after this. */
    fun release()

    /**
     * The no-op the ViewModel's default parameter names, so a unit test or a preview can construct
     * the ViewModel without an audio stack.
     *
     * **Never the production wiring.** `MainActivity` supplies [PhoneCueSounder] through the
     * ViewModel factory; a production render that ends up here is the app shipping silent, which is
     * this repo's worst failure class (#61, #95 — everything reports success and no sound comes
     * out). It exists because the alternative default — constructing the real thing — needs a
     * `Context` the ViewModel deliberately does not hold.
     */
    object SILENT : CueSounder {
        override fun prepare() {}
        override fun warmUp(patterns: List<SignalPattern>) {}
        override fun playCue(pattern: SignalPattern) {}
        override fun release() {}
    }
}
