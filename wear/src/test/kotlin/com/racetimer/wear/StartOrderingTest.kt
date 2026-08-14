package com.racetimer.wear

import android.app.Service
import android.content.Intent
import com.racetimer.shared.BuiltInSequences
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

/**
 * The #62 ordering, pinned: the first cue is dispatched **before** the startup work, not after it.
 *
 * This is #160's highest-value target and the reason that issue chose Robolectric over an eighth
 * extraction pass. The fix is not a value or a rule — it is *an ordering of four calls inside
 * `onStartCommand`* (`engine.tick()` ahead of `persistSnapshot()`, `acquireWakeLock()` and
 * `startForegroundWithNotification()`), which no seam refactor can protect without first rewriting
 * the very ordering under protection.
 *
 * ### Why a shadow is a sufficient instrument here, when it is not for audio
 *
 * The property is an **ordering**, and an ordering is the framework's own bookkeeping rather than
 * physics. "Records that the call happened" is the whole subject, not a weak proxy for it — the
 * distinction cairn `race-timer-testing-strategy` draws when it grants Robolectric for this and
 * refuses it for the audio path. Nothing here asserts that a cue was *heard*: the cue's audio and
 * haptic delivery is #114 / #144 / #126 and stays a hardware question, guarded from creeping in
 * here by `AudioHapticBoundaryTest`.
 *
 * ### The observation is behavioural, not a spy
 *
 * A `TimerListener` added to the engine sees the first cue fire, and reads the **world** at that
 * instant: no wake lock taken, no foreground notification posted, no race written to disk. Move
 * `engine.tick()` below those three lines and all three readings invert. That is what makes this a
 * pin rather than a restatement of the source in another file.
 */
@RunWith(RobolectricTestRunner::class)
class StartOrderingTest {

    /**
     * What the world looked like at the instant the first cue was dispatched.
     *
     * [fired] is not a formality. Every assertion below is of the form "X had not happened yet", and
     * a probe that never ran satisfies all of them — an absent result reading as a clean one (cairn
     * `an-absent-result-reads-as-a-clean-one`). It is asserted first, every time.
     */
    private class FirstCueProbe(private val service: TimerService) : TimerListener {
        var fired = false
            private set
        var wakeLockHeldAtFirstCue = true
        var foregroundEnteredAtFirstCue = true
        var racePersistedAtFirstCue = true

        override fun onCue(cue: SequenceCue) {
            if (fired) return
            fired = true
            wakeLockHeldAtFirstCue = ShadowPowerManager.getLatestWakeLock()?.isHeld == true
            foregroundEnteredAtFirstCue = shadowOf(service).lastForegroundNotification != null
            racePersistedAtFirstCue = TimerService.savedSnapshot(service) != null
        }

        override fun onGun() = Unit
        override fun onTick(remainingMs: Long) = Unit
        override fun onSync(snappedToMs: Long) = Unit
    }

    private fun createdService(): TimerService =
        Robolectric.buildService(TimerService::class.java).create().get()

    private fun TimerService.arm(freshStart: Boolean = false, startId: Int = 1): Int =
        onStartCommand(
            TimerService.startIntent(this, BuiltInSequences.usSailing.id, freshStart),
            0,
            startId,
        )

    @Test
    fun `the first cue is dispatched ahead of persist, the wake lock and the foreground`() {
        val svc = createdService()
        val probe = FirstCueProbe(svc)
        svc.engine.addListener(probe)

        svc.arm()

        // The first cue of every sequence shipped sits at `offsetMs == totalMs`, so it comes due at
        // the instant `start()` anchors the gun and `engine.tick()` dispatches it synchronously.
        // If this ever stops being true the three readings below become vacuous, which is why it is
        // checked rather than assumed.
        assertTrue("the first cue never fired, so nothing below was measured", probe.fired)

        assertFalse("the wake lock was taken before the first cue", probe.wakeLockHeldAtFirstCue)
        assertFalse(
            "the service entered the foreground before the first cue",
            probe.foregroundEnteredAtFirstCue,
        )
        assertFalse("the race was persisted before the first cue", probe.racePersistedAtFirstCue)
    }

    @Test
    fun `the startup work still happens, immediately after`() {
        // The other half of the ordering, and the reason the test above cannot pass by deleting the
        // startup path: a race with no wake lock and no foreground service is the failure #62's fix
        // had to avoid causing, not a stricter version of it.
        val svc = createdService()
        svc.arm()

        val lock = ShadowPowerManager.getLatestWakeLock()
        assertNotNull("no wake lock acquired", lock)
        assertTrue("the wake lock was not held after arming", lock.isHeld)
        assertNotNull(
            "the service never entered the foreground",
            shadowOf(svc).lastForegroundNotification,
        )
        assertNotNull("the race was never persisted", TimerService.savedSnapshot(svc))
        assertEquals(TimerState.RUNNING, svc.engine.currentState)
        assertFalse(svc.foregroundStartRefused)
    }

    @Test
    fun `a start command is not sticky`() {
        // `CLAUDE.md` records this as deliberate: a sticky restart arrives with a null intent, which
        // matches no branch in the `when`, so `startForeground()` would never run and Android 12+
        // kills the process with ForegroundServiceDidNotStartInTimeException. A decision that costs
        // nothing to state and a process to rediscover.
        assertEquals(Service.START_NOT_STICKY, createdService().arm())
    }

    @Test
    fun `stop releases the lock, leaves the foreground and stops the service`() {
        val svc = createdService()
        svc.arm()
        svc.onStartCommand(TimerService.stopIntent(svc), 0, 2)

        assertFalse(
            "the wake lock survived the stop",
            ShadowPowerManager.getLatestWakeLock().isHeld,
        )
        assertTrue("still in the foreground", shadowOf(svc).isForegroundStopped)
        assertTrue("the service did not stop itself", shadowOf(svc).isStoppedBySelf)
        assertNull("the race outlived the stop", TimerService.savedSnapshot(svc))
    }

    @Test
    fun `sync re-sizes the wake lock so a gun moved later is still covered`() {
        // #126: `acquireWakeLock` sizes its timeout from `engine.remainingMs` at the instant it is
        // called, and until that fix it was only ever called from ACTION_START -- so the lock
        // protected the race the sailor started rather than the race a sync had just made longer.
        val svc = createdService()
        svc.arm()
        val first = ShadowPowerManager.getLatestWakeLock()
        assertTrue(first.isHeld)

        svc.onStartCommand(Intent(TimerService.syncIntent(svc)), 0, 2)

        val second = ShadowPowerManager.getLatestWakeLock()
        assertTrue("sync did not re-acquire the lock", first !== second)
        assertTrue("the re-acquired lock is not held", second.isHeld)
        assertFalse("the replaced lock leaked", first.isHeld)
    }

    @Test
    fun `a sync delivered to a service with no race under it stops rather than idling`() {
        // The intent can land on a service the system created just to carry it -- the app was killed,
        // or the race has already ended. There is then nothing to sync and no countdown to hold open.
        val svc = createdService()
        svc.onStartCommand(TimerService.syncIntent(svc), 0, 1)

        assertEquals(TimerState.IDLE, svc.engine.currentState)
        assertTrue("an idle service was left started", shadowOf(svc).isStoppedBySelf)
    }
}
