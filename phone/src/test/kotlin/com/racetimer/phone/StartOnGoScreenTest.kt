package com.racetimer.phone

import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.racetimer.shared.BuiltInSequences
import com.racetimer.shared.SequenceCue
import com.racetimer.shared.TimerListener
import com.racetimer.shared.TimerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowPowerManager
import java.time.Duration

/**
 * A Start tapped on the "GO!" screen, inside the last race's post-gun linger (#327).
 *
 * A countdown's gun posts a teardown the gun cue's length plus the linger out, and the teardown acts
 * on **whatever race is running when it fires**. On the phone it leaves the engine counting and takes
 * everything around it: the foreground, the wake lock and the persisted snapshot. So the new race
 * dies at the first screen-off and cannot be restored. #220 cancelled the teardown for a race joined
 * from the watch (`PairJoinTest`); a Start tapped here did not. Robolectric within #160's scope: the
 * service's bookkeeping, never the audio.
 */
@RunWith(RobolectricTestRunner::class)
class StartOnGoScreenTest {

    private val looper get() = shadowOf(Looper.getMainLooper())

    private fun createdService(): PhoneTimerService =
        Robolectric.buildService(PhoneTimerService::class.java).create().get()

    private fun persistence() =
        PhoneRacePersistence(ApplicationProvider.getApplicationContext<android.app.Application>())

    /** The Start both screens send, as `PhoneTimerService.start` builds it from `onStartRace`. */
    private fun startIntent() =
        Intent().setAction(PhoneTimerService.ACTION_START).putExtra(PhoneTimerService.EXTRA_FRESH_START, false)

    /** A club race run to just past its gun: "GO!" on screen, the teardown pending. */
    private fun inTheLinger(): PhoneTimerService {
        val svc = createdService()
        svc.runner.select(BuiltInSequences.club)
        svc.onStartCommand(startIntent(), 0, 1)
        looper.idleFor(Duration.ofMillis(BuiltInSequences.club.totalMs + 200L))
        assertEquals("the positive control: the gun has fired and GO! is up", TimerState.FINISHED, svc.runner.engine.currentState)
        return svc
    }

    /**
     * Past the old race's teardown. It is due the club gun's length (one long and three short blasts,
     * under 2 s) plus the 3 s linger after that gun, and the tap lands 0.2 s after it, so this is
     * twice the delay. `the linger takes a finished race out of the foreground inside the same window`
     * proves the window reaches it.
     */
    private val pastTheOldTeardown = Duration.ofMillis(10_000L)

    private class Guns : TimerListener {
        var count = 0
        override fun onCue(cue: SequenceCue) {}
        override fun onGun() {
            count++
        }

        override fun onTick(remainingMs: Long) {}
        override fun onSync(snappedToMs: Long) {}
    }

    @Test
    fun `the linger takes a finished race out of the foreground inside the same window`() {
        // The control for the test below: with no new Start, the window it waits out does contain the
        // teardown, so a race keeping its foreground through it is a race the teardown did not reach.
        val svc = inTheLinger()

        looper.idleFor(pastTheOldTeardown)

        assertTrue(shadowOf(svc).isForegroundStopped)
        assertTrue(shadowOf(svc).isStoppedBySelf)
    }

    @Test
    fun `a Start tapped in the linger keeps its foreground, wake lock and snapshot past the old teardown`() {
        val svc = inTheLinger()

        svc.onStartCommand(startIntent(), 0, 2)
        val gun = svc.runner.engine.snapshot()?.gunElapsedMs
        assertNotNull("the tap started a race", gun)
        looper.idleFor(pastTheOldTeardown)

        assertEquals("the new race is still counting", TimerState.RUNNING, svc.runner.engine.currentState)
        assertTrue("its wake lock is held", ShadowPowerManager.getLatestWakeLock().isHeld)
        assertFalse("it is still in the foreground", shadowOf(svc).isForegroundStopped)
        assertFalse(shadowOf(svc).isStoppedBySelf)
        assertEquals("the persisted race is the new one", gun, persistence().saved()?.gunElapsedMs)
    }

    @Test
    fun `the restarted race fires its own gun, and its own linger still leaves the foreground`() {
        val svc = inTheLinger()
        val guns = Guns().also { svc.runner.engine.addListener(it) }

        svc.onStartCommand(startIntent(), 0, 2)
        looper.idleFor(Duration.ofMillis(BuiltInSequences.club.totalMs + 200L))

        assertEquals("the new race's gun", 1, guns.count)
        assertFalse("still in the foreground through its own gun cue", shadowOf(svc).isForegroundStopped)

        looper.idleFor(pastTheOldTeardown)

        assertTrue("its teardown ran, as any countdown's does", shadowOf(svc).isForegroundStopped)
        assertFalse("and released the wake lock", ShadowPowerManager.getLatestWakeLock().isHeld)
    }
}
