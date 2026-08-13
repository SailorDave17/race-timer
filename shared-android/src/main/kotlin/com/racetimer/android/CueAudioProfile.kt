package com.racetimer.android

import com.racetimer.shared.CueStream

/**
 * How this device's audio stack realises a [CueStream] — supplied by the app module, never assumed
 * here (#200).
 *
 * ### Why this is injected rather than a `when` in [ToneManager]
 *
 * [com.racetimer.shared.cueStream] decides *which* route a race should use, and that decision is a
 * pure rule both form factors share. What a route then **means** to the platform is not shared: it
 * was measured on one watch.
 *
 * The [CueStream.MEDIA] fallback exists because on an SM-R925U the alarm stream is aliased into the
 * ringer-affected set, so `USAGE_ALARM` is silenced by vibrate mode while `STREAM_MUSIC` is not —
 * *a Samsung Wear customisation, not stock Android behaviour*. Copying that mapping into shared code
 * would state, on every device that ever runs this module, an answer that was true of one watch.
 * A phone's alarm stream is not aliased that way as far as anybody here has checked, and *as far as
 * anybody here has checked* is the whole point: the phone's answer is owed a measurement, and an
 * inherited default is exactly what stops one being taken.
 *
 * ### There is no default implementation, deliberately
 *
 * An app module that forgets to supply one does not get the watch's answer — it does not compile.
 * A default would be indistinguishable, at every later reading, from a measurement someone took.
 */
interface CueAudioProfile {

    /**
     * The `AudioAttributes` usage to build the cue track with, for [route].
     *
     * Fixed at track-build time — it is an attribute on the `AudioTrack` builder, not something that
     * can be moved on a live track — which is why [ToneManager.prepare] rebuilds rather than retunes.
     */
    fun audioUsageFor(route: CueStream): Int

    /**
     * The legacy `AudioManager` stream type [route] means, for the APIs that still take one.
     *
     * Two callers need this answer and they must never disagree: the keep-alive `ToneGenerator` in
     * [ToneManager], which holds the *cue's own* output open, and whatever raises that stream's
     * volume for a race. A keep-alive on the wrong stream and a working one look identical from
     * inside this class (#95), so the two paths read one implementation of this or they read nothing.
     */
    fun legacyStreamFor(route: CueStream): Int
}
