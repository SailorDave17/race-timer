package com.racetimer.android

/**
 * What a vibration is *for*, which the platform will decide on your behalf if you don't say.
 *
 * Not a boolean, because a boolean silently absorbs a third case into the else branch — the same
 * argument [HapticManager.play] makes for reading `CueTiming.amplitude` rather than `voice == SYNC`.
 * The two values are genuinely different promises: a cue must reach the wrist even when the device
 * has been asked for quiet, and a tap confirmation need not.
 *
 * On the watch both currently resolve to the same declared usage, which looks like the enum earning
 * nothing. It is not: the values differ in *intent* and only coincide in what one platform will
 * honour today. Keep them separate — collapsing them would delete the record of a decision that is
 * expected to be revisited, and on a second form factor they may not coincide at all.
 */
enum class HapticUsage {
    CUE,
    FEEDBACK,
}

/**
 * The usage [HapticManager] declares on a vibration — supplied by the app module, never assumed here
 * (#200).
 *
 * ### Why this is injected
 *
 * The watch's answer is `VibrationAttributes.USAGE_TOUCH`, and it is a **known lie taken
 * deliberately**: measured on an SM-R925U at API 36, total-silence Do Not Disturb drops the alarm
 * usage class outright and permits the feedback class, so the accurate declaration is the one that
 * silences the gun. That is a fact about one device's Do Not Disturb policy — see the implementation
 * supplying it for the measured table, and #144/#186/#187.
 *
 * A phone's zen policy is a different policy. Inheriting the watch's lie there would carry the cost
 * of a mislabelled vibration without any evidence that it buys the delivery it was traded for. So the
 * answer is supplied per app, and a new one is owed a measurement rather than a copy.
 *
 * ### No default, for the same reason as [CueAudioProfile]
 *
 * An unsupplied policy is a compile error, not a silent inheritance. The failure mode this guards
 * against is not a wrong value — it is a plausible one that nobody ever re-measures.
 */
fun interface HapticUsagePolicy {

    /** The `VibrationAttributes.USAGE_*` constant to declare for [usage]. */
    fun vibrationUsageFor(usage: HapticUsage): Int
}
