package com.racetimer.wear

import android.os.Looper
import android.os.SystemClock
import com.racetimer.shared.BuiltInSequences
import com.racetimer.shared.JoinGun
import com.racetimer.shared.RaceSequence
import com.racetimer.shared.TimerListener
import com.racetimer.shared.SequenceCue
import com.racetimer.shared.TimerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * The phone's Sync and End Race, carried out on the watch (#221): this service as the pair's
 * `PairRaces.RaceService`.
 *
 * Which control applies, and to which race, is `:shared`'s rule (`PairRaceTest`). What only this
 * module can show is that a control the rule hands here is carried out as the watch's own is — above
 * all that a gun the phone moved re-sizes the wake lock exactly as `ACTION_SYNC` does, so #126's
 * defect class is not reborn through the remote path (#221 AC 4). Robolectric within #160's scope:
 * the framework's bookkeeping and the engine's own events, never the audio.
 */
@RunWith(RobolectricTestRunner::class)
class PairControlsTest {

    private fun createdService(): TimerService =
        Robolectric.buildService(TimerService::class.java).create().get()

    private fun startedService(sequence: RaceSequence = BuiltInSequences.usSailing): TimerService =
        createdService().apply {
            onStartCommand(TimerService.startIntent(this, sequence.id, freshStart = true), 0, 1)
        }

    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private class Synced : TimerListener {
        val heard = mutableListOf<Long>()
        override fun onCue(cue: SequenceCue) {}
        override fun onGun() {}
        override fun onTick(remainingMs: Long) {}
        override fun onSync(snappedToMs: Long) {
            heard += snappedToMs
        }
    }

    // --- AC 1 and 4: the phone's Sync -------------------------------------------------------------

    @Test
    fun `the phone's Sync moves the watch's gun, persisted, and re-sizes the wake lock as a Sync here does`() {
        val svc = startedService()
        val first = ShadowPowerManager.getLatestWakeLock()
        assertTrue("the positive control: a race holds a lock", first.isHeld)
        val moved = svc.engine.snapshot()!!.gunElapsedMs + 8_000L

        assertTrue(svc.moveGun(JoinGun(moved, 30L)))

        assertEquals(moved, svc.engine.snapshot()?.gunElapsedMs)
        assertEquals("a restore comes back to the moved gun", moved, TimerService.savedSnapshot(svc)?.gunElapsedMs)
        val second = ShadowPowerManager.getLatestWakeLock()
        assertTrue("the phone's Sync did not re-acquire the lock", first !== second)
        assertTrue(second.isHeld)
        assertFalse("the replaced lock leaked", first.isHeld)
    }

    @Test
    fun `the phone's Sync is heard here as a Sync, as the owner's rule asks`() {
        val svc = startedService()
        val synced = Synced().also { svc.engine.addListener(it) }

        svc.moveGun(JoinGun(svc.engine.snapshot()!!.gunElapsedMs + 8_000L, 30L))

        // The service's own listener takes the same path: buzzed, beeped and persisted.
        assertEquals(1, synced.heard.size)
    }

    @Test
    fun `a gun the phone moved outside the budget is flagged in a Sync's words, and one inside clears it`() {
        val svc = startedService()
        val gun = svc.engine.snapshot()!!.gunElapsedMs

        svc.moveGun(JoinGun(gun + 8_000L, 240L))
        assertEquals("Phone sync ±240 ms — tap Sync to confirm", svc.pairJoinNotice)

        svc.moveGun(JoinGun(gun + 8_000L, 40L))
        assertNull(svc.pairJoinNotice)
    }

    @Test
    fun `with no countdown there is no gun to move and no lock is taken`() {
        val svc = createdService()

        assertFalse(svc.moveGun(JoinGun(SystemClock.elapsedRealtime() + 60_000L, 30L)))

        assertEquals(TimerState.IDLE, svc.engine.currentState)
        assertNull("nothing ever took a lock", ShadowPowerManager.getLatestWakeLock())
    }

    // --- #222: a reconnect's correction, and a gap beyond it ----------------------------------------

    @Test
    fun `a reconnect's correction moves the gun, persisted, re-sizes the lock, and is not heard as a Sync`() {
        val svc = startedService()
        val synced = Synced().also { svc.engine.addListener(it) }
        val first = ShadowPowerManager.getLatestWakeLock()
        val corrected = svc.engine.snapshot()!!.gunElapsedMs - 60L

        assertTrue(svc.correctGun(JoinGun(corrected, 30L)))

        assertEquals(corrected, svc.engine.snapshot()?.gunElapsedMs)
        assertEquals("a restore comes back to the corrected gun", corrected, TimerService.savedSnapshot(svc)?.gunElapsedMs)
        assertTrue("the correction did not re-acquire the lock", first !== ShadowPowerManager.getLatestWakeLock())
        assertFalse(first.isHeld)
        // The negative half of the phone's Sync test above: the service buzzes and beeps from the
        // same listener path, so nothing reaching it is what keeps a correction silent.
        assertTrue("nobody tapped Sync, and nothing heard one", synced.heard.isEmpty())
    }

    @Test
    fun `a gap beyond the bound stands on the prompt until a Sync here, and a correction inside the budget clears it`() {
        val svc = startedService()
        assertTrue(svc.gunsApart(-8_000L))
        assertEquals("Phone gun 8.0 s apart — tap Sync to confirm", svc.pairJoinNotice)

        svc.onStartCommand(TimerService.syncIntent(svc), 0, 2)
        assertNull("the officer synced against the flag", svc.pairJoinNotice)

        svc.gunsApart(250L)
        svc.correctGun(JoinGun(svc.engine.snapshot()!!.gunElapsedMs, 30L))
        assertNull(svc.pairJoinNotice)
    }

    @Test
    fun `past the gun, or with no race, there is nothing to correct and no Sync to ask for`() {
        assertFalse(createdService().correctGun(JoinGun(SystemClock.elapsedRealtime() + 60_000L, 30L)))
        assertFalse(createdService().gunsApart(8_000L))

        val sequence = BuiltInSequences.scholasticRaceManager
        val svc = startedService(sequence)
        idle(sequence.totalMs + 5_000L)
        assertEquals("the positive control: counting up", TimerState.COUNTING_UP, svc.engine.currentState)
        assertFalse(svc.correctGun(JoinGun(svc.engine.snapshot()!!.gunElapsedMs + 50L, 30L)))
        assertFalse(svc.gunsApart(8_000L))
        assertNull(svc.pairJoinNotice)
    }

    // --- AC 2: the phone's End Race ---------------------------------------------------------------

    @Test
    fun `the phone's End Race freezes the phone's time here and leaves the summary up for Done`() {
        val sequence = BuiltInSequences.scholasticRaceManager
        val svc = startedService(sequence)
        idle(sequence.totalMs + 45_300L)
        assertEquals("the positive control: counting up", TimerState.COUNTING_UP, svc.engine.currentState)

        assertEquals(45_000L, svc.endRaceAt(45_000L))

        assertEquals(TimerState.RACE_ENDED, svc.engine.currentState)
        assertEquals("the phone's time, not this watch's reading", -45_000L, svc.engine.remainingMs)
        // As End Race here: the summary stays on the notification until the committee taps Done.
        assertFalse("the summary was torn down", shadowOf(svc).isForegroundStopped)
        idle(5_000L)
        assertEquals(-45_000L, svc.engine.remainingMs)
    }

    @Test
    fun `there is nothing to end before the gun`() {
        val svc = startedService(BuiltInSequences.scholasticRaceManager)

        assertNull(svc.endRaceAt(1_000L))

        assertEquals(TimerState.RUNNING, svc.engine.currentState)
    }

    // --- AC 5: the pre-start screen's state -------------------------------------------------------

    @Test
    fun `only an idle watch is at its pre-start screen`() {
        val svc = createdService()
        assertTrue(svc.atPreStart())

        svc.onStartCommand(TimerService.startIntent(svc, BuiltInSequences.club.id, freshStart = true), 0, 1)
        assertFalse("a countdown", svc.atPreStart())

        idle(BuiltInSequences.club.totalMs + 200L)
        assertEquals(TimerState.FINISHED, svc.engine.currentState)
        assertFalse("GO! still on screen", svc.atPreStart())

        idle(10_000L)
        assertTrue("back at the top once the gun's linger has run", svc.atPreStart())
    }
}
