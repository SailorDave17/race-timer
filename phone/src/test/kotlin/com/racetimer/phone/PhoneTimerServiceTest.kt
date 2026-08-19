package com.racetimer.phone

import android.app.NotificationManager
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.racetimer.shared.BuiltInSequences
import com.racetimer.shared.RaceSequence
import com.racetimer.shared.TimerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowPowerManager

/**
 * The service half of #203: the arm path takes the lock and the foreground, sync re-sizes the
 * lock, stop releases everything.
 *
 * Robolectric within #160's scope — these assertions read the framework's own bookkeeping (wake
 * locks, notifications, foreground state), never the audio path. The service does construct the
 * real `PhoneCueSounder`; on Robolectric's platform that path reports no audio output and runs
 * inert, which is the same graceful degradation a speakerless device gets.
 */
@RunWith(RobolectricTestRunner::class)
class PhoneTimerServiceTest {

    private fun startedService(): PhoneTimerService {
        val svc = Robolectric.buildService(PhoneTimerService::class.java).create().get()
        svc.onStartCommand(Intent().setAction(PhoneTimerService.ACTION_START), 0, 1)
        return svc
    }

    @Test
    fun `arming takes a partial wake lock and enters the foreground with the countdown posted`() {
        val svc = startedService()

        val lock = ShadowPowerManager.getLatestWakeLock()
        assertNotNull("no wake lock acquired", lock)
        assertTrue("lock not held", lock.isHeld)

        val notification = shadowOf(svc).lastForegroundNotification
        assertNotNull("service never entered the foreground", notification)
        assertEquals(RaceTimerPhoneApplication.TIMER_CHANNEL_ID, notification.channelId)
        // The alpha-only mark, not the adaptive launcher foreground (#203 AC 4).
        assertEquals(R.drawable.ic_stat_race_timer, shadowOf(notification.smallIcon).resId)
        assertFalse(svc.foregroundStartRefused)
    }

    @Test
    fun `the channel the notification posts on exists before the service ever runs`() {
        // Created by RaceTimerPhoneApplication.onCreate — the Application init the manifest names —
        // not by the service. Asserted by reading the manager back, so a channel quietly moved into
        // the service (where a second component could race it) fails here.
        val nm = ApplicationProvider.getApplicationContext<android.app.Application>()
            .getSystemService(NotificationManager::class.java)
        val channel = nm.getNotificationChannel(RaceTimerPhoneApplication.TIMER_CHANNEL_ID)
        assertNotNull("channel missing at Application init", channel)
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
    }

    @Test
    fun `sync re-acquires the lock so a gun moved later is still covered`() {
        val svc = startedService()
        val first = ShadowPowerManager.getLatestWakeLock()
        assertTrue(first.isHeld)

        svc.onStartCommand(Intent().setAction(PhoneTimerService.ACTION_SYNC), 0, 2)

        // The #126 re-compute: a fresh lock, sized from the remaining race as it reads NOW, and the
        // old one released rather than orphaned. The sizing arithmetic itself is PhoneWakeLockTest.
        val second = ShadowPowerManager.getLatestWakeLock()
        assertNotSame("sync did not re-acquire the lock", first, second)
        assertTrue(second.isHeld)
        assertFalse("the replaced lock leaked", first.isHeld)
    }

    @Test
    fun `stop releases the lock and leaves the foreground`() {
        val svc = startedService()
        svc.onStartCommand(Intent().setAction(PhoneTimerService.ACTION_STOP), 0, 2)

        assertFalse("wake lock survived the stop", ShadowPowerManager.getLatestWakeLock().isHeld)
        assertTrue("service still foreground", shadowOf(svc).isForegroundStopped)
        assertTrue("service did not stop itself", shadowOf(svc).isStoppedBySelf)
    }

    // --- #206: the race-manager count-up and its teardown -----------------------

