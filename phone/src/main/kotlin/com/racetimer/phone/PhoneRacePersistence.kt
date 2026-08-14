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
 * where the watch reads the identical rules. This class only moves four values to disk and back;
 * a rule that crept in here would be the second copy the restore path's every shipped defect came
 * from (`resumeOfferRemainingMs`, duplicated inverted — see RestorePlan's header).
 *
 * ### `apply()`, consciously (#151)
 *
 * The same choice the watch made, made here on purpose rather than inherited by copy. `apply()`
 * writes to disk asynchronously, so a process killed in the window between the write and the flush
 * loses the snapshot — the race is gone, the officer starts fresh, nothing is *wrong*, just lost.
 * `commit()` closes that window by blocking the caller — and the caller here includes the arm
 * path's persist slot, which sits between the first cue's synchronous dispatch and the wake-lock
 * acquisition. A disk write blocking that path is a worse trade than a lost snapshot: the snapshot
 * protects a rare failure, the arm path runs every race. #151 tracks the question for the watch;
 * whatever it decides applies here identically, which is why this comment names it.
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
     * Forget the race in flight. By key, not `clear()` — the watch learned that a blanket clear
     * silently takes unrelated keys with it the day somebody adds one (#88).
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
    }
}
