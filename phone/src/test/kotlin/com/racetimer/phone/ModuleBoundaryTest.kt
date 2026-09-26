package com.racetimer.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The structural criteria of #197, #198 and #199, asserted rather than greped by hand.
 *
 * #197's AC 3 and AC 5, #198's AC 1 and AC 5 and #199's AC 4 all say "grep-verified", and a grep
 * somebody remembers to run is the shape of rule this repo has already watched decay — the same
 * argument `MessageContrast` makes for moving a contrast table out of prose. So they run in CI, on
 * every push, as tests.
 *
 * The scan is deliberately confined to `src/main/kotlin`. A guard whose subject is source text
 * otherwise fires on the file explaining it (cairn
 * `a-guard-that-reads-source-must-survive-its-own-docs`): this file names the colour form, the
 * forbidden package and the shared display table, and lives in `src/test/kotlin`, outside every
 * directory it reads.
 */
class ModuleBoundaryTest {

    private val repoRoot: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile }
        ?: error("Could not find the repo root from ${File("").absolutePath}")

    /**
     * Comment lines, skipped by the persistence scan and **only** by it.
     *
     * The three scans in this file want different things, and the difference is not an oversight.
     * The cross-module and colour scans read comments on purpose — their subject is a *textual*
     * reference, so a copied doc or a stray literal is exactly what they exist to catch. The
     * persistence scan's subject is a **call**, and nothing is ever accidentally persisted by a
     * sentence. Reading comments there means the paragraph explaining the rule trips it, which is
     * the failure cairn `a-guard-that-reads-source-must-survive-its-own-docs` names — and it fired
     * here, on the docstring of the very class this guard protects, written by someone who had just
     * finished reading that note.
     *
     * Deliberately crude: it will not see a block comment opened on one line and closed on
     * another. That is acceptable because a false *negative* here costs a missed comment, never a
     * missed call — the scan still reads every line of actual code.
     */
    private fun isComment(line: String): Boolean = line.trimStart().let {
        it.startsWith("//") || it.startsWith("*") || it.startsWith("/*")
    }

    private fun kotlinSourcesIn(modulePath: String): List<File> =
        File(repoRoot, "$modulePath/src/main/kotlin")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()

    /**
     * One rule, one implementation, both app modules (#198 AC 1, #197 AC 3).
     *
     * The watch's half arrived when #198 moved `Theme.kt`'s palette into `shared/Palette.kt`; before
     * that the same rule was asserted for `:phone` only, and this method's own message said the
     * watch's palette was still to come. Writing the wear scan as a second test — or as a second
     * test in `:wear` — would have put two copies of one rule in the tree on the day the story whose
     * whole subject is *a value must exist once* landed. So it takes the module as an argument.
     *
     * Scanning `:wear` from `:phone` is not the tidiest home for it, and it is the one that cannot
     * drift. Both modules' tests run on every push (`ci.yml`), so nothing is lost by where it sits.
     */
    private fun assertNoColourLiteralsIn(module: String, atLeast: Int) {
        val sources = kotlinSourcesIn(module)
        // A guard that scanned nothing would pass silently — the absent-result-reads-as-clean shape.
        assertTrue("no $module sources were scanned", sources.size >= atLeast)

        val literal = Regex("0x[0-9A-Fa-f]{6,8}")
        val offenders = sources.flatMap { file ->
            file.readLines().withIndex()
                .filter { (_, line) -> literal.containsMatchIn(line) }
                .map { (index, line) -> "${file.name}:${index + 1}: ${line.trim()}" }
        }
        assertEquals(
            "Colour literals under :$module. Every colour in either app module comes from " +
                "shared/Palette.kt, shared/MessageContrast.kt or a Compose named absolute — one " +
                "definition both form factors read, which is what #198 bought and what a literal " +
                "here spends. (The launcher-icon vectors under res/ are a deliberate per-module " +
                "copy and are outside this scan — see $module/src/main/res/drawable/.)",
            emptyList<String>(),
            offenders,
        )
    }

    @Test
    fun `the phone module holds no colour of its own`() = assertNoColourLiteralsIn("phone", atLeast = 4)

    @Test
    fun `the watch module holds no colour of its own`() = assertNoColourLiteralsIn("wear", atLeast = 10)

    /**
     * #198 AC 2: the colour palette does not come back as a resource.
     *
     * `wear/src/main/res/values/colors.xml` was deleted by that story. It was read by no code — only
     * by comments — and three of its four state values had drifted from the contrast-tested ones in
     * `shared/MessageContrast.kt` while every comment citing it still read as current. Nothing about
     * that was visible: a resource nobody references is invisible to the compiler, and this repo has
     * no lint step.
     *
     * The literal scan above cannot see this class, because it reads `src/main/kotlin` only. So the
     * deletion is asserted here, or it is an event that happened once rather than a property that
     * holds. If a themed resource ever genuinely needs one, this test is where that decision gets
     * taken — deliberately, with the history above in front of whoever takes it.
     */
    @Test
    fun `neither app module keeps a colour palette in resources`() {
        val found = listOf("wear", "phone")
            .map { File(repoRoot, "$it/src/main/res/values/colors.xml") }
            .filter { it.isFile }
            .map { it.relativeTo(repoRoot).path }
        assertEquals(
            "A resource colour palette is back. Colours live once, in shared/Palette.kt or " +
                "shared/MessageContrast.kt, where both form factors read them and a test pins " +
                "them; a res/values copy is read by neither module's Kotlin and drifts unwatched, " +
                "which is exactly what #198 removed.",
            emptyList<String>(),
            found,
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
    fun `the display path takes two booleans and no timer state`() {
        val displayPath = File(repoRoot, "phone/src/main/kotlin/com/racetimer/phone/PhoneDisplay.kt")
        // A scan of a file that is not there passes silently — the absent-result-reads-as-clean
        // shape. Locating it is the precondition, not a courtesy check.
        assertTrue("the display path is at ${displayPath.path}", displayPath.isFile)
        val source = displayPath.readText()

        assertEquals(
            "TimerState reaching the phone display path (#199 AC 4). The two window properties are " +
                "the officer's choice (#225), not a function of where the countdown is — the whole " +
                "reason this file is separate from the watch's state-driven table.",
            emptyList<String>(),
            source.lines().withIndex()
                .filter { (_, line) -> line.contains("TimerState") }
                .map { (index, line) -> "${index + 1}: ${line.trim()}" },
        )

        // The signature itself, so "two booleans and nothing else" is asserted rather than described.
        assertTrue(
            "the display path's entry point takes exactly the two booleans; found:\n$source",
            source.contains(
                "fun Window.applyDisplayProperties(keepScreenOn: Boolean, fullBrightness: Boolean)",
            ),
        )
    }

    @Test
    fun `the phone never reaches for the watch's shared display table`() {
        val sources = kotlinSourcesIn("phone")
        assertTrue("no phone sources were scanned", sources.size >= 5)

        // The compiler would allow every one of these — `:shared` is on the classpath and the table
        // is public. Nothing but this assertion stops the phone re-inheriting a policy that is right
        // for a wrist and wrong for a console (#199 AC 4).
        val forbidden = listOf("ScreenPolicy", "keepsScreenOn", "forcesMaxBrightness")
        val offenders = sources.flatMap { file ->
            file.readLines().withIndex()
                .filter { (_, line) -> forbidden.any { line.contains(it) } }
                .map { (index, line) -> "${file.name}:${index + 1}: ${line.trim()}" }
        }
        assertEquals(
            "Phone sources naming the watch's shared display rules. The phone's two properties are " +
                "chosen by the officer once per launch (#225) and applied by PhoneDisplay.kt; " +
                "shared/ScreenPolicy.kt is the watch's and stays untouched.",
            emptyList<String>(),
            offenders,
        )
    }

    @Test
    fun `the display choice is written to no persistent store`() {
        val sources = kotlinSourcesIn("phone")
        assertTrue("no phone sources were scanned", sources.size >= 7)

        // #225 AC 7. The choice is held for the process lifetime and no longer, because the right
        // answer is a property of the day — this sun, this boat, this battery — and not of the
        // officer, so a remembered value would be confidently wrong on the next race day. A
        // ViewModel gives that by construction; this is what stops somebody "improving" it later
        // into a preference that quietly outlives its conditions.
        val stores = listOf("SharedPreferences", "DataStore", "getPreferences", "getSharedPreferences")
        val offenders = sources
            // #205 landed, and this is the narrowing its message promised: the race snapshot's IO
            // lives in exactly one sanctioned file, so the guard now reads "no store outside it"
            // rather than "no store at all". The display choice's own rule is unchanged — a store
            // reached from DisplayChoice* or MainActivity still fails here.
            .filterNot { it.name == "PhoneRacePersistence.kt" }
            .flatMap { file ->
                file.readLines().withIndex()
                    .filterNot { (_, line) -> isComment(line) }
                    .filter { (_, line) -> stores.any { line.contains(it) } }
                    .map { (index, line) -> "${file.name}:${index + 1}: ${line.trim()}" }
            }
        assertEquals(
            "Persistence under :phone outside PhoneRacePersistence. The display choice (#225) is " +
                "deliberately re-asked on every cold launch and stored nowhere; the race snapshot " +
                "(#205) is the one sanctioned store and has exactly one home.",
            emptyList<String>(),
            offenders,
        )
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
