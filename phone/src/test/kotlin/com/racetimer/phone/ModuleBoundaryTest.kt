package com.racetimer.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The structural criteria of #197, asserted rather than greped by hand.
 *
 * AC 3 and AC 5 both say "grep-verified", and a grep somebody remembers to run is the shape of rule
 * this repo has already watched decay — the same argument `MessageContrast` makes for moving a
 * contrast table out of prose. So they run in CI, on every push, as tests.
 *
 * The scan is deliberately confined to `src/main/kotlin`. A guard whose subject is source text
 * otherwise fires on the file explaining it (cairn
 * `a-guard-that-reads-source-must-survive-its-own-docs`): this file names both the colour form and
 * the forbidden package, and lives in `src/test/kotlin`, outside every directory it reads.
 */
class ModuleBoundaryTest {

    private val repoRoot: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile }
        ?: error("Could not find the repo root from ${File("").absolutePath}")

    private fun kotlinSourcesIn(modulePath: String): List<File> =
        File(repoRoot, "$modulePath/src/main/kotlin")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()

    @Test
    fun `the phone module holds no colour of its own`() {
        val sources = kotlinSourcesIn("phone")
        // A guard that scanned nothing would pass silently — the absent-result-reads-as-clean shape.
        assertTrue("no phone sources were scanned", sources.size >= 4)

        val literal = Regex("0x[0-9A-Fa-f]{6,8}")
        val offenders = sources.flatMap { file ->
            file.readLines().withIndex()
                .filter { (_, line) -> literal.containsMatchIn(line) }
                .map { (index, line) -> "${file.name}:${index + 1}: ${line.trim()}" }
        }
        assertEquals(
            "Colour literals under :phone. Every colour here comes from shared/MessageContrast.kt " +
                "or a Compose named absolute; the watch's palette moves to shared code in #198. " +
                "(The launcher-icon vectors under res/ are a deliberate copy of the watch's and " +
                "are outside this scan — see phone/src/main/res/drawable/.)",
            emptyList<String>(),
            offenders,
        )
    }

    @Test
    fun `the two app modules do not reference each other`() {
        val watchPackage = listOf("com", "racetimer", "wear").joinToString(".")
        val phonePackage = listOf("com", "racetimer", "phone").joinToString(".")

        val phoneOffenders = kotlinSourcesIn("phone")
            .filter { it.readText().contains(watchPackage) }
            .map { it.name }
        assertEquals("phone sources referencing the watch", emptyList<String>(), phoneOffenders)

        val wearSources = kotlinSourcesIn("wear")
        assertTrue("no wear sources were scanned", wearSources.size >= 10)
        val wearOffenders = wearSources
            .filter { it.readText().contains(phonePackage) }
            .map { it.name }
        assertEquals("wear sources referencing the phone", emptyList<String>(), wearOffenders)
    }

    @Test
    fun `shared stays a pure Kotlin JVM library`() {
        val buildFile = File(repoRoot, "shared/build.gradle.kts").readText()
        val pluginLines = buildFile.lineSequence()
            .dropWhile { !it.trimStart().startsWith("plugins") }
            .drop(1)
            .takeWhile { !it.trimStart().startsWith("}") }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()
        // Exactly one plugin, and it is the JVM one. An Android plugin here would put Android types
        // within reach of the timing core, and the whole suite off the JVM and onto a device.
        assertEquals(listOf("alias(libs.plugins.kotlin.jvm)"), pluginLines)
    }
}
