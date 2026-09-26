package com.racetimer.phone

import android.media.AudioAttributes
import android.media.AudioManager
import com.racetimer.android.CueAudioProfile
import com.racetimer.shared.CueStream

/**
 * What a [CueStream] means on a phone — **measured** on the owner's phone (#210).
 *
 * ### The measured table
 *
 * SM-S918U, Android 16 (API 36), `develop @ a4972fe`, 2026-09-25. One full US Sailing 5-4-1-Go race
 * per condition (30 cues), each cue counted as heard by a microphone reading that cue's own pitch,
 * with the player's `mutedState` from `dumpsys audio` alongside. Procedure, instrument and its
 * negative control in `docs/phone-cue-delivery.md`.
 *
 * | Condition | `USAGE_ALARM` (what ships) | `USAGE_ASSISTANCE_ACCESSIBILITY` |
 * |---|---|---|
 * | normal | 30 of 30 heard, player unmuted | — |
 * | vibrate mode | 30 of 30 | — |
 * | silent mode | 30 of 30 | — |
 * | another app's music holding audio focus | 30 of 30, never ducked or faded | — |
 * | screen off, on (reported) battery | 30 of 30 | — |
 * | **total-silence Do Not Disturb** | **0 of 30** — `mutedState=opPlayAudio` throughout | **30 of 30** |
 *
 * ### What the table settles, and what it leaves to #315
 *
 * **Ringer mode does not reach the alarm stream on this phone.** Vibrate and silent both left
 * `STREAM_ALARM` unmuted and every cue audible — the opposite of the SM-R925U, whose alarm stream
 * is aliased into the ringer-affected set (see `WearCueAudioProfile`). That is the measurement
 * [PhoneCueSounder] was waiting on before deciding whether the phone needs the watch's #95 reroute,
 * and the answer is no.
 *
 * **Total-silence DND does reach it**, and there no stream choice helps: DND mutes `STREAM_MUSIC`
 * too, so [CueStream.MEDIA] is no way out. The accessibility usage is the one DND leaves alone, and
 * it delivered every cue in its one race — but it plays at the accessibility volume (5 of 15 on this
 * phone, set by nobody), a slider Samsung's volume panel may not show, where the alarm stream is the
 * one #61's loudness was verified on; and it has not been run in the other five conditions. So it is
 * **not** adopted here (owner decision, 2026-09-25); #315 owns that decision with these numbers.
 *
 * ### These values are the phone's, not an inheritance from the watch
 *
 * [CueAudioProfile] exists so that each form factor states its own answer (#200). These happen to
 * name the same constants as `WearCueAudioProfile`, for different reasons: the watch keeps them
 * *despite* a device-measured aliasing that forced a reroute, the phone keeps them because nothing
 * here needed one. [CueStream.MEDIA]'s mapping is the documented landing on `STREAM_MUSIC` and is
 * **unmeasured** on the phone — [PhoneCueSounder] never routes there.
 *
 * `PhoneCueDeclarationTest` pins these values, so a change here is a red build and a reason to
 * re-run the procedure, not a silent edit.
 */
object PhoneCueAudioProfile : CueAudioProfile {

    override fun audioUsageFor(route: CueStream): Int = when (route) {
        CueStream.ALARM -> AudioAttributes.USAGE_ALARM
        CueStream.MEDIA -> AudioAttributes.USAGE_MEDIA
    }

    override fun legacyStreamFor(route: CueStream): Int = when (route) {
        CueStream.ALARM -> AudioManager.STREAM_ALARM
        CueStream.MEDIA -> AudioManager.STREAM_MUSIC
    }
}
