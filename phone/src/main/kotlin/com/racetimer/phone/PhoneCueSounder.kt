package com.racetimer.phone

import android.content.Context
import com.racetimer.android.ToneManager
import com.racetimer.shared.CueStream
import com.racetimer.shared.SignalPattern

/**
 * The production [CueSounder]: a thin pass-through to the shared [ToneManager] (#202).
 *
 * Thin on purpose. Everything hard about phone cue audio — the single never-paused track, the
 * silence heartbeat, the frame arithmetic in `CueTrackPacing`, the render-off-the-deadline staging —
 * lives in `:shared-android`, where the watch already proved it on hardware (#61, #98, #114). This
 * class contributes exactly two phone-side decisions and nothing else:
 *
 * - **Which platform constants a route means**: [PhoneCueAudioProfile], provisional pending #210's
 *   measurement. See there.
 * - **Which route to use**: always [CueStream.ALARM], and deliberately *not* the shared
 *   `cueStream()` rule the watch runs. That rule's reroute half exists because one watch's alarm
 *   stream is aliased into the ringer-affected set — a Samsung Wear customisation. Whether this
 *   phone silences `USAGE_ALARM` in any mode is exactly what #210 measures, and wiring the
 *   watch-shaped reroute here first would ship half of #95 (the routing) without the half that made
 *   it safe (the volume floor and its persisted receipt) on the strength of a premise measured on a
 *   different device. Until #210 answers, the phone routes to the platform-documented
 *   ringer-exempt stream and says so.
 */
class PhoneCueSounder(context: Context) : CueSounder {

    private val tone = ToneManager(context, PhoneCueAudioProfile)

    override fun prepare() {
        // Provisional route — see the class doc and #210. Re-preparing with the same route costs
        // one comparison, so calling this at launch and again at arm is cheap on purpose (#114).
        tone.prepare(CueStream.ALARM)
    }

    override fun warmUp(patterns: List<SignalPattern>) = tone.warmUp(patterns)

    override fun playCue(pattern: SignalPattern) = tone.playCue(pattern)

    override fun release() = tone.release()
}