    /**
     * Start [sequence] on a fresh service and drive the engine past its gun.
     *
     * The clock here is the real `SystemMonotonicClock` — this is the *service's* own runner, not
     * one a test constructed with a fake — so the race cannot be wound forward directly. Idling the
     * main looper is what moves it: the posted tick then runs with the gun behind it, which is the
     * same call that fires the gun in production rather than a test-only shortcut.
     */
    private fun servicePastTheGun(
        sequence: RaceSequence,
        pastGunMs: Long = 2_000L,
    ): PhoneTimerService {
        val svc = Robolectric.buildService(PhoneTimerService::class.java).create().get()
        svc.runner.select(sequence)
        svc.onStartCommand(Intent().setAction(PhoneTimerService.ACTION_START), 0, 1)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(sequence.totalMs + pastGunMs))
        return svc
    }

    private fun persistence() =
        PhoneRacePersistence(ApplicationProvider.getApplicationContext<android.app.Application>())

    @Test
    fun `the gun leaves a race-manager race running, foreground, and restorable`() {
        // Twelve seconds, not two, and the reason is the whole force of this test: the sailor
        // teardown is *scheduled* at the gun and runs six seconds later, so at two seconds past the
        // gun "still foreground" is true of a race-manager race and of a mis-branched sailor one
        // alike. Only an arrangement past the point a teardown would have fired can tell "no
        // teardown was scheduled" from "one was scheduled and has not run yet".
        val svc = servicePastTheGun(BuiltInSequences.scholasticRaceManager, pastGunMs = 12_000L)

        assertEquals(TimerState.COUNTING_UP, svc.runner.engine.currentState)
        // Still foreground: the count-up IS the race, and a service that tore down at the gun would
        // take the notification and the tick loop with it.
        assertFalse("service left the foreground at the gun", shadowOf(svc).isForegroundStopped)
        // The snapshot survives, and that is the shared RestorePlan rule doing real work rather
        // than an oversight: `recoverableRemainingMs` calls a countUpAfterFinish race past its gun
        // recoverable precisely because past the gun is where it lives. Clearing here would make
        // the one race long enough to outlive its process the one race that could not come back.
        assertNotNull("the count-up's snapshot was cleared at the gun", persistence().saved())
    }

    @Test
    fun `End Race releases the lock, leaves the foreground, and clears the snapshot`() {
        val svc = servicePastTheGun(BuiltInSequences.scholasticRaceManager, pastGunMs = 12_000L)
        // The precondition, asserted rather than assumed: without it the clear below holds just as
        // well on a race that never saved anything, and this would be measuring nothing.
        assertNotNull("nothing saved to clear — the arrangement failed", persistence().saved())

        svc.onStartCommand(Intent().setAction(PhoneTimerService.ACTION_END_RACE), 0, 2)

        assertEquals(TimerState.RACE_ENDED, svc.runner.engine.currentState)
        assertFalse("wake lock survived End Race", ShadowPowerManager.getLatestWakeLock().isHeld)
        assertTrue("service still foreground after End Race", shadowOf(svc).isForegroundStopped)
        // A snapshot surviving End Race would restore as a *running* count-up — the same
        // recoverability rule that protects the race above is what makes leaving it here dangerous
        // — and un-freeze the race the committee has just closed.
        assertNull("the ended race is still restorable", persistence().saved())
    }

    @Test
    fun `a sailor sequence still tears down at the gun`() {
        // Far enough past the gun for the teardown to have *run*, which is not the same instant as
        // the gun: it is scheduled at the gun cue's own length plus GUN_LINGER_MS, so the sustained
        // three-second gun puts it six seconds out. Arranged at two seconds on the first run of
        // this test and it failed here — correctly, and on the arrangement rather than on the code.
        val svc = servicePastTheGun(BuiltInSequences.scholastic, pastGunMs = 12_000L)

        // The negative control for the whole story: the branch added for the race-manager modes
        // must not have changed what happens to every other sequence. Without this, dropping the
        // `countUpAfterFinish` condition would turn every sailor sequence into a stopwatch that
        // never stops, and every assertion above would still pass.
        assertEquals(TimerState.FINISHED, svc.runner.engine.currentState)
        assertTrue("sailor sequence stayed foreground past its gun", shadowOf(svc).isForegroundStopped)
        assertFalse("wake lock survived the gun", ShadowPowerManager.getLatestWakeLock().isHeld)
    }

    @Test
    fun `the runner's race survives the activity unbinding but not the service's death`() {
        val controller = Robolectric.buildService(PhoneTimerService::class.java).create()
        val svc = controller.get()
        svc.onStartCommand(Intent().setAction(PhoneTimerService.ACTION_START), 0, 1)
        assertEquals(
            com.racetimer.shared.TimerState.RUNNING,
            svc.runner.engine.currentState,
        )

        controller.destroy()
        // Destruction released the lock — the one resource that would otherwise outlive the race.
        assertFalse(ShadowPowerManager.getLatestWakeLock().isHeld)
    }
}
