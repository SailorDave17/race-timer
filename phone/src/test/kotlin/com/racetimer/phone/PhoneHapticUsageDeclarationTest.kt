package com.racetimer.phone

import android.os.VibrationAttributes
import com.racetimer.android.HapticUsage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pins what this phone *declares* its vibrations as, and that the declaration is marked
 * provisional (#208 AC 2) — so a value that quietly moves, or a marker that quietly goes, is a red
 * build rather than a silent inheritance.
 *
 * The watch's twin is `HapticUsageDeclarationTest` under `wear/src/test/`, and the shape is
 * deliberately the same: no Robolectric runner, because the subject is a `when` over an enum
 * returning a compile-time constant, and a faked platform's answers are exactly what must not be
 * trusted about a haptic (cairn `a-stubbed-default-cannot-report-the-platform-moved`). The
 * positive control in the first test is the defence that survives being wrong about any of this.
 *
 * ### This file is written to be deleted
 *
 * The values it pins are **provisional**: the honest declarations, chosen on documentation alone
 * because nothing has been measured on a phone. #210 measures delivery on the owner's phone under
 * DND, silent mode, focus loss and screen-off, sets the declaration to the measured winner, and
 * removes the provisional marker — at which point the third test here goes red, and **that red is
 * the intended outcome** (cairn `a-measurement-artefact-goes-red-when-its-decision-lands`,
 * `an-exemption-should-carry-the-test-that-expires-it`). #210's change replaces this file with a
 * pin of the measured values, the way the watch's pin cites its table.
 */
class PhoneHapticUsageDeclarationTest {

    private val repoRoot: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile }
        ?: error("Could not find the repo root from ${File("").absolutePath}")

    @Test
    fun `a cue is declared an alarm and feedback a touch, provisionally, and the constants are real`() {
        // The positive control: if the android stubs ever answer a uniform default, these
        // collapse onto each other and this reddens instead of the map below quietly agreeing.
        assertNotEquals(
            "USAGE_ALARM and USAGE_TOUCH read as the same value, so the constants are not resolving " +
                "and the assertion below would pass whatever the policy returned.",
            VibrationAttributes.USAGE_ALARM,
            VibrationAttributes.USAGE_TOUCH,
        )
        assertNotEquals(
            "USAGE_ALARM is reading as USAGE_UNKNOWN — see above, the constants are not resolving.",
            VibrationAttributes.USAGE_UNKNOWN,
            VibrationAttributes.USAGE_ALARM,
        )

        // ACTUAL driven off the enum so a usage added later arrives unpinned rather than
        // unexamined; EXPECTED names both keys and both values outright, so a deleted usage cannot
        // shrink both sides together (prove-tests shape 4).
        val declared = HapticUsage.entries.associateWith { PhoneHapticUsagePolicy.vibrationUsageFor(it) }

        assertEquals(
            "The phone's PROVISIONAL declarations moved. Until #210 measures this phone, a cue is " +
                "declared the honest USAGE_ALARM and feedback the honest USAGE_TOUCH — deliberately " +
                "NOT the watch's USAGE_TOUCH-for-both, which is a lie measured on one watch's DND " +
                "policy. If #210 has landed, this file should have been replaced with a pin of the " +
                "measured winner; if it has not, re-read PhoneHapticUsagePolicy before changing this.",
            mapOf(
                HapticUsage.CUE to VibrationAttributes.USAGE_ALARM,
                HapticUsage.FEEDBACK to VibrationAttributes.USAGE_TOUCH,
            ),
            declared,
        )
    }

    @Test
    fun `the watch's answer was not copied`() {
        // The value the phone must NOT have inherited. Asserted on its own so that a future edit
        // "aligning" the two policies fails with this message rather than the generic one above.
        assertNotEquals(
            "The phone declares its cues as the watch does. That value is a known lie taken on a " +
                "measurement of an SM-R925U's DND policy, and a phone's is a different policy — " +
                "importing it pays the taxonomy cost with no evidence of the delivery. #210 is where " +
                "the phone's own measurement decides this.",
            VibrationAttributes.USAGE_TOUCH,
            PhoneHapticUsagePolicy.vibrationUsageFor(HapticUsage.CUE),
        )
    }

    @Test
    fun `the declaration is marked provisional, and names the story that measures it`() {
        val source = File(repoRoot, "phone/src/main/kotlin/com/racetimer/phone/PhoneHapticUsagePolicy.kt")
        // A scan of a file that is not there passes silently. Locating it is the precondition.
        assertTrue("the policy is at ${source.path}", source.isFile)
        val text = source.readText()

        // AC 2's own words: the attributes are "marked provisional pending the hardware
        // measurement story". The mark is prose, so this reads the prose — a copy test, and one
        // whose subject is the PRESENCE of the marker rather than the truth of a sentence, which
        // is the one case a copy test measures what it claims to. #210 removes the marker and
        // this test together; a red here after that is the decision landing, not a defect.
        assertTrue(
            "PhoneHapticUsagePolicy no longer says its values are provisional. If #210 has landed, " +
                "delete this test with it; if it has not, the marker was dropped and the values " +
                "below it are now reading as measured when nothing measured them.",
            text.contains("provisional", ignoreCase = true),
        )
        assertTrue(
            "PhoneHapticUsagePolicy no longer names #210, the story that measures it — a " +
                "provisional value with no named discharge is a default nobody re-measures.",
            text.contains("#210"),
        )
    }
}
