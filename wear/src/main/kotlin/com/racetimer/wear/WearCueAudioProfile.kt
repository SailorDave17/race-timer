package com.racetimer.wear

import android.media.AudioAttributes
import android.media.AudioManager
import com.racetimer.android.CueAudioProfile
import com.racetimer.shared.CueStream

/**
 * What a [CueStream] means on this watch (#95, #200).
 *
 * ### Why this is here and not in `:shared-android`
 *
 * Because every value below was measured on an SM-R925U and is a fact about *that* device's audio
 * policy, not about Android. `USAGE_ALARM` is normally exempt from ringer mode; on this watch the
 * alarm stream is aliased into the ringer-affected set, so vibrate mode silences it while the
 * countdown and the haptics carry on and nothing on screen says the speaker has stopped. Read from
 * `adb shell dumpsys audio`:
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
 * so it stays audible in vibrate mode — **a Samsung Wear customisation rather than stock Android
 * behaviour**, which is exactly why the phone must measure its own rather than read this one.
 * [com.racetimer.shared.cueStream] — the rule that *picks* a route — is genuinely shared and stays in
 * `:shared`; only this translation of a route into platform constants is per-device.
 *
 * ### One copy of the mapping, and that has not changed
 *
 * Two paths need this answer and must never disagree: [ToneManager]'s playback and keep-alive, which
 * are handed this object, and [TimerService]'s volume raise, which calls [legacyStreamFor] directly.
 * Every audio and restore defect this app has shipped came from one rule written out twice —
 * `resumeOfferRemainingMs` was duplicated *inverted*, and `CueTiming`'s constants were hand-copied
 * into a test. Before #200 the single copy was a top-level `internal fun` in `ToneManager.kt`; the
 * class moving to a module `:wear` does not share `internal` with is what makes it this object
 * instead. It is the same single copy, at a new address.
 */
object WearCueAudioProfile : CueAudioProfile {

    /**
     * `USAGE_MEDIA` rather than `USAGE_ASSISTANCE_SONIFICATION` for the rerouted case: the point is to
     * land on `STREAM_MUSIC`, the one stream measured to be neither aliased nor in this device's
     * ringer-affected mask. `USAGE_ALARM` for the normal case, because on Wear it is the loudest
     * stream and the one users leave up for alerts, and it is what #61's on-the-water loudness was
     * verified on.
     */
    override fun audioUsageFor(route: CueStream): Int = when (route) {
        CueStream.ALARM -> AudioAttributes.USAGE_ALARM
        CueStream.MEDIA -> AudioAttributes.USAGE_MEDIA
    }

    override fun legacyStreamFor(route: CueStream): Int = when (route) {
        CueStream.ALARM -> AudioManager.STREAM_ALARM
        CueStream.MEDIA -> AudioManager.STREAM_MUSIC
    }
}
