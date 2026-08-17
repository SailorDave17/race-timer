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
 * ### `apply()`, kept on this device's own measurement (#256)
 *
 * **The watch and this class deliberately differ, and the difference is measured rather than
 * inherited.** #151 measured the watch's `apply()` window at 20-53 ms warm and 69-205 ms cold and
 * closed it with `commit()`. #256 ran the same instrument against `:phone` rather than assuming those
 * numbers transfer - and they do not.
 *
 * On SM-S918U, debug build hash-verified as the installed artefact:
 *
 * | | Measured |
 * |---|---|
 * | `apply()` window, warm and cold, n=17 | 0.8 - 12.8 ms (median 5.8) |
 * | `commit()` window, n=13 | -4.2 to -0.2 ms, i.e. zero at the instrument's ~3 ms resolution |
 * | `commit()` main-thread cost, n=13 | 1.8 - 17.9 ms (median 4.6) |
 *
 * So on this device class `commit()` would buy roughly 6 ms of durability for a median 4.6 ms of
 * main thread, with a measured 17.9 ms tail - and it would spend it in the arm path's persist slot,
 * between the first cue's synchronous dispatch and the wake-lock acquisition. That is break-even at
 * the median and worse at the tail, which is the opposite of the watch's result, where `commit()`
 * bought 68-205 ms for ~8 ms.
 *
 * **`apply()` therefore stays - but the original argument for it was still wrong.** It reasoned that
 * a blocking disk write on the arm path is a worse trade than a lost snapshot, and priced neither
 * side. Both sides are priced above. The conclusion survived; the reasoning did not, and a rationale
 * nothing executes is an empirical claim that ages silently.
 *
 * `apply()` writes asynchronously, so a process killed inside the window above loses the snapshot -
 * the race is gone, the officer starts fresh, nothing is *wrong*, just lost. On this device that
 * window is single-digit milliseconds.
 *
 * **What would change this answer:** a materially slower phone. The whole finding is that the window
 * is a property of the device's flash and startup profile, not of this code, so it is worth
 * re-measuring on the slowest phone the app is expected to run on rather than assuming an S23 Ultra
 * generalises. The harness is in `docs/process-kill-test.md` and needs no process kill.
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
