package com.racetimer.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Tests for the three restore decisions (#64).
 *
 * These used to live in `wear/` behind an Android `Context` and were the only part of the restore
 * path with no coverage at all — which is how #57 symptom A survived on hardware long enough to be
 * hit twice by accident: a killed Scholastic race came back as a fresh 5-minute US Sailing one.
 * `a saved race is what the app opens on, not the default` is that regression, named.
 *
 * The clocks are passed in, so "now" here is whatever a test says it is. [BASE_ELAPSED] and
 * [BASE_WALL] are an arbitrary pair standing in for a boot that has been up a while; what matters is
 * only the *relationship* between them and the snapshot's own readings, since
 * [gunTimeFromSnapshot] treats a current monotonic reading below the captured one as proof of a
 * reboot.
 */
class RestorePlanTest {

    private companion object {
        const val BASE_ELAPSED = 1_000_000L
        const val BASE_WALL = 1_700_000_000_000L

        /** A race of [remainingMs] still to run, captured at [BASE_ELAPSED] on this same boot. */
        fun raceWith(sequenceId: String, remainingMs: Long) = TimerEngine.Snapshot(
            sequenceId = sequenceId,
            gunElapsedMs = BASE_ELAPSED + remainingMs,
            gunWallMs = BASE_WALL + remainingMs,
            capturedElapsedMs = BASE_ELAPSED,
        )
    }

    // --- snapshotFrom: is there a saved race at all? --------------------------

    @Test fun `four stored values make a race`() {
        val snap = snapshotFrom(
            sequenceId = BuiltInSequences.scholastic.id,
            gunElapsedMs = 5_000L,
            gunWallMs = BASE_WALL,
            capturedElapsedMs = 4_000L,
        )
        assertEquals(
            TimerEngine.Snapshot(BuiltInSequences.scholastic.id, 5_000L, BASE_WALL, 4_000L),
            snap,
        )
    }

    @Test fun `no gun anchor means no race`() {
        // The state a cleared or first-run prefs file presents: the key is absent and the read
        // returns the sentinel.
        assertNull(
            snapshotFrom(BuiltInSequences.usSailing.id, 5_000L, NO_GUN_WALL_MS, 4_000L)
        )
    }

    @Test fun `a zero gun anchor means no race`() {
        // Guarded on sign rather than on the sentinel: a gun at wall-clock zero is not a race, and
        // reading it as one would put the sailor on a countdown anchored to 1970.
        assertNull(
            snapshotFrom(BuiltInSequences.usSailing.id, 5_000L, 0L, 4_000L)
        )
    }

    @Test fun `no capture reading means no race`() {
        // Without it there is no way to tell a surviving monotonic domain from a rebooted one, so
        // there is no honest restore to offer.
        assertNull(
            snapshotFrom(BuiltInSequences.usSailing.id, 5_000L, BASE_WALL, NO_CAPTURED_ELAPSED_MS)
        )
    }

    @Test fun `no sequence id means no race`() {
        // Nothing can be rebuilt without it — not the cues, not the duration.
        assertNull(snapshotFrom(null, 5_000L, BASE_WALL, 4_000L))
    }

    @Test fun `a missing monotonic anchor still makes a race`() {
        // Deliberately unguarded: the wall-clock reconstruction is what a restore falls back to
        // across a reboot, so refusing here would turn a degraded restore into no restore.
        assertNotNull(
            snapshotFrom(BuiltInSequences.usSailing.id, NO_GUN_ELAPSED_MS, BASE_WALL, 4_000L)
        )
    }

    // --- launchPlan: what the app opens on ------------------------------------

    @Test fun `a saved race is what the app opens on, not the default`() {
        // #57 symptom A, the regression this issue exists for. A Scholastic race was killed; the
        // app must come back holding Scholastic. Coming back holding US Sailing is what made a
        // 3-minute race restart as a fresh 5-minute one.
        val plan = launchPlan(
            raceWith(BuiltInSequences.scholastic.id, remainingMs = 90_000L),
            pickedSequenceId = BuiltInSequences.usSailing.id,
            BASE_ELAPSED,
            BASE_WALL,
        )
        assertEquals(BuiltInSequences.scholastic, plan.sequence)
        assertNotNull(plan.resumable)
    }

    @Test fun `a saved race outranks the remembered pick`() {
        // The pick is the more recent record only until a race attaches to one. Asserted against a
        // pick that is readable and *different*, so it could plausibly have won.
        val plan = launchPlan(
            raceWith(BuiltInSequences.club.id, remainingMs = 60_000L),
            pickedSequenceId = BuiltInSequences.scholastic.id,
            BASE_ELAPSED,
            BASE_WALL,
        )
        assertEquals(BuiltInSequences.club, plan.sequence)
    }

    @Test fun `a saved custom race is rebuilt and reopens the stepper on its own length`() {
        // The duration lives inside the id (#51), so `custom_8m` is the whole record: the sequence
        // is rebuilt from it, and the stepper has to reopen on 8 rather than the default.
        val plan = launchPlan(
            raceWith(BuiltInSequences.customId(8), remainingMs = 300_000L),
            pickedSequenceId = null,
            BASE_ELAPSED,
            BASE_WALL,
        )
        assertEquals(BuiltInSequences.custom(8), plan.sequence)
        assertEquals(8, plan.customMinutes)
    }

    @Test fun `a built-in selection leaves the dialled custom duration alone`() {
        // Null, not a default: the stepper keeps whatever the sailor last dialled, so passing
        // through a built-in sequence does not silently reset it.
        val plan = launchPlan(
            raceWith(BuiltInSequences.scholastic.id, remainingMs = 60_000L),
            pickedSequenceId = null,
            BASE_ELAPSED,
            BASE_WALL,
        )
        assertNull(plan.customMinutes)
    }

    @Test fun `an unreadable saved race selects nothing and says so`() {
        val plan = launchPlan(
            raceWith("nonsense_id", remainingMs = 60_000L),
            pickedSequenceId = null,
            BASE_ELAPSED,
            BASE_WALL,
        )
        assertNull(plan.sequence)
        assertNull(plan.resumable)
        assertEquals(LaunchNotice.SAVED_RACE_UNREADABLE, plan.notice)
    }

    @Test fun `an unreadable saved race falls back to the remembered pick`() {
        // The two records are independent (#88): a race nobody can read says nothing about whether
        // the pick can be read, and dropping to the default on top of the bad news would lose both.
        val plan = launchPlan(
            raceWith("nonsense_id", remainingMs = 60_000L),
            pickedSequenceId = BuiltInSequences.club.id,
            BASE_ELAPSED,
            BASE_WALL,
        )
        assertEquals(BuiltInSequences.club, plan.sequence)
        assertNull(plan.resumable)
        assertEquals(LaunchNotice.SAVED_RACE_UNREADABLE, plan.notice)
    }

    @Test fun `with no race saved the app opens on the remembered pick`() {
        // #88: the pick outlives every race, so a cold launch after a dozen Club starts opens on
        // Club and not on US Sailing.
        val plan = launchPlan(null, BuiltInSequences.club.id, BASE_ELAPSED, BASE_WALL)
        assertEquals(BuiltInSequences.club, plan.sequence)
        assertNull(plan.resumable)
        assertNull(plan.notice)
    }

    @Test fun `a first-ever launch selects nothing and says nothing`() {
        // Nothing stored is not an error — there is no choice to honour yet, and the caller's
        // default is the right thing to show.
        val plan = launchPlan(null, null, BASE_ELAPSED, BASE_WALL)
        assertEquals(LaunchPlan(null, null, null, null), plan)
    }

    @Test fun `an unreadable remembered pick selects nothing and says so`() {
        val plan = launchPlan(null, "nonsense_id", BASE_ELAPSED, BASE_WALL)
        assertNull(plan.sequence)
        assertEquals(LaunchNotice.PICKED_SEQUENCE_UNREADABLE, plan.notice)
    }

    @Test fun `when both records are unreadable the sailor is told once`() {
        // One message line, so one notice. This is what the two overlapping banners already came to
        // on screen — the second overwrote the first — stated deliberately rather than by accident.
        val plan = launchPlan(
            raceWith("nonsense_id", remainingMs = 60_000L),
            pickedSequenceId = "also_nonsense",
            BASE_ELAPSED,
            BASE_WALL,
        )
        assertNull(plan.sequence)
        assertEquals(LaunchNotice.PICKED_SEQUENCE_UNREADABLE, plan.notice)
    }

    @Test fun `a spent countdown is still selected but is not offered back`() {
        // Its gun went while the process was dead. The sequence is still the right thing to be
        // looking at — it is what the sailor was running — but there is no race left to resume, and
        // offering one would promise a race that no longer exists.
        val plan = launchPlan(
            raceWith(BuiltInSequences.scholastic.id, remainingMs = -30_000L),
            pickedSequenceId = null,
            BASE_ELAPSED,
            BASE_WALL,
        )
        assertEquals(BuiltInSequences.scholastic, plan.sequence)
        assertNull(plan.resumable)
    }

    @Test fun `a committee count-up past its gun is offered back`() {
        // The one sequence whose race outlives its own start: past the gun is where a race manager's
        // job begins, so the negative reading is a running race rather than a spent one.
        val plan = launchPlan(
            raceWith(BuiltInSequences.scholasticRaceManager.id, remainingMs = -600_000L),
            pickedSequenceId = null,
            BASE_ELAPSED,
            BASE_WALL,
        )
        assertEquals(BuiltInSequences.scholasticRaceManager, plan.sequence)
        assertNotNull(plan.resumable)
    }

    @Test fun `the race offered back is paired with the sequence it was selected for`() {
        // The offer carries its own sequence so the caller has nothing to look up and nothing to get
        // wrong. If these two ever came apart, Resume would restore one race onto another's cues.
        val snapshot = raceWith(BuiltInSequences.customId(12), remainingMs = 120_000L)
        val plan = launchPlan(snapshot, null, BASE_ELAPSED, BASE_WALL)
        val (offered, sequence) = plan.resumable!!
        assertEquals(snapshot, offered)
        assertSame(plan.sequence, sequence)
    }

    // --- startPlan: does a tap resume, or run from the top? -------------------

    @Test fun `Start resumes the saved race it is looking at`() {
        val saved = raceWith(BuiltInSequences.scholastic.id, remainingMs = 90_000L)
        assertEquals(
            StartPlan.Resume(saved),
            startPlan(
                freshStart = false,
                saved = saved,
                requestedSequenceId = BuiltInSequences.scholastic.id,
                engineState = TimerState.IDLE,
            ),
        )
    }

    @Test fun `Start over refuses the race it was shown, even with the snapshot still readable`() {
        // The ordering test, and the reason `freshStart` is an input rather than a precondition.
        // Start over clears the persisted keys, but that clear is a *separate statement* in
        // onStartCommand: this asserts the decision holds when the clear has not happened — which is
        // the state the bug would leave, and the state no test could otherwise pin down.
        val saved = raceWith(BuiltInSequences.scholastic.id, remainingMs = 90_000L)
        assertEquals(
            StartPlan.FromTheTop,
            startPlan(
                freshStart = true,
                saved = saved,
                requestedSequenceId = BuiltInSequences.scholastic.id,
                engineState = TimerState.IDLE,
            ),
        )
    }

    @Test fun `a saved race has no claim on a screen showing a different sequence`() {
        // #89's case seen from the service side: the tap runs what the sailor is looking at, and the
        // saved race is written over. The warning about that belongs on screen before the tap, not
        // in a restore they did not ask for.
        assertEquals(
            StartPlan.FromTheTop,
            startPlan(
                freshStart = false,
                saved = raceWith(BuiltInSequences.customId(8), remainingMs = 300_000L),
                requestedSequenceId = BuiltInSequences.customId(5),
                engineState = TimerState.IDLE,
            ),
        )
    }

    @Test fun `only an idle engine restores`() {
        // A second ACTION_START arriving mid-race must not re-anchor the race it is already running.
        val saved = raceWith(BuiltInSequences.scholastic.id, remainingMs = 90_000L)
        for (state in TimerState.entries.filter { it != TimerState.IDLE }) {
            assertEquals(
                "engine in $state",
                StartPlan.FromTheTop,
                startPlan(false, saved, BuiltInSequences.scholastic.id, state),
            )
        }
    }

    @Test fun `with nothing saved every start runs from the top`() {
        assertEquals(
            StartPlan.FromTheTop,
            startPlan(false, null, BuiltInSequences.usSailing.id, TimerState.IDLE),
        )
    }
}
