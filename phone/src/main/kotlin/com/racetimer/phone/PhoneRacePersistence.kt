package com.racetimer.phone

import android.content.Context
import android.content.SharedPreferences
import com.racetimer.shared.NO_CAPTURED_ELAPSED_MS
import com.racetimer.shared.NO_GUN_ELAPSED_MS
import com.racetimer.shared.NO_GUN_WALL_MS
import com.racetimer.shared.TimerEngine
import com.racetimer.shared.snapshotFrom

/**
 * The SharedPreferences IO under the phone's race snapshot — and nothing else (#205).
 *
 * Every *decision* about the persisted race — is it a race, does it restore, what does a launch
 * offer — lives in `shared/RestorePlan.kt` and `TimerEngine`, where the JVM suite reaches it and
 * where the watch reads the identical rules. This class only moves values to disk and back;
 * a rule that crept in here would be the second copy the restore path's every shipped defect came
 * from (`resumeOfferRemainingMs`, duplicated inverted — see RestorePlan's header).
 *
 * ### `apply()`, on a premise the watch has since disproved (#256)
 *
 * **This is no longer the same choice the watch made.** It was when it was written; #151 measured the
 * watch's window and closed it with `commit()`, so `TimerService.persistSnapshot()` now commits and
 * this class is the last `apply()` on a snapshot path.
 *
 * The reasoning below is kept rather than quietly deleted, because the half that failed is worth
 * seeing. `apply()` writes to disk asynchronously, so a process killed in the window between the
 * write and the flush loses the snapshot — the race is gone, the officer starts fresh, nothing is
 * *wrong*, just lost. The argument for accepting that was: `commit()` closes the window by blocking
 * the caller, the caller here includes the arm path's persist slot between the first cue's
 * synchronous dispatch and the wake-lock acquisition, and a disk write blocking that path is a worse
 * trade than a lost snapshot.
 *
 * **The cost in that trade was never measured, and on the watch it turned out to be ~8 ms** — against
 * an `apply()` window of 20–53 ms warm and 69–205 ms cold, and landing after the first cue is already
 * dispatched, which is the very ordering this comment relies on. So the trade was priced backwards
 * there. What is *not* known is this device's numbers, which is why nothing here changed on the
 * strength of the watch's: [#256](https://github.com/SailorDave17/race-timer/issues/256) measures the
 * phone's own window and decides on it.
 */
class PhoneRacePersistence(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Write the race in flight, as one set — the four keys exist together or not at all. */
    fun persist(snapshot: TimerEngine.Snapshot) {
        prefs.edit()
            .putString(PREF_SEQUENCE_ID, snapshot.sequenceId)
            .putLong(PREF_GUN_ELAPSED, snapshot.gunElapsedMs)
            .putLong(PREF_GUN_WALL_CLOCK, snapshot.gunWallMs)
            .putLong(PREF_CAPTURED_ELAPSED, snapshot.capturedElapsedMs)
            .apply()
    }

    /** The saved race, or null when the values on disk do not describe one. Decided by [snapshotFrom]. */
    fun saved(): TimerEngine.Snapshot? = snapshotFrom(
        sequenceId = prefs.getString(PREF_SEQUENCE_ID, null),
        gunElapsedMs = prefs.getLong(PREF_GUN_ELAPSED, NO_GUN_ELAPSED_MS),
        gunWallMs = prefs.getLong(PREF_GUN_WALL_CLOCK, NO_GUN_WALL_MS),
        capturedElapsedMs = prefs.getLong(PREF_CAPTURED_ELAPSED, NO_CAPTURED_ELAPSED_MS),
    )

    /**
     * Remember the sequence the officer just chose (#209).
     *
     * `apply()` for the same reason [persist] uses it, and with less at stake: losing this costs the
     * next launch its opening pick, never a race.
     */
    fun savePickedSequenceId(sequenceId: String) {
        prefs.edit().putString(PREF_PICKED_SEQUENCE_ID, sequenceId).apply()
    }

    /** The sequence last chosen, or null on a first-ever launch. Read by [PhoneTimerService.launchPlan]. */
    fun pickedSequenceId(): String? = prefs.getString(PREF_PICKED_SEQUENCE_ID, null)

    /**
     * Forget the race in flight. By key, not `clear()` — the watch learned that a blanket clear
     * silently takes unrelated keys with it the day somebody adds one (#88).
     *
     * [PREF_PICKED_SEQUENCE_ID] is **not** in this list, and that omission is the whole of #88's
     * lesson rather than an oversight: this method runs on Stop and at the post-gun teardown, so a
     * pick cleared here would be remembered exactly while a race was running and forgotten in every
     * ordinary case — a cold launch after three Club races reverting to the default.
     */
    fun clear() {
        prefs.edit()
            .remove(PREF_SEQUENCE_ID)
            .remove(PREF_GUN_ELAPSED)
            .remove(PREF_GUN_WALL_CLOCK)
            .remove(PREF_CAPTURED_ELAPSED)
            .apply()
    }

    private companion object {
        const val PREFS_NAME = "phone_race_state"
        const val PREF_SEQUENCE_ID = "sequence_id"
        const val PREF_GUN_ELAPSED = "gun_elapsed_ms"
        const val PREF_GUN_WALL_CLOCK = "gun_wall_clock_ms"
        const val PREF_CAPTURED_ELAPSED = "captured_elapsed_ms"

        /**
         * The sequence the officer last chose — a preference that outlives every race, not one of
         * the four keys above that describe a race in flight (#209).
         *
         * Shares the prefs file rather than opening a second one: same owner, same lifetime. What
         * matters is that no clear path touches it, which [clear] states in its own words.
         *
         * A Custom race needs nothing else stored. `custom_8m` carries its duration inside the id,
         * so `BuiltInSequences.resolve` rebuilds the whole sequence from this one string — there is
         * no second value here that could disagree with it.
         */
        const val PREF_PICKED_SEQUENCE_ID = "picked_sequence_id"
    }
}
