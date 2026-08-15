package com.racetimer.wear

import android.content.Context
import android.media.AudioManager
import com.racetimer.shared.BuiltInSequences
import com.racetimer.shared.CueStream
import com.racetimer.shared.raisedCueVolume
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The #95 volume the service borrows for a race, and the promise that it gives it back.
 *
 * A device setting changed by an app is only defensible if putting it back survives the app dying,
 * which is what #65 rejected the equivalent move for screen brightness over. The mechanism is a
 * persisted obligation plus three restore points, and the state machine -- record, raise, repay,
 * clear -- is service orchestration: the *rule* for how loud is loud enough lives in
 * `shared/CueAudioRoute.kt` and is tested there, and is called here rather than restated so the two
 * cannot drift.
 *
 * This is device *state*, not the cue path. Nothing here asserts that anything was heard; whether a
 * raised stream actually carries a cue to the wrist is #61 / #114 and is a wrist question.
 */
@RunWith(RobolectricTestRunner::class)
class CueVolumeBorrowTest {

    private fun audio(context: Context): AudioManager =
        context.getSystemService(AudioManager::class.java)

    private fun createdService(): TimerService =
        Robolectric.buildService(TimerService::class.java).create().get()

    @Test
    fun `an obligation survives the round trip, including a previous volume of zero`() {
        val app = RuntimeEnvironment.getApplication()
        // Zero is the case the sentinel exists for: a stream the sailor had muted is precisely what
        // this feature was written to encounter, so "no record" cannot be spelled the same way.
        TimerService.saveRaisedCueVolume(app, AudioManager.STREAM_MUSIC, 0)

        val record = TimerService.raisedCueVolumeRecord(app)
        assertNotNull("a previous volume of 0 was read back as no obligation at all", record)
        assertEquals(AudioManager.STREAM_MUSIC, record!!.stream)
        assertEquals(0, record.previousVolume)

        TimerService.clearRaisedCueVolume(app)
        assertNull("the obligation outlived its discharge", TimerService.raisedCueVolumeRecord(app))
    }

    @Test
    fun `a volume stranded by a dead process is repaid the next time the service is created`() {
        // The recovery half. A watch whose app was killed mid-race and never reopened still gets its
        // volume back the next time anything starts this service -- `onCreate` is the earliest point
        // at which that is true, and it is where the third restore point sits.
        val app = RuntimeEnvironment.getApplication()
        TimerService.saveRaisedCueVolume(app, AudioManager.STREAM_MUSIC, 2)
        audio(app).setStreamVolume(AudioManager.STREAM_MUSIC, 11, 0)

        createdService()

        assertEquals(
            "the borrowed volume was never repaid",
            2,
            audio(app).getStreamVolume(AudioManager.STREAM_MUSIC),
        )
        assertNull(
            "the discharged obligation is still on disk, so a later launch would repay it again " +
                "against a volume the sailor has since set themselves",
            TimerService.raisedCueVolumeRecord(app),
        )
    }

    @Test
    fun `a quiet stream is raised for the race, recorded, and put back at the end`() {
        val svc = createdService()
        val audio = audio(svc)
        // Ringer normal and a non-zero alarm volume: `cueStream` then routes to ALARM, which is the
        // ordinary case rather than the silenced-watch reroute.
        audio.ringerMode = AudioManager.RINGER_MODE_NORMAL
        audio.setStreamVolume(AudioManager.STREAM_ALARM, 1, 0)
        val expected = raisedCueVolume(
            currentVolume = 1,
            maxVolume = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM),
        )
        assertNotNull("a volume of 1 is meant to be below the audible floor", expected)

        svc.onStartCommand(TimerService.startIntent(svc, BuiltInSequences.usSailing.id), 0, 1)

        assertEquals(
            "the cue stream was not raised for the race",
            expected!!.toInt(),
            audio.getStreamVolume(AudioManager.STREAM_ALARM),
        )
        val record = TimerService.raisedCueVolumeRecord(svc)
        assertNotNull("the race raised a volume and recorded no way to put it back", record)
        assertEquals(AudioManager.STREAM_ALARM, record!!.stream)
        assertEquals(1, record.previousVolume)

        svc.onStartCommand(TimerService.stopIntent(svc), 0, 2)

