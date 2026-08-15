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
 * ### The scan follows the compiler, not one directory
 *
 * `:wear:testDebugUnitTest` compiles four source directories, not the one this file lives in --
 * `src/test/kotlin`, `src/test/java`, `src/testDebug/kotlin` and `src/testDebug/java`, per
 * `./gradlew :wear:sourceSets`. `src/test/java` is AGP's own default and the root Android Studio's
 * test generation offers first, so a guard pinned to `src/test/kotlin` leaves the likeliest hole
 * open: a class there runs in CI while this scan returns the same files and passes.
 *
 * Rather than list the four, [testRoots] takes every `src/test*` and `src/androidTest*` directory
 * that exists, so a build variant added later is covered the day it appears rather than the day
 * somebody remembers this file. Both extensions are read for the same reason.
 *
 * ### Why this file is exempt from its own scan
 *
 * A guard whose subject is source text otherwise fires on the file explaining it (cairn
 * `a-guard-that-reads-source-must-survive-its-own-docs` -- measured twice in this workspace, the
 * second time on someone who had just read the note). The exemption is one **path**, not one name:
 * matching on the filename alone would silently exempt a second class of the same name in any other
 * package, which is not what the sentence above claims. It is held honest two ways: `the exemption
 * is still needed` fails if this file stops containing what it is exempted for, and `the scan can
 * fail` runs the detector over a synthetic offender, because a scan that matched nothing and a scan
 * that was never wired up produce the same empty list.
 */
class AudioHapticBoundaryTest {

    private val repoRoot: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile }
        ?: error("Could not find the repo root from ${File("").absolutePath}")

    private val moduleSrc = File(repoRoot, "wear/src")

    /** This file, as a path relative to [moduleSrc] -- the one exemption, and it is a path. */
    private val exemptPath = "test/kotlin/com/racetimer/wear/AudioHapticBoundaryTest.kt"

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

    /** Every source directory `:wear:testDebugUnitTest` could compile, that exists. */
    private fun testRoots(): List<File> =
        (moduleSrc.listFiles() ?: emptyArray())
            .filter { it.isDirectory && (it.name.startsWith("test") || it.name.startsWith("androidTest")) }
            .sortedBy { it.name }

    private fun offendersIn(path: String, lines: List<String>): List<String> =
        lines.withIndex()
            // Comments are read on purpose. The subject here is a *textual reference* -- a test that
            // names the tone path in a comment is describing an assertion somebody is about to
            // write, and the cheapest moment to refuse it is then. (`ModuleBoundaryTest` under
            // :phone skips comments for its persistence scan, and states the opposite reason:
            // nothing is ever accidentally persisted by a sentence.)
            .filter { (_, line) -> cueDeliverySymbols.any { line.contains(it) } }
            .map { (index, line) -> "$path:${index + 1}: ${line.trim()}" }

    private fun testSources(): List<Pair<String, File>> =
        testRoots().flatMap { root ->
            root.walkTopDown()
                .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
                .map { it.relativeTo(moduleSrc).invariantSeparatorsPath to it }
                .toList()
        }

    @Test
    fun `the scan reaches every directory the suite compiles`() {
        val roots = testRoots()
        assertTrue("no test source directory under $moduleSrc", roots.isNotEmpty())

        // Two properties, and together they mean "anything AGP compiles is scanned".
        //
        // First: the walk starts at the SOURCE-SET root, not at a language directory under it. Every
        // directory `:wear:testDebugUnitTest` compiles is `<sourceSet>/<language>`, so if each
        // source-set root that exists on disk is walked, both language directories under it are too.
        // A variant with no sources at all is skipped on purpose -- there is nothing there to scan,
        // and it is picked up the day somebody creates it, because [testRoots] reads the disk.
        // This reddens the moment [testRoots] narrows: pointing it at `src/test/kotlin` drops the
        // `test` root out of the list.
        val requiredRoots = AGP_TEST_SOURCE_DIRS
            .map { it.substringBefore('/') }
            .distinct()
            .map { File(moduleSrc, it) }
            .filter { it.isDirectory }
        assertEquals(
            "AGP compiles sources under these roots and the scan does not walk them, so a test " +
                "placed there runs in CI while this guard passes. Roots walked: ${roots.map { it.name }}",
            emptyList<File>(),
            requiredRoots.filterNot { required -> roots.any { it.absolutePath == required.absolutePath } },
        )

        // Second: the walk is recursive, so a language directory under a walked root is reached
        // rather than merely contained. Asserted against a file four levels below the root.
        val scanned = testSources().map { it.first }
        assertTrue(
            "the walk is not recursive -- nothing nested was scanned, so only files sitting " +
                "directly in a source-set root would ever be seen. Scanned: $scanned",
            scanned.any { it == "test/kotlin/com/racetimer/wear/AudioHapticBoundaryTest.kt" },
        )

        // The floor is the files this source set was created with. A scan of nothing passes
        // silently, which is the shape that makes an absent result read as a clean one.
        assertTrue("only ${scanned.size} test sources were scanned", scanned.size >= 6)
    }

    @Test
    fun `no test in this source set asserts on the audio or haptic path`() {
        val offenders = testSources()
            .filterNot { (path, _) -> path == exemptPath }
            .flatMap { (path, file) -> offendersIn(path, file.readLines()) }

        assertEquals(
            "Cue delivery named under wear/src/test*. Robolectric is scoped to the non-audio " +
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
        val offenders = offendersIn("test/java/SomeTest.kt", synthetic)
        assertEquals(
            listOf("test/java/SomeTest.kt:2", "test/java/SomeTest.kt:3"),
            offenders.map { it.substringBeforeLast(": ") },
        )
    }

    @Test
    fun `the exemption is still needed, and is one path rather than one name`() {
        // An allowlist entry that has stopped being necessary is one nobody notices has become a
        // hole. If this file ever stops naming the symbols it is exempted for, the exemption goes
        // rather than staying as a standing permission.
        val self = File(moduleSrc, exemptPath)
        assertTrue("the exempt file is not at ${self.path}", self.isFile)
        assertTrue(
            "$exemptPath no longer names any cue-delivery symbol, so its exemption is now a " +
                "hole rather than a necessity -- remove it from the scan.",
            offendersIn(exemptPath, self.readLines()).isNotEmpty(),
        )
        // And exactly one file is exempt. A filename match would exempt every same-named class in
        // every package the walk reaches, which is not what this class claims to do.
        assertEquals(
            listOf(exemptPath),
            testSources().map { it.first }.filter { it.endsWith("AudioHapticBoundaryTest.kt") },
        )
    }

    private companion object {
        /**
         * What `:wear:testDebugUnitTest` actually compiles, read off `./gradlew :wear:sourceSets`:
         *
         * ```
         * test        Kotlin [wear/src/test/kotlin, wear/src/test/java]  Java [wear/src/test/java]
         * testDebug   Kotlin [wear/src/testDebug/kotlin, wear/src/testDebug/java]
         *             Java   [wear/src/testDebug/java]
         * ```
         *
         * Listed as a fixed expectation on purpose: [testRoots] derives its answer from the disk, so
         * comparing the two is a check rather than a restatement. If AGP ever adds a directory this
         * list will be stale in the safe direction -- the scan still walks the source-set root and
         * picks it up; what this catches is the scan narrowing.
         */
        val AGP_TEST_SOURCE_DIRS = listOf(
            "test/kotlin",
            "test/java",
            "testDebug/kotlin",
            "testDebug/java",
        )
    }
}
