package com.racetimer.phone

import android.app.NotificationManager
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
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
