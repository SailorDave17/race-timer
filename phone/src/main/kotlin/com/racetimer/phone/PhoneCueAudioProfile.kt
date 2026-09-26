package com.racetimer.phone

import android.media.AudioAttributes
import android.media.AudioManager
import com.racetimer.android.CueAudioProfile
import com.racetimer.shared.CueStream

/**
 * What a [CueStream] means on a phone — **provisional, pending measurement** (#202, measured by
 * #210).
 *
 * ### These values are a choice made for the phone, not an inheritance from the watch
 *
 * [CueAudioProfile] exists so that each form factor states its own answer (#200), and this is the
 * phone's: the platform-documented mappings, chosen because nothing has been measured yet and the
 * documented behaviour is the only ground there is. `USAGE_ALARM` is documented to be exempt from
 * ringer mode and to carry on the stream users keep up for things that must be heard; `USAGE_MEDIA`
 * is the documented landing on `STREAM_MUSIC` for the rerouted case.
 *
 * They happen to name the same constants as `WearCueAudioProfile`. That is coincidence of the
 * starting point, not a copy: the watch's values are *kept* because of device-measured facts (its
 * alarm stream is aliased into the ringer-affected set — a Samsung Wear customisation), and none of
 * those measurements transfers here. Neither does the watch's haptic answer — `USAGE_TOUCH` was
 * measured to survive DND *on that watch*, and importing it here as "the value that works" would be
 * exactly the untransferable inheritance #200 built this interface to prevent.
 *
 * ### Provisional until #210
 *
 * The phone's real answer is owed to a measurement nobody has taken: #210 runs cue delivery on the
 * owner's phone under DND, silent mode, focus loss and screen-off, and records the device it
 * measured on. Until that lands, treat every value below as a placeholder that has never been
 * proven to reach a human — the same epistemic state the watch's values were in before #95's
 * hardware runs, which is what found the aliasing that rewrote them.
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
