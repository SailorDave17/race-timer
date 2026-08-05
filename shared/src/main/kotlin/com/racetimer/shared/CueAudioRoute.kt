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
 * ### What the inputs are, and the one that is not what you would reach for
 *
 * @param overrideEnabled the sailor's setting, default on. Off restores today's behaviour exactly —
 *   vibrate mode silences the cues — which is what makes the setting a genuine opt-out rather than
 *   decoration.
 * @param ringerSilenced true when the ringer is in vibrate or silent mode, i.e. `getRingerMode() !=
 *   RINGER_MODE_NORMAL`. This is deliberately *not* a volume read on `STREAM_ALARM`, and that is worth
 *   stating because a volume read is the obvious choice and it does not work: the aliasing above means
 *   `getStreamVolume(STREAM_ALARM)` returns **15** in precisely the case where the cue is inaudible.
 *   It reports on the stream you named, not the stream the mixer takes the volume from, and no public
 *   API resolves the alias. So the ringer is the honest signal available here — with the limitation
 *   stated rather than hidden: an alarm stream muted some *other* way is not detected, and is #96's
 *   territory.
 * @param alarmVolume `getStreamVolume(STREAM_ALARM)`, kept as a second trigger rather than the primary
 *   one. It is worthless for the aliased case above and correct for the ordinary one — a sailor who has
 *   simply dialled the alarm slider to zero in normal ringer mode — so it costs one comparison and
 *   catches a case the ringer read cannot see. The two are unioned, not intersected: either alone is
 *   enough to reroute, because being wrong towards audible is the cheap direction.
 */
fun cueStream(
    overrideEnabled: Boolean,
    ringerSilenced: Boolean,
    alarmVolume: Int,
): CueStream = when {
    !overrideEnabled -> CueStream.ALARM
    ringerSilenced -> CueStream.MEDIA
    alarmVolume <= 0 -> CueStream.MEDIA
    else -> CueStream.ALARM
}
