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
 * - **Which platform constants a route means**: [PhoneCueAudioProfile], measured by #210. See there.
 * - **Which route to use**: always [CueStream.ALARM], and deliberately *not* the shared
 *   `cueStream()` rule the watch runs. That rule's reroute half exists because one watch's alarm
 *   stream is aliased into the ringer-affected set — a Samsung Wear customisation. #210 measured
 *   whether this phone does the same, and it does not: vibrate and silent mode both left the alarm
 *   stream unmuted and delivered 30 of 30 cues. So the reroute stays unwired on evidence now, not
 *   only on caution. The one condition that does silence the alarm stream here — total-silence Do
 *   Not Disturb — silences `STREAM_MUSIC` with it, so the reroute would not help there either; #315
 *   owns that condition.
 */
class PhoneCueSounder(context: Context) : CueSounder {

    private val tone = ToneManager(context, PhoneCueAudioProfile)

    override fun prepare() {
        // The measured route — see the class doc and #210. Re-preparing with the same route costs
        // one comparison, so calling this at launch and again at arm is cheap on purpose (#114).
        tone.prepare(CueStream.ALARM)
    }

    override fun warmUp(patterns: List<SignalPattern>) = tone.warmUp(patterns)

    override fun playCue(pattern: SignalPattern) = tone.playCue(pattern)

    override fun release() = tone.release()
}
