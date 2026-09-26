package com.racetimer.phone

import android.media.AudioAttributes
import android.media.AudioManager
import android.os.VibrationAttributes
import com.racetimer.android.HapticUsage
import com.racetimer.shared.CueStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pins what this phone *declares* its cues as — tones and buzzes both — to the values #210 measured
 * on the owner's phone, so a declaration that quietly moves is a red build and a reason to re-run
 * the measurement rather than a silent edit.
 *
 * ### What it replaced
 *
 * `PhoneHapticUsageDeclarationTest` pinned the haptic half while it was **provisional** (#208 AC 2),
 * and was written to be deleted: its third test asserted the provisional marker was still there, so
 * the change that recorded the measurement turned it red on purpose (cairn
 * `a-measurement-artefact-goes-red-when-its-decision-lands`). This is the pin of the measured values
 * that its own KDoc asked for, the way the watch's `HapticUsageDeclarationTest` cites its table. The
 * audio half had no pin at all while provisional; it has one now, for the same reason.
 *
 * ### The values are measured, and one of them is known to fail a condition
 *
 * `USAGE_ALARM` delivered 30 of 30 on both channels in five of six conditions and **0 of 30** under
 * total-silence Do Not Disturb, where accessibility tones and touch buzzes delivered 30 of 30 (table
 * in `docs/phone-cue-delivery.md`). The alarm values are pinned anyway, by owner decision: the
 * alternatives carry costs #315 has to weigh. So a red here that moves the cue off `USAGE_ALARM` is
 * #315 landing — replace these expectations with its measured values in the same change.
 *
 * ### Why there is no Robolectric runner here
 *
 * The subjects are `when`s over enums returning compile-time constants, and a faked platform's
 * answers are exactly what must not be trusted about audio or haptics (cairn
 * `a-stubbed-default-cannot-report-the-platform-moved`). The positive controls are the defence that
 * survives being wrong about that: if the android stubs ever answer a uniform default, the constants
 * collapse onto each other and those assertions redden instead of the maps quietly agreeing.
 */
class PhoneCueDeclarationTest {

    private val repoRoot: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile }
        ?: error("Could not find the repo root from ${File("").absolutePath}")

    @Test
    fun `a cue buzz is declared an alarm and feedback a touch, as measured`() {
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
            "The phone's haptic declarations moved. #210 measured USAGE_ALARM delivering 30 of 30 " +
                "in five of six conditions on the owner's phone (docs/phone-cue-delivery.md). If this " +
                "is #315 landing, replace this expectation with its measured values; otherwise a " +
                "declaration change needs the measurement re-run before it ships.",
            mapOf(
                HapticUsage.CUE to VibrationAttributes.USAGE_ALARM,
                HapticUsage.FEEDBACK to VibrationAttributes.USAGE_TOUCH,
            ),
            declared,
        )
    }

    @Test
    fun `a cue tone is declared an alarm on the alarm stream, as measured`() {
        assertNotEquals(
            "USAGE_ALARM and USAGE_MEDIA read as the same value — the constants are not resolving.",
            AudioAttributes.USAGE_ALARM,
            AudioAttributes.USAGE_MEDIA,
        )
        assertNotEquals(
            "STREAM_ALARM and STREAM_MUSIC read as the same value — the constants are not resolving.",
            AudioManager.STREAM_ALARM,
            AudioManager.STREAM_MUSIC,
        )

        // Both members of the seam, for both routes. The phone only ever routes ALARM
        // (PhoneCueSounder), so MEDIA's pair is the documented landing and is unmeasured — pinned so
        // it cannot drift unnoticed, not because it was proven.
        val declared = CueStream.entries.associateWith {
            PhoneCueAudioProfile.audioUsageFor(it) to PhoneCueAudioProfile.legacyStreamFor(it)
        }

        assertEquals(
            "The phone's audio declarations moved. #210 measured USAGE_ALARM on STREAM_ALARM heard " +
                "30 of 30 in five of six conditions on the owner's phone (docs/phone-cue-delivery.md). " +
                "If this is #315 landing, replace this expectation with its measured values; " +
                "otherwise re-run the measurement before shipping a declaration change.",
            mapOf(
                CueStream.ALARM to (AudioAttributes.USAGE_ALARM to AudioManager.STREAM_ALARM),
                CueStream.MEDIA to (AudioAttributes.USAGE_MEDIA to AudioManager.STREAM_MUSIC),
            ),
            declared,
        )
    }

    @Test
    fun `both declarations cite the measurement they rest on, and it exists`() {
        val record = File(repoRoot, "docs/phone-cue-delivery.md")
        assertTrue(
            "docs/phone-cue-delivery.md is gone — the pins above cite it as the table behind their " +
                "values, so a value with nothing behind it would read as measured.",
            record.isFile,
        )
        for (name in listOf("PhoneHapticUsagePolicy", "PhoneCueAudioProfile")) {
            val source = File(repoRoot, "phone/src/main/kotlin/com/racetimer/phone/$name.kt")
            // A scan of a file that is not there passes silently. Locating it is the precondition.
            assertTrue("the policy is at ${source.path}", source.isFile)
            assertTrue(
                "$name no longer points at docs/phone-cue-delivery.md. Its values are measured, and " +
                    "the pointer is what lets the next reader find the table and the re-run triggers.",
                source.readText().contains("docs/phone-cue-delivery.md"),
            )
        }
    }
}