        assertEquals(
            "the sailor's volume was not restored at the end of the race",
            1,
            audio.getStreamVolume(AudioManager.STREAM_ALARM),
        )
        assertNull(TimerService.raisedCueVolumeRecord(svc))
    }

    @Test
    fun `a race that needs no raise touches nothing and owes nothing`() {
        // `raisedCueVolume` returns null when the stream is already at or above the floor, and null
        // means *touch nothing* -- no device write and no persisted record. This is what makes
        // "nothing outside a race changes" true rather than merely intended, and it is the arm the
        // previous test is the control for.
        val svc = createdService()
        val audio = audio(svc)
        audio.ringerMode = AudioManager.RINGER_MODE_NORMAL
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        audio.setStreamVolume(AudioManager.STREAM_ALARM, max, 0)

        svc.onStartCommand(TimerService.startIntent(svc, BuiltInSequences.usSailing.id), 0, 1)

        assertEquals(max, audio.getStreamVolume(AudioManager.STREAM_ALARM))
        assertNull(
            "a race that raised nothing recorded an obligation anyway, which a later launch " +
                "would act on",
            TimerService.raisedCueVolumeRecord(svc),
        )
    }

    @Test
    fun `a stream that answers and stays muted is a refusal, and records nothing`() {
        // The refusal this watch actually performs: `setStreamVolume` throws nothing and changes
        // nothing that matters -- the stream holds the index it was asked for and remains muted.
        // *Measured on an SM-R925U under zen_mode=2*, and the reason `setStreamVolumeChecked` reads
        // the volume back instead of trusting the absence of an exception. The exception path alone
        // left this flag false through a measurably silent start.
        val svc = createdService()
        val audio = audio(svc)
        audio.ringerMode = AudioManager.RINGER_MODE_NORMAL
        audio.setStreamVolume(AudioManager.STREAM_ALARM, 1, 0)
        audio.adjustStreamVolume(AudioManager.STREAM_ALARM, AudioManager.ADJUST_MUTE, 0)

        svc.onStartCommand(TimerService.startIntent(svc, BuiltInSequences.usSailing.id), 0, 1)

        assertTrue(
            "a stream that took the index and stayed muted was reported as audible, which is the " +
                "state that silences a start with the one warning that could catch it switched off",
            svc.cueVolumeRefused,
        )
        assertNull(
            "a refused raise left a record claiming a change it never made -- the next launch " +
                "would restore a volume the sailor has since set themselves",
            TimerService.raisedCueVolumeRecord(svc),
        )
    }

    @Test
    @Config(shadows = [IndexIgnoringAudioManager::class])
    fun `a stream that pins its index without reporting itself muted is also a refusal`() {
        // The other conjunct of the read-back, and the one no other test reaches.
        //
        // `setStreamVolumeChecked` requires BOTH `getStreamVolume(stream) == volume` and
        // `!isStreamMute(stream)`. Under the stock shadow only the second can ever fire: the
        // requested index is written straight into the stream, and the target `raisedCueVolume`
        // computes comes from that same stream's maximum, so the read-back always equals the
        // request. The value comparison is not merely untested, it is unreachable through the
        // service -- so deleting it left all of these tests green.
        //
        // It is not hypothetical on this watch. `WearCueAudioProfile` records `STREAM_ALARM`
        // reporting `Muted: false` while aliased to a muted `STREAM_NOTIFICATION`; a stream that
        // holds an index while calling itself unmuted is that shape.
        val svc = createdService()
        val audio = audio(svc)
        audio.ringerMode = AudioManager.RINGER_MODE_NORMAL

        svc.onStartCommand(TimerService.startIntent(svc, BuiltInSequences.usSailing.id), 0, 1)

        assertTrue(
            "a stream that ignored the write was reported as raised",
            svc.cueVolumeRefused,
        )
        // Which conjunct refused, asserted rather than assumed. Without this the test would pass
        // just as well if the mute check were the one that fired, and it would be measuring the
        // clause the other tests already cover (cairn `prove-a-guard-test-can-fail`, tenth outcome:
        // a refusal was asserted, a refusal occurred, it was not the one under test).
        assertFalse(
            "the mute flag fired, so this is not exercising the value comparison",
            audio.isStreamMute(WearCueAudioProfile.legacyStreamFor(CueStream.MEDIA)),
        )
        assertNull(
            "a refused raise left a record claiming a change it never made",
            TimerService.raisedCueVolumeRecord(svc),
        )
    }

    @Test
    fun `a refusal is this race's verdict and does not survive into the next race`() {
        // #96. The flag is rewritten on **every** arm, including the two that return early -- it used
        // to be written only where a raise was actually attempted, so the "already loud enough" path
        // left the previous race's answer standing and warned about a race that had already finished.
        val svc = createdService()
        val audio = audio(svc)
        audio.ringerMode = AudioManager.RINGER_MODE_NORMAL
        audio.setStreamVolume(AudioManager.STREAM_ALARM, 1, 0)
        audio.adjustStreamVolume(AudioManager.STREAM_ALARM, AudioManager.ADJUST_MUTE, 0)
        svc.onStartCommand(TimerService.startIntent(svc, BuiltInSequences.usSailing.id), 0, 1)
        assertTrue("the first race was meant to be refused", svc.cueVolumeRefused)
        svc.onStartCommand(TimerService.stopIntent(svc), 0, 2)

        // Second race, on a watch the sailor has since turned up: nothing to raise, so the arm takes
        // the early exit -- which is exactly the path that used to skip the reset.
        audio.adjustStreamVolume(AudioManager.STREAM_ALARM, AudioManager.ADJUST_UNMUTE, 0)
        audio.setStreamVolume(
            AudioManager.STREAM_ALARM,
            audio.getStreamMaxVolume(AudioManager.STREAM_ALARM),
            0,
        )
        svc.onStartCommand(TimerService.startIntent(svc, BuiltInSequences.usSailing.id), 0, 3)

        assertFalse(
            "this race inherited the previous race's refusal, so the sailor is warned about a " +
                "start that already happened",
            svc.cueVolumeRefused,
        )
    }

    @Test
    fun `discharging the obligation leaves the race and the remembered pick alone`() {
        // Removed by name, like `clearPersistedState`: the obligation and the race share a
        // preferences file and have different lifetimes, and an `edit().clear()` here would take
        // both of the others with it.
        val svc = createdService()
        TimerService.savePickedSequenceId(svc, BuiltInSequences.club.id)
        svc.onStartCommand(TimerService.startIntent(svc, BuiltInSequences.club.id), 0, 1)
        TimerService.saveRaisedCueVolume(svc, AudioManager.STREAM_MUSIC, 4)

        TimerService.clearRaisedCueVolume(svc)

        assertNull(TimerService.raisedCueVolumeRecord(svc))
        assertNotNull("the race went with the obligation", TimerService.savedSnapshot(svc))
        assertEquals(BuiltInSequences.club.id, TimerService.pickedSequenceId(svc))
    }
}
