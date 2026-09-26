package com.racetimer.phone

import android.os.VibrationAttributes
import com.racetimer.android.HapticUsage
import com.racetimer.android.HapticUsagePolicy

/**
 * What this phone's vibrations are declared as — **measured** on the owner's phone (#210).
 *
 * ### The measured table
 *
 * SM-S918U, Android 16 (API 36), `develop @ a4972fe`, 2026-09-25. One full US Sailing 5-4-1-Go race
 * per condition (30 cues), read per buzz from `dumpsys vibrator_manager` — the only instrument that
 * says a buzz reached the hand. Procedure and the audio half in `docs/phone-cue-delivery.md`.
 *
 * | Condition | `USAGE_ALARM` (what ships) | `USAGE_TOUCH` |
 * |---|---|---|
 * | normal | 30 of 30 `finished` | — |
 * | vibrate mode | 30 of 30 | — |
 * | silent mode | 30 of 30 | — |
 * | another app's music holding audio focus | 30 of 30 | — |
 * | screen off, on (reported) battery | 30 of 30 | — |
 * | **total-silence Do Not Disturb** | **0 of 30** — every one `ignored_app_ops` | **30 of 30** |
 *
 * So a cue is declared `USAGE_ALARM`: the honest declaration, and the one measured to reach the hand
 * in five of the six conditions. The sixth is the watch's #144 finding reproduced on a phone — the
 * accurate class is the one total-silence DND restricts, and `USAGE_TOUCH` is the one it lets
 * through. **It is not adopted here, on purpose** (owner decision, 2026-09-25): on this phone the
 * touch class vibrates at `MEDIUM` intensity where the alarm class gets `HIGH`, it follows a
 * touch-feedback setting a user can switch off entirely — which would silence every cue buzz in
 * *every* condition — and it has not been run in the other five. #315 owns that decision, with
 * these numbers. Until it lands, a race under total-silence DND buzzes nothing on the phone.
 *
 * ### Why the watch's answer is still not copied
 *
 * The watch declares `USAGE_TOUCH` for **both** usages, a known lie taken on its own evidence (see
 * `WearHapticUsagePolicy` and #144/#186/#187). The phone's numbers say the lie would buy the same
 * delivery here; they also say what it would cost here that it did not cost there. That is a
 * decision about this device, taken in #315 — not a value to inherit.
 *
 * ### Feedback is declared, not measured
 *
 * The phone issues no [HapticUsage.FEEDBACK] vibration today (it has no sync buzz — on a
 * console-sized readout the display snapping to the minute is the confirmation), so #210 had
 * nothing to measure there. The seam has two members and both are answered, so a feedback buzz
 * added later arrives declared as touch feedback rather than inferred — and owes a measurement of
 * its own when it does.
 *
 * `PhoneCueDeclarationTest` pins both values, so a change here is a red build and a reason to
 * re-run the procedure, not a silent edit.
 */
object PhoneHapticUsagePolicy : HapticUsagePolicy {

    override fun vibrationUsageFor(usage: HapticUsage): Int = when (usage) {
        HapticUsage.CUE -> VibrationAttributes.USAGE_ALARM
        HapticUsage.FEEDBACK -> VibrationAttributes.USAGE_TOUCH
    }
}
