package com.racetimer.wear

import android.os.VibrationAttributes
import com.racetimer.android.HapticUsage
import com.racetimer.android.HapticUsagePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Pins what this watch *declares* its vibrations as, so dropping the declaration is a red build
 * rather than a silent regression (#245).
 *
 * ### What this is guarding, and why it needed a test source set to exist
 *
 * [WearHapticUsagePolicy] answers `USAGE_TOUCH` for both usages, and that answer is a **known lie
 * taken deliberately** — the measured table is on that object. `USAGE_ALARM` is the honest
 * declaration for a race gun and delivered **0 of 30** cues under total-silence Do Not Disturb on
 * this watch, against 30 of 30 for `USAGE_TOUCH`. So the constant below is not a preference; it is
 * the only unprivileged value measured to reach the wrist when the sailor most needs it.
 *
 * Nothing would have noticed it changing. `docs/dnd-haptics-recheck.md` deferred this pin explicitly
 * on `wear/` gaining somewhere to put it, [#160] landed that, and this is the deferred fragment.
 *
 * ### Why there is no Robolectric runner here
 *
 * The subject is a `when` over an enum returning a compile-time constant. Standing up a fake Android
 * environment to read it would add a platform whose answers are exactly what must **not** be trusted
 * here (cairn `a-stubbed-default-cannot-report-the-platform-moved`: a harness that fakes a platform
 * can overstate it, and that direction passes). The positive control in the first test is the
 * defence that survives being wrong about any of this — if the constants ever come back as a
 * uniform zero, that assertion reddens instead of quietly agreeing.
 *
 * ### The pre-33 route is out of scope by construction, not merely unreached (#245 AC 2)
 *
 * Below API 33 the attribution travels as `AudioAttributes` instead, and that value is **not
 * supplied through this policy** — it is a single shared constant held in `:shared-android`, which
 * has no test source set by decision, and it has never been measured on any device this app runs on.
 * The only watch available is API 36 and cannot execute that branch at all.
 *
 * So its absence here is stated rather than covered, per that criterion's second arm. The second
 * test keeps the statement honest: it reads the policy's own surface, so the day a pre-33 answer is
 * added to it, this file goes red and the question gets asked instead of inherited.
 *
 * ### The blind spot, named rather than implied
 *
 * Both usages resolve to the same constant *today*, which is a coincidence of what this platform
 * will honour and not an equivalence — [HapticUsage] says so in as many words. A change collapsing
 * the two branches into one unconditional return is therefore **invisible to every assertion here**,
 * because the values it would produce are the values expected. What that would delete is the record
 * of a decision, and no value check can see a record.
 *
 * [#160]: https://github.com/SailorDave17/race-timer/issues/160
 */
class HapticUsageDeclarationTest {

    @Test
    fun `every usage is declared USAGE_TOUCH, and USAGE_TOUCH is a real constant`() {
        // The positive control lives in this test rather than merely in this class, because an
        // expected value that a broken harness would also produce proves nothing on its own. If the
        // android stubs ever answer a uniform default, these three constants collapse onto each
        // other and this reddens — which is the whole reason to spend two lines on it.
        assertNotEquals(
            "USAGE_TOUCH and USAGE_ALARM read as the same value, so the constants are not resolving " +
                "and the assertion below would pass whatever the policy returned.",
            VibrationAttributes.USAGE_ALARM,
            VibrationAttributes.USAGE_TOUCH,
        )
        assertNotEquals(
            "USAGE_TOUCH is reading as USAGE_UNKNOWN — see above, the constants are not resolving.",
            VibrationAttributes.USAGE_UNKNOWN,
            VibrationAttributes.USAGE_TOUCH,
        )

        // The ACTUAL is driven off the enum, so a usage added later arrives unpinned rather than
        // unexamined. The EXPECTED names both keys and both values outright, which is the part that
        // matters: writing it as `entries.associateWith { USAGE_TOUCH }` would derive the key set
        // from the same enum the actual derives it from, so a deleted usage would shrink both sides
        // together and pass (prove-tests shape 4 — an expected value computed from the code under
        // test). Having to edit this map when a usage is added is the assertion working.
        val declared = HapticUsage.entries.associateWith { WearHapticUsagePolicy.vibrationUsageFor(it) }

        assertEquals(
            "every HapticUsage must be declared USAGE_TOUCH — it is the only unprivileged usage " +
                "measured to survive total-silence Do Not Disturb on this watch (0 of 30 for " +
                "USAGE_ALARM, 30 of 30 for this). See WearHapticUsagePolicy for the table.",
            mapOf(
                HapticUsage.CUE to VibrationAttributes.USAGE_TOUCH,
                HapticUsage.FEEDBACK to VibrationAttributes.USAGE_TOUCH,
            ),
            declared,
        )
    }

    @Test
    fun `the policy answers for the API 33+ declaration alone`() {
        // Keeps the pre-33 paragraph above from becoming quietly false. The claim is not "the pre-33
        // branch is fine" — it is "this seam does not answer for it", and that stops being true the
        // moment the interface grows a second member. Synthetic members are filtered because the
        // compiler adds its own and they are nobody's decision.
        val declared = HapticUsagePolicy::class.java.declaredMethods
            .filterNot { it.isSynthetic }
            .map { it.name }
            .sorted()

        assertEquals(
            "HapticUsagePolicy has gained or lost a member. This file states that the pre-33 " +
                "AudioAttributes route is out of its scope, and that statement is only true while " +
                "vibrationUsageFor is the whole surface. Re-read #245 AC 2 before changing this.",
            listOf("vibrationUsageFor"),
            declared,
        )
        // An `assertTrue(HapticUsagePolicy isAssignableFrom WearHapticUsagePolicy)` stood here and
        // was removed: dropping that supertype is a compile error at the `override` keyword, so the
        // assertion could not fail for its stated reason (prove-tests shape 8). The empty-reflection
        // case it was meant to guard is already covered — an empty list does not equal the list
        // above, so it reddens rather than reading as clean.
    }
}
