package com.racetimer.wear

import android.os.Looper
import android.os.SystemClock
import com.racetimer.shared.BuiltInSequences
import com.racetimer.shared.JOIN_LATE_CUE_GRACE_MS
import com.racetimer.shared.JoinGun
import com.racetimer.shared.PairJoin
import com.racetimer.shared.PairRace
import com.racetimer.shared.RaceKey
import com.racetimer.shared.RaceSequence
import com.racetimer.shared.SequenceCue
import com.racetimer.shared.TimerListener
import com.racetimer.shared.TimerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowPowerManager
import java.time.Duration

/**
 * A race the phone started, run on the watch (#220): `ACTION_JOIN` through the arm `ACTION_START`
 * takes.
 *
 * Which start wins is `:shared`'s rule, covered across two simulated devices by `PairRaceTest`. What
 * only this module can show is that a join the rule sends here is **armed as a start is** — the first
 * cue synchronously and ahead of the startup work (#62), then the persist, the wake lock and the
 * foreground — and anchored to the gun it was given rather than to now. Robolectric within #160's
 * scope: the framework's bookkeeping and the engine's own events, never the audio.
 */
@RunWith(RobolectricTestRunner::class)
class PairJoinTest {

    private fun createdService(): TimerService =
        Robolectric.buildService(TimerService::class.java).create().get()

    private fun TimerService.join(
        sequence: RaceSequence,
        gunMs: Long,
        boundMs: Long? = 30L,
        graceMs: Long = JOIN_LATE_CUE_GRACE_MS,
        startId: Int = 1,
    ) = onStartCommand(
        TimerService.joinIntent(
            this,
            PairJoin(PairRace(RaceKey(1L, 1L), sequence.id, JoinGun(gunMs, boundMs), startedHere = false), sequence, graceMs),
        ),
        0,
        startId,
    )

    /** The phone tapped Start [lateMs] ago on this clock: the gun it anchored, moved here. */
    private fun gunTappedAgo(sequence: RaceSequence, lateMs: Long): Long = SystemClock.elapsedRealtime() - lateMs + sequence.totalMs

    private class Heard : TimerListener {
        val offsets = mutableListOf<Long>()
        override fun onCue(cue: SequenceCue) {
            offsets += cue.offsetMs
        }

        override fun onGun() {}
        override fun onTick(remainingMs: Long) {}
        override fun onSync(snappedToMs: Long) {}
    }

    @Test
    fun `a joined race's first cue is dispatched ahead of persist, the wake lock and the foreground`() {
        val svc = createdService()
        var wakeLockHeld = true
        var foregroundEntered = true
        var racePersisted = true
        val probe = FirstCueProbe {
            wakeLockHeld = ShadowPowerManager.getLatestWakeLock()?.isHeld == true
            foregroundEntered = shadowOf(svc).lastForegroundNotification != null
            racePersisted = TimerService.savedSnapshot(svc) != null
        }
        svc.engine.addListener(probe)

        svc.join(BuiltInSequences.usSailing, gunTappedAgo(BuiltInSequences.usSailing, lateMs = 150L))

        assertTrue("the warning never fired, so nothing below was measured", probe.fired)
        assertFalse("the wake lock was taken before the first cue", wakeLockHeld)
        assertFalse("the service entered the foreground before the first cue", foregroundEntered)
        assertFalse("the race was persisted before the first cue", racePersisted)
        // And the startup work did happen after it.
        assertTrue(ShadowPowerManager.getLatestWakeLock().isHeld)
        assertNotNull(shadowOf(svc).lastForegroundNotification)
    }

    @Test
    fun `a joined race runs to the phone's gun, not to now plus the sequence`() {
        val svc = createdService()
        val gun = gunTappedAgo(BuiltInSequences.scholastic, lateMs = 150L)

        svc.join(BuiltInSequences.scholastic, gun)

        assertEquals(TimerState.RUNNING, svc.engine.currentState)
        assertEquals(BuiltInSequences.scholastic, svc.engine.loadedSequence)
        assertEquals("anchored to the given gun", gun, svc.engine.snapshot()?.gunElapsedMs)
        assertEquals("and persisted on it, so a process death restores the pair's race", gun, TimerService.savedSnapshot(svc)?.gunElapsedMs)
    }

