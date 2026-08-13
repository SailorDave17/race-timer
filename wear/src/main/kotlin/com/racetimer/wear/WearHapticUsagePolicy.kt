package com.racetimer.wear

import android.os.VibrationAttributes
import com.racetimer.android.HapticUsage
import com.racetimer.android.HapticUsagePolicy

/**
 * What this watch's vibrations are declared as (#144, #187, #200).
 *
 * ### Why `USAGE_TOUCH` and not `USAGE_ALARM`, which is the honest answer
 *
 * Because `USAGE_ALARM` does not work here, and that was **measured rather than assumed** — the issue
 * and the cairn note both warned it might not. *SM-R925U, API 36, one full race per arm:*
 *
 * | declared usage | `zen_mode=2` | `zen_mode=0` |
 * |---|---|---|
 * | none (duration-inferred) | 20 of 30 | 30 of 30 |
 * | `USAGE_ALARM` | **0 of 30**, every one `ignored_app_ops` | 29 of 29 |
 * | `USAGE_ALARM` + `FLAG_BYPASS_INTERRUPTION_POLICY` | **0 of 30** — the flag is *stripped*, the
 *   record reads `flags: 0`, and no error is raised. Only platform apps keep it. |  |
 * | **`USAGE_TOUCH`** | **30 of 30**, gun at 3032 ms | **30 of 30** |
 *
 * Total-silence DND restricts the alarm usage class on this device and permits the feedback class. So
 * the honest declaration is the one that gets the sailor's gun silenced, and the dishonest one is the
 * only thing that delivers it.
 *
 * **This is a known lie, taken deliberately.** A race gun is not touch feedback. It is declared that
 * way because the platform offers no unprivileged usage that is both accurate and audible under DND,
 * and a silent gun is a safety failure while a mislabelled one is a taxonomy failure.
 *
 * **What would break it, and why nothing here would notice**: if DND policy ever restricts the
 * feedback class, every cue goes silent under DND again, with no error, no crash and no failing test.
 * The only instrument that has ever detected this class is a race run on the wrist reading
 * `dumpsys vibrator_manager`. Re-run it after any platform upgrade; the numbers above are the
 * baseline to compare against. That obligation is tracked as
 * [#186](https://github.com/SailorDave17/race-timer/issues/186) rather than left to this comment,
 * because a comment is not a thing anybody is scheduled to read.
 *
 * ### Why it is in `:wear` and not beside the class that uses it
 *
 * Every number above is a reading of *one watch's* zen policy. A phone's is a different policy, and
 * an inherited lie there would pay the taxonomy cost with no evidence of buying the delivery it was
 * traded for. The phone owes its own run of this table — see [HapticUsagePolicy] and epic #196.
 *
 * ### Both values are the same and must stay two
 *
 * [HapticUsage.CUE] and [HapticUsage.FEEDBACK] resolve to the same constant *on this device today*,
 * which is a coincidence of what the platform will honour and not an equivalence. Collapsing them
 * would delete the record of a decision that is expected to be revisited.
 */
object WearHapticUsagePolicy : HapticUsagePolicy {

    override fun vibrationUsageFor(usage: HapticUsage): Int = when (usage) {
        HapticUsage.CUE -> VibrationAttributes.USAGE_TOUCH
        HapticUsage.FEEDBACK -> VibrationAttributes.USAGE_TOUCH
    }
}
