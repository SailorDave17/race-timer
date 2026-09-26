package com.racetimer.wear

import android.app.Notification
import android.app.Service
import android.media.AudioManager
import com.racetimer.shared.SequenceCue
import com.racetimer.shared.TimerListener
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowAudioManager
import org.robolectric.shadows.ShadowService

/**
 * Two shadows and one probe, each existing because a branch the service really has is otherwise
 * unreachable from a unit test -- not to make anything easier.
 *
 * Both shadows are opt-in per test via `@Config(shadows = [...])`. That matters: a shadow installed
 * for the whole class would silently change what every other test in it was measuring.
 *
 * None of this reaches the audio or haptic path. `IndexIgnoringAudioManager` fakes a *volume
 * control* answering dishonestly, which is device-state bookkeeping; nothing here claims anything
 * about a sound. `AudioHapticBoundaryTest` still governs this file.
 */

/**
 * An `AudioManager` whose `setStreamVolume` answers and changes nothing, **without** setting the
 * mute flag.
 *
 * `setStreamVolumeChecked` reads the volume back and requires two things:
 * `getStreamVolume(stream) == volume && !isStreamMute(stream)`. Under the stock shadow only the
 * second can ever be the operative cause of a refusal -- `setStreamVolume` writes the requested
 * index straight into the stream, and the target `raisedCueVolume` computes is derived from that
 * same stream's maximum, so the read-back always equals the request. The value comparison is
 * therefore not merely untested, it is **unreachable** through the service.
 *
 * That conjunct is the belt of a belt-and-braces read-back, and this watch is a plausible place for
 * it to be the one that fires: `WearCueAudioProfile` records `STREAM_ALARM` reporting `Muted: false`
 * while aliased to a `STREAM_NOTIFICATION` that is muted. A stream that pins its index while
 * reporting itself unmuted is exactly the shape that would slip past the mute check alone.
 */
@Implements(AudioManager::class)
class IndexIgnoringAudioManager : ShadowAudioManager() {
    @Implementation
    override fun setStreamVolume(streamType: Int, index: Int, flags: Int) {
        // Deliberately nothing. Not a throw: the fault being modelled is a platform that returns
        // normally having changed nothing, which is the one the exception path cannot see.
    }
}

/**
 * A `Service` whose `startForeground` refuses, standing in for the platform's own refusal.
 *
 * The #13 abort arm catches `RuntimeException` to cover both refusals Android actually throws --
 * `ForegroundServiceStartNotAllowedException` (an `IllegalStateException`, Android 12+) and the
 * `SecurityException` Android 14+ raises when the declared FGS type is not permitted. Neither can
 * be provoked through Robolectric's stock `ShadowService`, which grants the foreground
 * unconditionally, so without this the entire branch is unreachable and the success-path
 * `assertFalse(foregroundStartRefused)` asserts a field that was never anything else.
 *
 * `IllegalStateException` rather than the real class because
 * `ForegroundServiceStartNotAllowedException` has no public constructor. The catch is written
 * against the shared supertype precisely so the exact subclass does not matter -- which is what
 * makes the stand-in faithful rather than convenient.
 */
@Implements(Service::class)
class ForegroundRefusingService : ShadowService() {
    @Implementation
    override fun startForeground(id: Int, notification: Notification?) {
        throw IllegalStateException("stand-in for ForegroundServiceStartNotAllowedException")
    }
}

/**
 * Reads the world at the instant the first cue is dispatched.
 *
 * The only way to observe the inside of `onStartCommand`. Two tests need it for different reasons --
 * the #62 ordering pin, and the fresh-start clear whose effect is overwritten two lines later by
 * `persistSnapshot()` -- so it lives here rather than in either.
 *
 * [fired] is not a formality. Every assertion taken through this probe is of the form "X had not
 * happened yet", and a probe that never ran satisfies all of them. Assert it first, every time.
 */
class FirstCueProbe(private val observe: () -> Unit) : TimerListener {
    var fired = false
        private set

    override fun onCue(cue: SequenceCue) {
        if (fired) return
        fired = true
        observe()
    }

    override fun onGun() = Unit
    override fun onTick(remainingMs: Long) = Unit
    override fun onSync(snappedToMs: Long) = Unit
}