    @Test
    fun `the warning the start's own transit made late sounds, and one older than the grace does not`() {
        val late = createdService()
        val heardLate = Heard().also { late.engine.addListener(it) }
        late.join(BuiltInSequences.club, gunTappedAgo(BuiltInSequences.club, lateMs = 200L))
        assertEquals(listOf(3 * 60_000L), heardLate.offsets)

        val tooLate = createdService()
        val heardTooLate = Heard().also { tooLate.engine.addListener(it) }
        tooLate.join(BuiltInSequences.club, gunTappedAgo(BuiltInSequences.club, lateMs = JOIN_LATE_CUE_GRACE_MS + 200L))
        assertTrue("a signal that late is a wrong signal", heardTooLate.offsets.isEmpty())
    }

    @Test
    fun `a join inside the budget says nothing`() {
        val svc = createdService()
        svc.join(BuiltInSequences.usSailing, gunTappedAgo(BuiltInSequences.usSailing, 100L), boundMs = 40L)

        assertNull(svc.pairJoinNotice)
    }

    @Test
    fun `a join the link could not place inside the budget is flagged until Sync`() {
        val svc = createdService()
        svc.join(BuiltInSequences.usSailing, gunTappedAgo(BuiltInSequences.usSailing, 100L), boundMs = 180L)

        assertEquals("Phone start ±180 ms — tap Sync to confirm", svc.pairJoinNotice)
        svc.onStartCommand(TimerService.syncIntent(svc), 0, 2)
        assertNull("Sync is the confirmation the line asks for", svc.pairJoinNotice)
    }

    @Test
    fun `an unmeasured join says so, and Stop clears it`() {
        val svc = createdService()
        svc.join(BuiltInSequences.usSailing, gunTappedAgo(BuiltInSequences.usSailing, 100L), boundMs = null)

        assertEquals("Phone start unmeasured — tap Sync to confirm", svc.pairJoinNotice)
        svc.onStartCommand(TimerService.stopIntent(svc), 0, 2)
        assertNull(svc.pairJoinNotice)
    }

    @Test
    fun `a start made here carries no joined race's flag`() {
        // The one window where it could: a joined race has fired its gun, the "GO!" screen offers
        // Start, and the teardown that would clear the flag is still lingering.
        val svc = createdService()
        svc.join(BuiltInSequences.club, gunTappedAgo(BuiltInSequences.club, 100L), boundMs = null)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(BuiltInSequences.club.totalMs + 200L))
        assertEquals(TimerState.FINISHED, svc.engine.currentState)
        assertNotNull("the positive control: the flag is still up in the linger", svc.pairJoinNotice)

        svc.onStartCommand(TimerService.startIntent(svc, BuiltInSequences.club.id, freshStart = true), 0, 2)

        assertNull(svc.pairJoinNotice)
    }

    @Test
    fun `a join inside the last race's post-gun linger is not torn down by it`() {
        val svc = createdService()
        svc.onStartCommand(TimerService.startIntent(svc, BuiltInSequences.club.id, freshStart = true), 0, 1)
        // Just past the gun: the sustained-free club gun fired and its linger teardown is pending.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(BuiltInSequences.club.totalMs + 200L))
        assertEquals("the positive control: the gun has fired", TimerState.FINISHED, svc.engine.currentState)

        svc.join(BuiltInSequences.usSailing, gunTappedAgo(BuiltInSequences.usSailing, 100L), startId = 2)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10_000L))

        assertEquals("the joined race survived the old race's teardown", TimerState.RUNNING, svc.engine.currentState)
        assertFalse(shadowOf(svc).isStoppedBySelf)
    }

    @Test
    fun `a join whose gun has passed leaves an idle watch as it was and keeps its foreground promise`() {
        val svc = createdService()

        svc.join(BuiltInSequences.club, gunMs = SystemClock.elapsedRealtime() - 1L)

        assertEquals(TimerState.IDLE, svc.engine.currentState)
        assertNull("nothing was written over a saved race", TimerService.savedSnapshot(svc))
        // The id, not the notification: stopForeground(REMOVE) nulls the latter in the shadow, and
        // only startForeground ever sets the former.
        assertEquals(
            "startForeground was still called, as startForegroundService requires",
            RaceTimerApplication.TIMER_NOTIFICATION_ID,
            shadowOf(svc).lastForegroundNotificationId,
        )
        assertTrue(shadowOf(svc).isForegroundStopped)
        assertTrue(shadowOf(svc).isStoppedBySelf)
    }
}
