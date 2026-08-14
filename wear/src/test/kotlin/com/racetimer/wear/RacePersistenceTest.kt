package com.racetimer.wear

import android.content.Context
import android.os.SystemClock
import com.racetimer.shared.BuiltInSequences
import com.racetimer.shared.TimerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/**
 * The four-key race snapshot: the writer, the reader, and the file they meet in.
 *
 * #160's second target. The *decisions* around a saved race were extracted to `shared/RestorePlan.kt`
 * by #64 and are covered by `:shared:test`; what stayed behind is the IO — which keys, in which
 * preferences file, written by `persistSnapshot` and read by `savedSnapshot` — and until this file
 * the only thing holding those two together was a comment.
 *
 * ### Why the key names are asserted as literals
 *
 * Both sides read the same private constants, so renaming a key in one place and its constant in the
 * other is invisible to any test that goes through the API. The literals are not duplication for its
 * own sake: **the four keys are an on-disk format**, and a watch that updates the app mid-regatta
 * finds its race under the old names or does not find it at all. Changing one is a migration, and
 * this is the line that says so.
 */
@RunWith(RobolectricTestRunner::class)
class RacePersistenceTest {

    private companion object {
        const val PREFS_NAME = "race_timer_state"
        const val KEY_SEQUENCE_ID = "sequence_id"
        const val KEY_GUN_ELAPSED = "gun_elapsed_ms"
        const val KEY_GUN_WALL_CLOCK = "gun_wall_clock_ms"
        const val KEY_CAPTURED_ELAPSED = "captured_elapsed_ms"
        const val KEY_PICKED_SEQUENCE_ID = "picked_sequence_id"

        /** US Sailing is 5-4-1, so a resumed race at two minutes is unmistakably not a fresh one. */
        const val SEEDED_REMAINING_MS = 120_000L
        const val TOLERANCE_MS = 1_000L
    }

    private fun createdService(): TimerService =
        Robolectric.buildService(TimerService::class.java).create().get()

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** A race with [SEEDED_REMAINING_MS] left, written the way a previous process would have left it. */
    private fun seedRaceInFlight(context: Context, sequenceId: String) {
        val nowElapsed = SystemClock.elapsedRealtime()
        prefs(context).edit()
            .putString(KEY_SEQUENCE_ID, sequenceId)
            .putLong(KEY_GUN_ELAPSED, nowElapsed + SEEDED_REMAINING_MS)
            .putLong(KEY_GUN_WALL_CLOCK, System.currentTimeMillis() + SEEDED_REMAINING_MS)
            .putLong(KEY_CAPTURED_ELAPSED, nowElapsed)
            .commit()
    }

    @Test
    fun `a running race is written under the four keys the reader looks for`() {
        val svc = createdService()
        svc.onStartCommand(
            TimerService.startIntent(svc, BuiltInSequences.usSailing.id),
            0,
            1,
        )

        val raw = prefs(svc)
        assertTrue("no race written to $PREFS_NAME", raw.contains(KEY_GUN_ELAPSED))

        // Read through the public reader as well, and require the two to agree. Either alone proves
        // half of what is wanted: the literals pin the format, the reader pins that the service can
        // still find what it just wrote.
        val read = TimerService.savedSnapshot(svc)
        assertNotNull("savedSnapshot cannot find the race persistSnapshot just wrote", read)
        assertEquals(raw.getString(KEY_SEQUENCE_ID, null), read!!.sequenceId)
        assertEquals(raw.getLong(KEY_GUN_ELAPSED, -1L), read.gunElapsedMs)
        assertEquals(raw.getLong(KEY_GUN_WALL_CLOCK, -1L), read.gunWallMs)
        assertEquals(raw.getLong(KEY_CAPTURED_ELAPSED, -1L), read.capturedElapsedMs)
        assertEquals(BuiltInSequences.usSailing.id, read.sequenceId)
    }

    @Test
    fun `a race left by a dead process is resumed rather than restarted`() {
        val svc = createdService()
        seedRaceInFlight(svc, BuiltInSequences.usSailing.id)

        svc.onStartCommand(
            TimerService.startIntent(svc, BuiltInSequences.usSailing.id),
            0,
            1,
        )

        assertEquals(TimerState.RUNNING, svc.engine.currentState)
        assertEquals(
            "the saved race was not resumed",
            SEEDED_REMAINING_MS.toDouble(),
            svc.engine.remainingMs.toDouble(),
            TOLERANCE_MS.toDouble(),
        )
    }

    @Test
    fun `a fresh start discards the saved race and runs the sequence from the top`() {
        val svc = createdService()
        seedRaceInFlight(svc, BuiltInSequences.usSailing.id)

        svc.onStartCommand(
            TimerService.startIntent(svc, BuiltInSequences.usSailing.id, freshStart = true),
            0,
            1,
        )

        assertEquals(
            "a fresh start resumed the saved race",
            BuiltInSequences.usSailing.totalMs.toDouble(),
            svc.engine.remainingMs.toDouble(),
            TOLERANCE_MS.toDouble(),
        )
        // And the discarded race is gone from disk, not merely ignored -- otherwise the next launch
        // offers to resume a race the sailor has already declined.
        val persisted = TimerService.savedSnapshot(svc)
        assertNotNull(persisted)
        assertTrue(
            "the declined race is still on disk",
            persisted!!.gunElapsedMs >= SystemClock.elapsedRealtime() + SEEDED_REMAINING_MS,
        )
    }

    @Test
    fun `stop forgets the race but keeps the sailor's chosen sequence`() {
        // #88. `clearPersistedState` removes the four snapshot keys **by name** rather than calling
        // `edit().clear()`, which would be shorter and would take the remembered pick with it -- so a
        // Stop would send the next cold launch back to US Sailing however many Club races had just
        // been run. Nothing but this assertion stops that being tidied up again.
        val svc = createdService()
        TimerService.savePickedSequenceId(svc, BuiltInSequences.club.id)
        svc.onStartCommand(TimerService.startIntent(svc, BuiltInSequences.club.id), 0, 1)
        assertNotNull(TimerService.savedSnapshot(svc))

        svc.onStartCommand(TimerService.stopIntent(svc), 0, 2)

        assertNull("the race survived the stop", TimerService.savedSnapshot(svc))
        assertEquals(
            "the remembered sequence was cleared with the race",
            BuiltInSequences.club.id,
            TimerService.pickedSequenceId(svc),
        )
        val raw = prefs(svc)
        assertEquals(emptySet<String>(), raw.all.keys.intersect(SNAPSHOT_KEYS))
        assertTrue(raw.contains(KEY_PICKED_SEQUENCE_ID))
    }

    private val SNAPSHOT_KEYS = setOf(
        KEY_SEQUENCE_ID,
        KEY_GUN_ELAPSED,
        KEY_GUN_WALL_CLOCK,
        KEY_CAPTURED_ELAPSED,
    )
}
