package com.racetimer.phone

import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import com.racetimer.shared.BuiltInSequences
import com.racetimer.shared.RestoreOutcome
import com.racetimer.shared.TimerEngine
import com.racetimer.shared.TimerState
import com.racetimer.shared.leadInId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

/**
 * The phone race survives process death (#205): kill and relaunch restore EXACT, a reboot restores
 * DEGRADED, a spent race reports EXPIRED and starts fresh — all decided by the shared plan, with
 * only the SharedPreferences IO on this side.
 *
 * Robolectric within #160's scope: framework bookkeeping (services, prefs, clocks), never audio.
 * The service constructs its real sounder, which degrades inert on this platform.
 */
@RunWith(RobolectricTestRunner::class)
class PhoneRestoreTest {

    private val context get() = ApplicationProvider.getApplicationContext<android.app.Application>()

    private fun service(): PhoneTimerService =
        Robolectric.buildService(PhoneTimerService::class.java).create().get()

    private fun start(svc: PhoneTimerService, fresh: Boolean = false) {
        svc.onStartCommand(
            Intent().setAction(PhoneTimerService.ACTION_START)
                .putExtra(PhoneTimerService.EXTRA_FRESH_START, fresh),
            0, 1,
        )
    }

    @Test
    fun `a killed race comes back exactly where it was`() {
        // Round one: a real race, run by the service, persisted by the arm path's slot.
        val first = Robolectric.buildService(PhoneTimerService::class.java).create()
        first.get().runner.select(BuiltInSequences.scholastic)
        start(first.get())
        assertEquals(TimerState.RUNNING, first.get().runner.engine.currentState)

        // A minute passes; the process dies. Destruction is the closest a JVM gets to force-stop:
        // no cleanup path runs that would clear the snapshot, exactly like a kill.
        ShadowSystemClock.advanceBy(Duration.ofSeconds(60))
        first.destroy()

        // Round two: a fresh process. The launch plan finds the race and names its sequence.
        val second = service()
        val plan = second.launchPlan()
        assertNotNull("the saved race was not offered", plan.resumable)
        val (_, sequence) = plan.resumable!!
        assertEquals(BuiltInSequences.scholastic.id, sequence.id)

        // Resuming restores EXACT: same boot, monotonic domain intact, zero drift.
        second.runner.select(sequence)
        start(second)
        assertEquals(RestoreOutcome.EXACT, second.consumeRestoreNotice())
        assertEquals(TimerState.RUNNING, second.runner.engine.currentState)
        val remaining = second.runner.engine.remainingMs
        assertTrue("expected ~2:00 left, got $remaining", remaining in 115_000..120_000)
    }

    @Test
    fun `a reboot restores degraded from the wall clock, honestly labelled`() {
        val now = SystemClock.elapsedRealtime()
        val wall = System.currentTimeMillis()
        // A capture reading from a boot whose uptime exceeded this one's: the monotonic domain is
        // broken, which is exactly what a reboot leaves behind.
        PhoneRacePersistence(context).persist(
            TimerEngine.Snapshot(
                sequenceId = BuiltInSequences.scholastic.id,
                gunElapsedMs = now + 999_000,
                gunWallMs = wall + 90_000,
                capturedElapsedMs = now + 1_000_000,
            ),
        )

        val svc = service()
        svc.runner.select(BuiltInSequences.scholastic)
        start(svc)

        assertEquals(RestoreOutcome.DEGRADED, svc.consumeRestoreNotice())
        assertEquals(TimerState.RUNNING, svc.runner.engine.currentState)
        val remaining = svc.runner.engine.remainingMs
        assertTrue("expected wall-clock ~1:30, got $remaining", remaining in 85_000..90_000)
    }

    @Test
    fun `a race whose gun fired while dead reports expired and starts fresh`() {
        val now = SystemClock.elapsedRealtime()
        val wall = System.currentTimeMillis()
        PhoneRacePersistence(context).persist(
            TimerEngine.Snapshot(
                sequenceId = BuiltInSequences.scholastic.id,
                gunElapsedMs = now - 5_000,
                gunWallMs = wall - 5_000,
                capturedElapsedMs = now - 60_000,
            ),
        )

        val svc = service()
        svc.runner.select(BuiltInSequences.scholastic)
        start(svc)

        // The officer tapped for a race, so they get one — from the top, said out loud.
        assertEquals(RestoreOutcome.EXPIRED, svc.consumeRestoreNotice())
        assertEquals(TimerState.RUNNING, svc.runner.engine.currentState)
        assertTrue(svc.runner.engine.remainingMs in 175_000..180_000)
    }

    @Test
    fun `a race killed mid-lead-in comes back on the armed variant's own clock`() {
        // The id is the whole mechanism (#205 AC 3): the box-alert variant persists as its
        // id-encoded self, and resolve() rebuilds the armed sequence from nothing else. The base
        // is the race-manager variant because lead-ins are committee work by design — offersLeadIn
        // reads countUpAfterFinish — so this is the exact shape #207 will persist.
        val armedId = leadInId(BuiltInSequences.scholasticRaceManager.id, 60)
        val armed = BuiltInSequences.resolve(armedId)
        assertNotNull("the armed id must resolve or this test is testing nothing", armed)

        val now = SystemClock.elapsedRealtime()
        val wall = System.currentTimeMillis()
        // Mid-lead-in: more than the sequence proper remains. 3:00 + 70 s lead = 250 s total;
        // 220 s out is 30 s into the run-up.
        PhoneRacePersistence(context).persist(
            TimerEngine.Snapshot(
                sequenceId = armedId,
                gunElapsedMs = now + 220_000,
                gunWallMs = wall + 220_000,
                capturedElapsedMs = now - 10_000,
            ),
        )

        val svc = service()
        val plan = svc.launchPlan()
        assertNotNull("mid-lead-in race not offered", plan.resumable)
        assertEquals(armedId, plan.resumable!!.second.id)

        svc.runner.select(plan.resumable!!.second)
        start(svc)
        assertEquals(RestoreOutcome.EXACT, svc.consumeRestoreNotice())
        assertEquals(armedId, svc.runner.selected.id)
        assertTrue(svc.runner.engine.remainingMs in 215_000..220_000)
    }

    @Test
    fun `start over discards the saved race instead of resuming it`() {
        val now = SystemClock.elapsedRealtime()
        val wall = System.currentTimeMillis()
        PhoneRacePersistence(context).persist(
            TimerEngine.Snapshot(
                sequenceId = BuiltInSequences.scholastic.id,
                gunElapsedMs = now + 120_000,
                gunWallMs = wall + 120_000,
                capturedElapsedMs = now - 10_000,
            ),
        )

        val svc = service()
        svc.runner.select(BuiltInSequences.scholastic)
        start(svc, fresh = true)

        // From the top, no notice owed — and the snapshot on disk now describes the new race, not
        // the declined one.
        assertNull(svc.consumeRestoreNotice())
        assertTrue(svc.runner.engine.remainingMs in 175_000..180_000)
    }

    @Test
    fun `the race ending clears the snapshot so nothing dead is ever offered`() {
        val svc = service()
        svc.runner.select(BuiltInSequences.scholastic)
        start(svc)
        assertNotNull("the arm path did not persist", PhoneRacePersistence(context).saved())

        svc.onStartCommand(Intent().setAction(PhoneTimerService.ACTION_STOP), 0, 2)
        assertNull("a stopped race must not survive to be offered", PhoneRacePersistence(context).saved())
    }
}
