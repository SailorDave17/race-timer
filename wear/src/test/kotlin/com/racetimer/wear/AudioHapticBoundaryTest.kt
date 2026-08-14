package com.racetimer.wear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The scope condition that shipped with #160's decision, as a check rather than as prose.
 *
 * Robolectric was granted for this module on one argument: the properties in scope -- an ordering of
 * calls, four preference keys, a notification channel, a volume the service owes back -- are the
 * framework's own bookkeeping, where "records that the call happened" *is* the subject. The audio
 * and haptic path is the opposite case. There a shadow stands in for physics, and the shadow is a
 * fourth instrument of the class that reported success through #61 while the watch was silent: the
 * duration overshoot (60 ms delivering 80, 500 delivering 512 *and* 520 from identical calls) and
 * the output restart charged to whichever blast followed a close are both invisible to it, and a
 * green suite over them would convert an unknown into a false negative.
 *
 * So the boundary is not "we chose not to write those tests". It is that such a test **must not be
 * written here**, because a passing one would be worse than none. Running `onStartCommand` under
 * Robolectric *executes* tone-thread code on the way past -- that is unavoidable and fine. Asserting
 * on it is what this refuses.
 *
 * ### Why this file is exempt from its own scan
 *
 * A guard whose subject is source text otherwise fires on the file explaining it (cairn
 * `a-guard-that-reads-source-must-survive-its-own-docs` -- measured twice in this workspace, the
 * second time on someone who had just read the note). The exemption is one file, named, and it is
 * held honest two ways: `the exemption is still needed` fails if this file stops containing what it
 * is exempted for, and `the scan can fail` runs the detector over a synthetic offender, because a
 * scan that matched nothing and a scan that was never wired up produce the same empty list.
 */
class AudioHapticBoundaryTest {

    private val repoRoot: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile }
        ?: error("Could not find the repo root from ${File("").absolutePath}")

    private val testSourceDir = File(repoRoot, "wear/src/test/kotlin")

    /**
     * Symbols that only appear in a test because it is reaching for cue delivery.
     *
     * Deliberately **not** `AudioManager` or `setStreamVolume`: the #95 volume borrow is device state
     * with a persisted obligation behind it, it is one of the targets the decision names, and
     * `CueVolumeBorrowTest` asserts on it correctly. The line is between the *route and the volume*
     * (bookkeeping, in scope) and *what comes out of the speaker* (physics, hardware only).
     */
    private val cueDeliverySymbols = listOf(
        "ToneManager",
        "HapticManager",
        "AudioTrack",
        "ToneGenerator",
        "VibrationEffect",
        "Vibrator",
        "playCue",
        "playBeep",
        "consumeCueLoss",
        "audioUnavailable",
    )

    /** This class is the one file allowed to name them, and only because it is the list. */
    private val exemptFileName = "AudioHapticBoundaryTest.kt"

    private fun offendersIn(fileName: String, lines: List<String>): List<String> =
        lines.withIndex()
            // Comments are read on purpose. The subject here is a *textual reference* -- a test that
            // names the tone path in a comment is describing an assertion somebody is about to
            // write, and the cheapest moment to refuse it is then. (`ModuleBoundaryTest` skips
            // comments for its persistence scan, and states the opposite reason: nothing is ever
            // accidentally persisted by a sentence.)
            .filter { (_, line) -> cueDeliverySymbols.any { line.contains(it) } }
            .map { (index, line) -> "$fileName:${index + 1}: ${line.trim()}" }

    private fun testSources(): List<File> =
        testSourceDir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    @Test
    fun `no test in this source set asserts on the audio or haptic path`() {
        val sources = testSources()
        // A scan of nothing passes silently, which is the shape that makes an absent result read as
        // a clean one. The floor is the files this source set was created with.
        assertTrue("no wear test sources were scanned from $testSourceDir", sources.size >= 5)

        val offenders = sources
            .filterNot { it.name == exemptFileName }
            .flatMap { offendersIn(it.name, it.readLines()) }

        assertEquals(
            "Cue delivery named under wear/src/test/. Robolectric is scoped to the non-audio " +
                "surfaces by the #160 decision: a shadow records that a tone was requested and " +
                "models nothing about what came out of the speaker, so a green assertion here " +
                "would be a false negative over exactly the defect class this app has shipped " +
                "(#61, #114, #144). The instrument for that path is a race run on a wrist.",
            emptyList<String>(),
            offenders,
        )
    }

    @Test
    fun `the scan can fail`() {
        // The positive control, in the same test class rather than in a mutation somebody has to
        // remember to run: a detector whose only evidence is an empty list has said nothing until it
        // has been shown to produce a non-empty one.
        val synthetic = listOf(
            "class SomeTest {",
            "    val tone = ToneManager(context, WearCueAudioProfile)",
            "    assertTrue(shadowOf(tone).playCue(signal))",
            "}",
        )
        val offenders = offendersIn("SomeTest.kt", synthetic)
        assertEquals(listOf(2, 3), offenders.map { it.substringAfter(':').substringBefore(':').toInt() })
    }

    @Test
    fun `the exemption is still needed`() {
        // An allowlist entry that has stopped being necessary is one nobody notices has become a
        // hole. If this file ever stops naming the symbols it is exempted for, the exemption goes
        // rather than staying as a standing permission.
        val self = File(testSourceDir, "com/racetimer/wear/$exemptFileName")
        assertTrue("the exempt file is not at ${self.path}", self.isFile)
        assertTrue(
            "$exemptFileName no longer names any cue-delivery symbol, so its exemption is now a " +
                "hole rather than a necessity -- remove it from the scan.",
            offendersIn(exemptFileName, self.readLines()).isNotEmpty(),
        )
    }
}
