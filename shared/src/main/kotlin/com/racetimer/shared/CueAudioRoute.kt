package com.racetimer.shared

/**
 * Which of the device's outputs a race cue is played through.
 *
 * [ALARM] is what the app has always used and what #61's loudness was verified on: on Wear it is the
 * stream users leave up for alerts, so it carries outdoors. [MEDIA] exists only because on some
 * watches the alarm path is silenced along with the ringer — see [cueStream].
 */
enum class CueStream {
    ALARM,
    MEDIA,
}

/**
 * Which stream the next race's cues should use (#95).
 *
 * ### The defect this decides
 *
 * On an SM-R925U, putting the watch in vibrate mode silences every cue the app makes. The countdown
 * keeps running and the wrist keeps buzzing, so nothing on the watch says the speaker has stopped —
 * the first the sailor knows is the fleet starting without them.
 *
 * `USAGE_ALARM` is normally exempt from ringer mode, and the reason it is not here takes two steps to
 * see. Measured from `adb shell dumpsys audio`:
 *
 * ```
 * - STREAM_ALARM (aliased to: STREAM_NOTIFICATION):
 *      Muted: false        <-- the alarm block itself looks fine
 *      streamVolume:15
 * - STREAM_NOTIFICATION:
 *      Muted: true         <-- but this is where its volume actually comes from
 *      streamVolume:0
 * - ringer mode affected streams = 0x126 (STREAM_SYSTEM,STREAM_RING,STREAM_NOTIFICATION,STREAM_DTMF)
 * ```
 *
 * `STREAM_MUSIC` is neither aliased nor in that mask (`0x126` is bits 1, 2, 5 and 8; music is bit 3),
 * so it stays audible in vibrate mode — and reaching it needs no new manifest permission. That is the
 * way out, and it is a Samsung Wear customisation rather than stock Android behaviour.
 *
 * ### Why this is a rule and not two lines at the call site
 *
 * The same question — *which stream will the cues actually use?* — has to be answered identically by
 * the playback path and by anything that wants to warn about silence (#96). Written out twice it is
 * exactly the shape every defect in this app's audio and restore paths has had: `resumeOfferRemainingMs`
 * was duplicated *inverted* across two methods, and `CueTiming`'s constants were hand-copied into a
 * test. One function, in a module CI runs, asserted by `CueAudioRouteTest`.
 *
 * ### Why it does not simply always use media
 *
 * Because [ALARM] is the verified path and [MEDIA] follows a slider a sailor has no reason to have
 * raised. Rerouting unconditionally would change the loudness of *every* race to fix the subset that
 * are silent; this changes only the races that are currently producing no sound at all, so the
 * baseline it departs from is silence rather than a working cue.
 *
 * ### There is no opt-out, and that is deliberate
 *
 * This function used to take an `overrideEnabled` flag — the sailor's setting, default on, whose off
 * position restored the silence exactly. Removed on owner directive 2026-08-06: *the race timer must
 * never not have audible cues, regardless of the watch's status.* Sounding the signals is the entire
 * purpose of the app, so a setting able to silence it is a safety failure with a toggle in front of
 * it. There is now no value the sailor can set, and no state the watch can be in, that makes this
 * return a stream the ringer has muted.
 *
 * Choosing an audible stream is only half of that promise. The other half is [raisedCueVolume]: the
 * stream this picks can itself be turned down to nothing.
 *
 * ### What the inputs are, and the one that is not what you would reach for
 *
 * @param ringerSilenced true when the ringer is in vibrate or silent mode, i.e. `getRingerMode() !=
 *   RINGER_MODE_NORMAL`. This is deliberately *not* a volume read on `STREAM_ALARM`, and that is worth
 *   stating because a volume read is the obvious choice and it does not work: the aliasing above means
 *   `getStreamVolume(STREAM_ALARM)` returns a healthy non-zero value in precisely the case where the
 *   cue is inaudible — measured at **10** on a silenced SM-R925U while the stream it actually reads
 *   from sat at 0. It reports on the stream you named, not the stream the mixer takes the volume from,
 *   and no public API resolves the alias. So the ringer is the honest signal available here, with its
 *   limitation stated rather than hidden: an alarm stream muted some *other* way is not detected by
 *   this input, which is what the next one is for.
 * @param alarmVolume `getStreamVolume(STREAM_ALARM)`, kept as a second trigger rather than the primary
 *   one. It is worthless for the aliased case above and correct for the ordinary one — a sailor who has
 *   simply dialled the alarm slider to zero in normal ringer mode — so it costs one comparison and
 *   catches a case the ringer read cannot see. The two are unioned, not intersected: either alone is
 *   enough to reroute, because being wrong towards audible is the cheap direction.
 */
fun cueStream(
    ringerSilenced: Boolean,
    alarmVolume: Int,
): CueStream = when {
    ringerSilenced -> CueStream.MEDIA
    alarmVolume <= 0 -> CueStream.MEDIA
    else -> CueStream.ALARM
}

/**
 * How loud the cue stream has to be, as a percentage of that stream's own maximum.
 *
 * Not 100. A race timer pinned to a watch's maximum is unpleasant at the wrist and startling at the
 * gun, and #61's loudness — the figure actually verified on the water — was measured nowhere near a
 * forced maximum. This is a floor for being *certainly heard*, in the same sense as #65's brightness
 * override: enough to do the job, not the most the hardware can do.
 *
 * Kept as a named constant carrying its reason because a bare `70` in the middle of the audio path is
 * exactly the kind of number that gets "tidied" to 100 by someone reading it as a volume rather than
 * as a threshold.
 */
const val CUE_VOLUME_FLOOR_PERCENT = 70

/**
 * The volume to raise the cue stream to for a race, or `null` when it is already loud enough (#95).
 *
 * ### Why routing alone was not enough
 *
 * [cueStream] answers *which* output, and on a silenced watch that answer is [CueStream.MEDIA]. But
 * the media slider is a separate control, which the sailor may have dialled to zero for entirely
 * unrelated reasons — so a correctly-routed cue can still make no sound whatsoever. Routing moved the
 * failure; it did not remove it. This is the half that removes it.
 *
 * ### Why `null` rather than "the volume it already has"
 *
 * The caller has to be able to tell *nothing to do* from *set it to exactly what it is already*,
 * because those two differ in their side effects even though they agree about the resulting volume. A
 * race that changed no device state has nothing to restore afterwards and cannot strand a raised
 * volume if the process is killed — so the common case, a watch whose volume is already up, is
 * required to touch nothing at all. `null` is what makes "nothing outside a race changes" a checkable
 * claim rather than an aspiration.
 *
 * ### It never lowers
 *
 * A sailor whose volume is already above the floor has said something about how loud they want their
 * race, and this has no business contradicting them. The return is a floor, never a target.
 *
 * @param currentVolume `getStreamVolume(stream)` for the stream [cueStream] chose.
 * @param maxVolume `getStreamMaxVolume(stream)` for that same stream. A non-positive value is the
 *   device saying the stream has no usable range, and the honest response to that is to change nothing
 *   rather than to compute a floor out of it.
 */
fun raisedCueVolume(currentVolume: Int, maxVolume: Int): Int? {
    if (maxVolume <= 0) return null
    // Integer ceiling. A floor that rounded *down* would sit below the percentage it claims, and on
    // the small indexes a watch uses — max 15 on the SM-R925U — one step of rounding is a whole step
    // of real loudness.
    val floor = ((maxVolume * CUE_VOLUME_FLOOR_PERCENT) + 99) / 100
    val target = floor.coerceIn(1, maxVolume)
    return if (currentVolume < target) target else null
}
