package com.racetimer.phone

import android.os.VibrationAttributes
import com.racetimer.android.HapticUsage
import com.racetimer.android.HapticUsagePolicy

/**
 * What this phone's vibrations are declared as — **provisional, pending measurement** (#208,
 * measured by #210).
 *
 * ### These values are a choice made for the phone, not an inheritance from the watch
 *
 * [HapticUsagePolicy] exists so that each form factor states its own answer (#200), and this is
 * the phone's: the **honest** declarations, chosen because nothing has been measured on a phone
 * yet and the documented meaning of each usage is the only ground there is. A race cue is an
 * alarm — something that must reach the person whatever else the device is doing — so it is
 * declared `USAGE_ALARM`; a tap confirmation is touch feedback, so it is declared `USAGE_TOUCH`.
 * The phone issues no [HapticUsage.FEEDBACK] vibration today (it has no sync buzz — on a
 * console-sized readout the display snapping to the minute is the confirmation), but the seam
 * has two members and both are answered, so a feedback buzz added later arrives declared rather
 * than inferred.
 *
 * This is the same stance [PhoneCueAudioProfile] takes for the tones, for the same reason.
 *
 * ### Why the watch's answer is deliberately NOT copied
 *
 * The watch declares `USAGE_TOUCH` for **both** usages, and that is a known lie taken on evidence:
 * on an SM-R925U at API 36, total-silence Do Not Disturb dropped every `USAGE_ALARM` cue
 * (0 of 30) and passed every `USAGE_TOUCH` one (30 of 30). Every number in that table is a
 * reading of *one watch's* zen policy — duration-class inference and DND behaviour are
 * device-measured facts — and a phone's is a different policy. Importing the lie here would pay
 * its taxonomy cost with no evidence that it buys the delivery it was traded for; importing the
 * *honest* value with a note is what makes #210's measurement a decision rather than a
 * confirmation. See `WearHapticUsagePolicy` for the watch's table and #144/#186/#187 for how it
 * was taken.
 *
 * ### Provisional until #210
 *
 * #210 runs cue delivery on the owner's phone under DND, silent mode, focus loss and screen-off,
 * reads vibration delivery from `dumpsys vibrator_manager` per condition — the only instrument
 * that says a buzz reached the hand — and **sets this declaration to the measured winner in its
 * own change**, removing the provisional marker here and the test that pins it
 * (`PhoneHapticUsageDeclarationTest`). Until then treat both values as placeholders that have
 * never been proven to reach a human: `USAGE_ALARM` delivering 0 of 30 under DND on the watch is
 * the standing proof that the accurate declaration can be the one that silences you.
 */
object PhoneHapticUsagePolicy : HapticUsagePolicy {

    override fun vibrationUsageFor(usage: HapticUsage): Int = when (usage) {
        HapticUsage.CUE -> VibrationAttributes.USAGE_ALARM
        HapticUsage.FEEDBACK -> VibrationAttributes.USAGE_TOUCH
    }
}
