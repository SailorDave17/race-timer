package com.racetimer.wear

import android.os.Looper
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
 * on **whatever race is running when it fires**: it resets the engine and leaves the foreground. #220
 * cancelled it for a race joined from the phone (`PairJoinTest`). A Start tapped here takes the same
 * arm and did not, so the new race was reset to IDLE a few seconds in. Robolectric within #160's
 * scope: the service's bookkeeping, never the audio.
 */
@RunWith(RobolectricTestRunner::class)
class StartOnGoScreenTest {

    private val looper get() = shadowOf(Looper.getMainLooper())

    private fun createdService(): TimerService =
        Robolectric.buildService(TimerService::class.java).create().get()

    /** A club race run to just past its gun: "GO!" on screen, the teardown pending. */
    private fun inTheLinger(): TimerService {
        val svc = createdService()
        svc.onStartCommand(TimerService.startIntent(svc, BuiltInSequences.club.id, freshStart = true), 0, 1)
        looper.idleFor(Duration.ofMillis(BuiltInSequences.club.totalMs + 200L))
        assertEquals("the positive control: the gun has fired and GO! is up", TimerState.FINISHED, svc.engine.currentState)
        return svc
    }

    /** The "GO!" screen's Start, as `MainActivity.handleStart` sends it: no fresh-start flag. */
    private fun TimerService.tapStart() =
        onStartCommand(TimerService.startIntent(this, BuiltInSequences.club.id), 0, 2)

    /**
     * Past the old race's teardown. It is due the club gun's length (one long and three short blasts,
     * under 2 s) plus the 3 s linger after that gun, and the tap lands 0.2 s after it, so this is
     * twice the delay. `the linger tears down a finished race inside the same window` proves the
     * window reaches it.
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
    fun `the linger tears down a finished race inside the same window`() {
        // The control for the test below: with no new Start, the window it waits out does contain the
        // teardown, so a race surviving it is a race the teardown did not reach.
        val svc = inTheLinger()

        looper.idleFor(pastTheOldTeardown)

        assertEquals(TimerState.IDLE, svc.engine.currentState)
        assertTrue(shadowOf(svc).isStoppedBySelf)
    }

    @Test
    fun `a Start tapped in the linger keeps its race, foreground, wake lock and snapshot past the old teardown`() {
        val svc = inTheLinger()

        svc.tapStart()
        val gun = svc.engine.snapshot()?.gunElapsedMs
        assertNotNull("the tap started a race", gun)
        looper.idleFor(pastTheOldTeardown)

        assertEquals("the new race is still counting", TimerState.RUNNING, svc.engine.currentState)
        assertTrue("its wake lock is held", ShadowPowerManager.getLatestWakeLock().isHeld)
        assertFalse("it is still in the foreground", shadowOf(svc).isForegroundStopped)
        assertFalse(shadowOf(svc).isStoppedBySelf)
        assertEquals("the persisted race is the new one", gun, TimerService.savedSnapshot(svc)?.gunElapsedMs)
    }

    @Test
    fun `the restarted race fires its own gun, and its own linger still tears it down`() {
        val svc = inTheLinger()
        val guns = Guns().also { svc.engine.addListener(it) }

        svc.tapStart()
        looper.idleFor(Duration.ofMillis(BuiltInSequences.club.totalMs + 200L))

        assertEquals("the new race's gun", 1, guns.count)
        assertEquals(TimerState.FINISHED, svc.engine.currentState)

        looper.idleFor(pastTheOldTeardown)

        assertEquals("its teardown ran, as any countdown's does", TimerState.IDLE, svc.engine.currentState)
        assertTrue(shadowOf(svc).isStoppedBySelf)
    }
}
