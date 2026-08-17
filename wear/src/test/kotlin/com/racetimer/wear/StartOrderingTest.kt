package com.racetimer.wear

import android.app.Service
import android.content.Intent
import com.racetimer.shared.BuiltInSequences
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
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowPowerManager

/**
 * The #62 ordering, pinned: the first cue is dispatched **before** the startup work, not after it.
 *
 * This is #160's highest-value target and the reason that issue chose Robolectric over an eighth
 * extraction pass. The fix is not a value or a rule -- it is *an ordering of four calls inside
 * `onStartCommand`* (`engine.tick()` ahead of `persistSnapshot()`, `acquireWakeLock()` and
 * `startForegroundWithNotification()`), which no seam refactor can protect without first rewriting
 * the very ordering under protection.
 *
 * ### Why a shadow is a sufficient instrument here, when it is not for audio
 *
 * The property is an **ordering**, and an ordering is the framework's own bookkeeping rather than
 * physics. "Records that the call happened" is the whole subject, not a weak proxy for it -- the
 * distinction cairn `race-timer-testing-strategy` draws when it grants Robolectric for this and
 * refuses it for the audio path. Nothing here asserts that a cue was *heard*: the cue's audio and
 * haptic delivery is #114 / #144 / #126 and stays a hardware question, guarded from creeping in
 * here by `AudioHapticBoundaryTest`.
 *
 * ### The observation is behavioural, not a spy
 *
 * A [FirstCueProbe] added to the engine sees the first cue fire, and reads the **world** at that
 * instant: no wake lock taken, no foreground notification posted, no race written to disk. Move
 * `engine.tick()` below those three lines and all three readings invert. That is what makes this a
 * pin rather than a restatement of the source in another file.
 */
@RunWith(RobolectricTestRunner::class)
class StartOrderingTest {

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
        var wakeLockHeld = true
        var foregroundEntered = true
        var racePersisted = true
        val probe = FirstCueProbe {
            wakeLockHeld = ShadowPowerManager.getLatestWakeLock()?.isHeld == true
            foregroundEntered = shadowOf(svc).lastForegroundNotification != null
            racePersisted = TimerService.savedSnapshot(svc) != null
        }
        svc.engine.addListener(probe)

        svc.arm()

        // The first cue of every sequence shipped sits at `offsetMs == totalMs`, so it comes due at
        // the instant `start()` anchors the gun and `engine.tick()` dispatches it synchronously.
        // If this ever stops being true the three readings below become vacuous, which is why it is
        // checked rather than assumed.
        assertTrue("the first cue never fired, so nothing below was measured", probe.fired)

        assertFalse("the wake lock was taken before the first cue", wakeLockHeld)
        assertFalse("the service entered the foreground before the first cue", foregroundEntered)
        assertFalse("the race was persisted before the first cue", racePersisted)
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
        // Meaningful only as one half of a pair. `foregroundStartRefused` is initialised to false,
        // so on its own this reddens for no plausible mutation -- an expected-value-of-false
        // assertion with nothing establishing that false was ever in question. The refusal test
        // below is its positive control: same code, same assertion target, opposite answer, and the
        // two differ only by the shadow.
        assertFalse(svc.foregroundStartRefused)
    }

    @Test
    @Config(shadows = [ForegroundRefusingService::class])
    fun `a refused foreground aborts the race rather than running one that cannot survive`() {
        // #13. Before that story the call was bare, and a refusal threw out of `onStartCommand`
        // leaving the sailor looking at a countdown that had **already started** -- `engine.tick()`
        // runs several lines above -- on a screen the platform would kill without notice. The
        // criterion was that the sequence "does not silently start", and the silence was structural.
        //
        // Unreachable without the shadow: Robolectric's stock ShadowService grants the foreground
        // unconditionally, so this arm is not merely untested by default, it cannot be entered.
        val svc = createdService()
        svc.arm()

        assertTrue("the refusal was not latched", svc.foregroundStartRefused)
        // Put back exactly where Stop would leave it, which is the whole point of aborting rather
        // than annotating: the sailor is returned to a pre-start screen, not left holding a race
        // with no service under it.
        assertEquals("the engine was left anchored", TimerState.IDLE, svc.engine.currentState)
        assertNull("the aborted race stayed on disk", TimerService.savedSnapshot(svc))
        assertFalse(
            "the wake lock outlived the aborted race",
            ShadowPowerManager.getLatestWakeLock().isHeld,
        )
        assertTrue("the service did not stop itself", shadowOf(svc).isStoppedBySelf)
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
