package com.racetimer.phone

import android.content.Intent
import android.os.Looper
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
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
 * A race the watch started, run on the phone (#220): [PhoneTimerService.ACTION_JOIN].
 *
 * The rule deciding whether to join is `:shared`'s (`PairRaceTest`). This is the phone half of what
 * only the app can show: a join the rule sends here is armed as a start is — wake lock, foreground,
 * persisted — on the gun it was given, with the late warning sounded and the flag owed when the gun
 * could not be vouched for. Robolectric within #160's scope: bookkeeping and engine events, no audio.
 */
@RunWith(RobolectricTestRunner::class)
class PairJoinTest {

    private fun createdService(): PhoneTimerService =
        Robolectric.buildService(PhoneTimerService::class.java).create().get()

    private fun PhoneTimerService.join(
        sequence: RaceSequence,
        gunMs: Long,
        boundMs: Long? = 30L,
        startId: Int = 1,
    ) = onStartCommand(
        PhoneTimerService.joinIntent(
            this,
            PairJoin(
                PairRace(RaceKey(1L, 1L), sequence.id, JoinGun(gunMs, boundMs), startedHere = false),
                sequence,
                JOIN_LATE_CUE_GRACE_MS,
            ),
        ),
        0,
        startId,
    )

    private fun gunTappedAgo(sequence: RaceSequence, lateMs: Long): Long =
        SystemClock.elapsedRealtime() - lateMs + sequence.totalMs

    private fun persistence() =
        PhoneRacePersistence(ApplicationProvider.getApplicationContext<android.app.Application>())

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
    fun `a joined race runs to the watch's gun, armed as a start is`() {
        val svc = createdService()
        val heard = Heard().also { svc.runner.engine.addListener(it) }
        val gun = gunTappedAgo(BuiltInSequences.usSailing, lateMs = 150L)

        svc.join(BuiltInSequences.usSailing, gun)

        assertEquals(TimerState.RUNNING, svc.runner.engine.currentState)
        assertEquals("the watch's sequence is now the phone's", BuiltInSequences.usSailing, svc.runner.selected)
        assertEquals("anchored to the given gun", gun, svc.runner.engine.snapshot()?.gunElapsedMs)
        assertEquals("the warning, 150 ms late, sounded", listOf(5 * 60_000L), heard.offsets)
        assertTrue(ShadowPowerManager.getLatestWakeLock().isHeld)
        assertNotNull(shadowOf(svc).lastForegroundNotification)
        assertEquals("persisted on the joined gun", gun, persistence().saved()?.gunElapsedMs)
    }

    @Test
    fun `a join inside the budget says nothing`() {
        val svc = createdService()
        svc.join(BuiltInSequences.club, gunTappedAgo(BuiltInSequences.club, 100L), boundMs = PAIR_BUDGET)

        assertNull(svc.pairJoinNotice)
    }

    @Test
    fun `a join the link could not place inside the budget is flagged until Sync`() {
        val svc = createdService()
        svc.join(BuiltInSequences.club, gunTappedAgo(BuiltInSequences.club, 100L), boundMs = 240L)

        assertEquals("Watch start ±240 ms — tap Sync to confirm", svc.pairJoinNotice)
        svc.onStartCommand(Intent().setAction(PhoneTimerService.ACTION_SYNC), 0, 2)
        assertNull(svc.pairJoinNotice)
    }

    @Test
    fun `an unmeasured join says so, and Stop clears it`() {
        val svc = createdService()
        svc.join(BuiltInSequences.club, gunTappedAgo(BuiltInSequences.club, 100L), boundMs = null)

        assertEquals("Watch start unmeasured — tap Sync to confirm", svc.pairJoinNotice)
        svc.onStartCommand(Intent().setAction(PhoneTimerService.ACTION_STOP), 0, 2)
        assertNull(svc.pairJoinNotice)
    }

    @Test
    fun `a start made here carries no joined race's flag`() {
        // The one window where it could: a joined race has fired its gun, the "GO!" screen offers
        // Start, and the teardown that would clear the flag is still lingering.
        val svc = createdService()
        svc.join(BuiltInSequences.club, gunTappedAgo(BuiltInSequences.club, 100L), boundMs = null)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(BuiltInSequences.club.totalMs + 200L))
        assertEquals(TimerState.FINISHED, svc.runner.engine.currentState)
        assertNotNull("the positive control: the flag is still up in the linger", svc.pairJoinNotice)

        svc.onStartCommand(
            Intent().setAction(PhoneTimerService.ACTION_START).putExtra(PhoneTimerService.EXTRA_FRESH_START, true),
            0,
            2,
        )

        assertNull(svc.pairJoinNotice)
    }

    @Test
    fun `a join inside the last race's post-gun linger is not torn down by it`() {
        val svc = createdService()
        svc.runner.select(BuiltInSequences.club)
        svc.onStartCommand(Intent().setAction(PhoneTimerService.ACTION_START), 0, 1)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(BuiltInSequences.club.totalMs + 200L))
        assertEquals("the positive control: the gun has fired", TimerState.FINISHED, svc.runner.engine.currentState)

        svc.join(BuiltInSequences.usSailing, gunTappedAgo(BuiltInSequences.usSailing, 100L), startId = 2)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10_000L))

        assertEquals("the joined race survived the old race's teardown", TimerState.RUNNING, svc.runner.engine.currentState)
        assertFalse(shadowOf(svc).isStoppedBySelf)
    }

    @Test
    fun `a join whose gun has passed leaves an idle phone as it was and keeps its foreground promise`() {
        val svc = createdService()

        svc.join(BuiltInSequences.club, gunMs = SystemClock.elapsedRealtime() - 1L)

        assertEquals(TimerState.IDLE, svc.runner.engine.currentState)
        assertNull(persistence().saved())
        // The id, not the notification: stopForeground(REMOVE) nulls the latter in the shadow, and
        // only startForeground ever sets the former.
        assertEquals(
            "startForeground was still called",
            RaceTimerPhoneApplication.TIMER_NOTIFICATION_ID,
            shadowOf(svc).lastForegroundNotificationId,
        )
        assertTrue(shadowOf(svc).isForegroundStopped)
        assertTrue(shadowOf(svc).isStoppedBySelf)
    }

    private companion object {
        /** D2, restated: a bound exactly at the budget is inside it. */
        const val PAIR_BUDGET = 100L
    }
}
