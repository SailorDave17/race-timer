package com.racetimer.phone

import android.content.Intent
import android.os.Looper
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import com.racetimer.shared.BuiltInSequences
import com.racetimer.shared.JoinGun
import com.racetimer.shared.RaceSequence
import com.racetimer.shared.SetupChoice
import com.racetimer.shared.TimerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
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
 * The watch's Sync, End Race and pre-start setup, carried out on the phone (#221): this service as
 * the pair's `PairRaces.RaceService`.
 *
 * Which control applies, and to which race, is `:shared`'s rule (`PairRaceTest`, across two simulated
 * devices). What only this module can show is that a control the rule hands here is carried out as
 * the phone's own is — above all that a gun the watch moved re-sizes the wake lock exactly as
 * `ACTION_SYNC` does, so #126's defect class is not reborn through the remote path (#221 AC 4).
 * Robolectric within #160's scope: the framework's bookkeeping and the engine's own state.
 */
@RunWith(RobolectricTestRunner::class)
class PairControlsTest {

    private fun createdService(): PhoneTimerService =
        Robolectric.buildService(PhoneTimerService::class.java).create().get()

    private fun startedService(sequence: RaceSequence = BuiltInSequences.usSailing): PhoneTimerService =
        createdService().apply {
            runner.select(sequence)
            onStartCommand(Intent().setAction(PhoneTimerService.ACTION_START), 0, 1)
        }

    private fun persistence() =
        PhoneRacePersistence(ApplicationProvider.getApplicationContext<android.app.Application>())

    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    // --- AC 1 and 4: the watch's Sync -------------------------------------------------------------

    @Test
    fun `the watch's Sync moves the phone's gun, persisted, and re-sizes the wake lock as a Sync here does`() {
        val svc = startedService()
        val first = ShadowPowerManager.getLatestWakeLock()
        assertTrue("the positive control: a race holds a lock", first.isHeld)
        // The watch rounded up to 5:00, eight seconds later than the gun the phone started with.
        val moved = svc.runner.engine.snapshot()!!.gunElapsedMs + 8_000L

        assertTrue(svc.moveGun(JoinGun(moved, 30L)))

        assertEquals(moved, svc.runner.engine.snapshot()?.gunElapsedMs)
        assertEquals("a restore comes back to the moved gun", moved, persistence().saved()?.gunElapsedMs)
        // The #126 re-compute, on the remote path: a fresh lock sized from the race as it now reads,
        // the old one released rather than orphaned. The sizing itself is PhoneWakeLockTest's.
        val second = ShadowPowerManager.getLatestWakeLock()
        assertNotSame("the watch's Sync did not re-acquire the lock", first, second)
        assertTrue(second.isHeld)
        assertFalse("the replaced lock leaked", first.isHeld)
    }

    @Test
    fun `a gun the watch moved outside the budget is flagged in a Sync's words, and one inside clears it`() {
        val svc = startedService()
        val gun = svc.runner.engine.snapshot()!!.gunElapsedMs

        svc.moveGun(JoinGun(gun + 8_000L, 240L))
        assertEquals("Watch sync ±240 ms — tap Sync to confirm", svc.pairJoinNotice)

        svc.moveGun(JoinGun(gun + 8_000L, 40L))
        assertNull(svc.pairJoinNotice)
    }

    @Test
    fun `with no countdown there is no gun to move and no lock is taken`() {
        val svc = createdService()

        assertFalse(svc.moveGun(JoinGun(SystemClock.elapsedRealtime() + 60_000L, 30L)))

        assertEquals(TimerState.IDLE, svc.runner.engine.currentState)
        assertNull("nothing ever took a lock", ShadowPowerManager.getLatestWakeLock())
    }

    // --- AC 2: the watch's End Race ---------------------------------------------------------------

    private fun countingUp(): PhoneTimerService {
        val sequence = BuiltInSequences.scholasticRaceManager
        return startedService(sequence).also { idle(sequence.totalMs + 45_300L) }
    }

    @Test
    fun `the watch's End Race freezes the watch's time here and winds the service down as End Race does`() {
        val svc = countingUp()
        assertEquals("the positive control: counting up", TimerState.COUNTING_UP, svc.runner.engine.currentState)
        assertNotNull("and saved", persistence().saved())

        assertEquals(45_000L, svc.endRaceAt(45_000L))

        assertEquals(TimerState.RACE_ENDED, svc.runner.engine.currentState)
        assertEquals("the watch's time, not this phone's reading", -45_000L, svc.runner.engine.remainingMs)
        assertFalse("wake lock survived", ShadowPowerManager.getLatestWakeLock().isHeld)
        assertTrue("still foreground", shadowOf(svc).isForegroundStopped)
        assertNull("the ended race is still restorable", persistence().saved())
    }

    @Test
    fun `End Race taken here freezes the time the watch will be told`() {
        val svc = countingUp()

        svc.onStartCommand(Intent().setAction(PhoneTimerService.ACTION_END_RACE), 0, 2)
        val frozen = svc.runner.engine.remainingMs

        assertEquals("a later end from the watch does not undo this one", -frozen, svc.endRaceAt(-frozen + 900L))
        assertEquals(frozen, svc.runner.engine.remainingMs)
    }

    @Test
    fun `there is nothing to end before the gun`() {
        val svc = startedService(BuiltInSequences.scholasticRaceManager)

        assertNull(svc.endRaceAt(1_000L))

        assertEquals(TimerState.RUNNING, svc.runner.engine.currentState)
        assertFalse(shadowOf(svc).isForegroundStopped)
    }

    // --- AC 5: the watch's setup ------------------------------------------------------------------

    @Test
    fun `the watch's setup is the phone's selection at its pre-start screen`() {
        val svc = createdService()
        assertTrue(svc.atPreStart())

        svc.applySetup(SetupChoice(BuiltInSequences.custom(8).id, 60))

        assertEquals(BuiltInSequences.custom(8), svc.runner.selected)
        assertEquals("the next Start runs it", BuiltInSequences.custom(8).totalMs, svc.runner.engine.remainingMs)
    }

    @Test
    fun `the watch's setup never loads over a frozen summary the committee is reading`() {
        // A summary, not a running race: `runner.select` already refuses over RUNNING and COUNTING_UP
        // (#281), so a running race could not tell whether this service's own pre-start guard exists.
        // Over RACE_ENDED only that guard stands between the setup and a load that would blank the
        // final time.
        val svc = countingUp()
        svc.onStartCommand(Intent().setAction(PhoneTimerService.ACTION_END_RACE), 0, 2)
        val frozen = svc.runner.engine.remainingMs
        assertFalse(svc.atPreStart())

        svc.applySetup(SetupChoice(BuiltInSequences.scholastic.id, 0))

        assertEquals(TimerState.RACE_ENDED, svc.runner.engine.currentState)
        assertEquals("the final time is still on screen", frozen, svc.runner.engine.remainingMs)
        assertEquals(BuiltInSequences.scholasticRaceManager, svc.runner.selected)
    }

    @Test
    fun `a GO still on screen is not the pre-start screen`() {
        val svc = startedService(BuiltInSequences.club)
        idle(BuiltInSequences.club.totalMs + 10_000L)
        assertEquals("the positive control: past the gun, torn down", TimerState.FINISHED, svc.runner.engine.currentState)

        assertFalse(svc.atPreStart())
    }
}
