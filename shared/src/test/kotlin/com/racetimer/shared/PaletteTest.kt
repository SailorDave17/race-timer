package com.racetimer.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Modifier

/**
 * #198 AC 3: the palette that moved out of `wear/ui/Theme.kt` and five wear screens is the palette
 * that arrived, value for value.
 *
 * Epic #196 lists "the watch's colors (owner directive: same colors for now)" among the things that
 * must survive the phone, so this story is a relocation and a retune would be a scope breach that
 * nothing else could catch: a colour is not covered by any other test here, and the repo has no
 * lint step. Every literal below was read off `wear/src/main/kotlin/.../ui/` at `origin/develop`
 * before the move.
 *
 * A pin like this is a change-detector on purpose. It fails when someone edits a value, which is
 * exactly the event the owner directive says must be a decision rather than a side effect — and the
 * failure message says where to take that decision.
 */
class PaletteTest {

    private val repoRoot: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile }
        ?: error("Could not find the repo root from ${File("").absolutePath}")

    /**
     * Constant name to its exact pre-move watch value.
     *
     * Keyed by name rather than asserted inline so [every constant is pinned] can hold this to the
     * real field list. A pin file that quietly stops covering a constant is the failure mode of the
     * pattern, and it looks identical to a passing one.
     */
    private val pinned: Map<String, Long> = mapOf(
        "PRIMARY_ARGB" to 0xFFFFD700L,
        "PRIMARY_VARIANT_ARGB" to 0xFFB8860BL,
        "SECONDARY_ARGB" to 0xFF64B5F6L,
        "SECONDARY_VARIANT_ARGB" to 0xFF1565C0L,
        "ERROR_ARGB" to 0xFFCF6679L,
        "ON_ACCENT_ARGB" to 0xFF1A1A2EL,
        "SURFACE_ARGB" to 0xFF2A2A40L,
        "LIST_ROW_ARGB" to 0xFF2A2A50L,
        "NEUTRAL_BUTTON_ARGB" to 0xFF555577L,
        "DISABLED_BUTTON_ARGB" to 0xFF3A4A5EL,
    )

    /** Every `Long` constant the palette file publishes, read off the compiled class. */
    private fun paletteConstants(): Map<String, Long> =
        Class.forName("com.racetimer.shared.PaletteKt").declaredFields
            .filter { Modifier.isStatic(it.modifiers) && it.type == java.lang.Long.TYPE }
            .associate { it.isAccessible = true; it.name to it.getLong(null) }

    @Test
    fun `each relocated constant still holds its pre-move watch value`() {
        val actual = paletteConstants()
        // Reading nothing would pass every assertion below it — the absent-result-reads-as-clean
        // shape. Locating the constants is the precondition, not a courtesy check.
        assertTrue("no palette constants were found on PaletteKt", actual.isNotEmpty())

        val moved = pinned.mapValues { (name, _) -> actual[name] }
        assertEquals(
            "A palette value moved. #198 relocated these from the watch unchanged and epic #196 " +
                "records the owner directive to keep the watch's colors for now, so a change here " +
                "is a design decision to take with the owner - not a diff to make green.",
            pinned,
            moved,
        )
    }

    @Test
    fun `every constant is pinned`() {
        val unpinned = paletteConstants().keys - pinned.keys
        assertEquals(
            "Palette constants with no pin. A value that reaches both form factors and is asserted " +
                "nowhere is the state #198 existed to end; add it to `pinned` with the value it " +
                "shipped at.",
            emptySet<String>(),
            unpinned,
        )
    }

    /**
     * `ON_ACCENT_ARGB` is defined **from** [BG_NORMAL_ARGB], and that has to be asserted on the
     * source text because it cannot be asserted on the value.
     *
     * *Measured* during this story's mutation pass: replacing the reference with the literal
     * `0xFF1A1A2EL` reddened **0** of 403 shared tests, against a written prediction of 0. Kotlin
     * inlines a `const`, so `assertEquals(BG_NORMAL_ARGB, ON_ACCENT_ARGB)` compares two identical
     * numbers whichever way the file is written — the constraint and the unconstrained case agree
     * on every input there is. No runtime assertion can tell the two apart.
     *
     * It is worth asserting anyway: the literal form is two copies of one navy, one of which is
     * free to drift, which is the exact state #198 removed from `Theme.kt` and `colors.xml`.
     */
    @Test
    fun `the ink on an accent is defined from the normal background, not copied from it`() {
        val palette = File(repoRoot, "shared/src/main/kotlin/com/racetimer/shared/Palette.kt")
        // A scan of a file that is not there passes silently. Locating it is the precondition.
        assertTrue("the palette is at ${palette.path}", palette.isFile)

        val definitions = palette.readLines()
            .map { it.trim() }
            .filter { it.startsWith("const val ON_ACCENT_ARGB") }
        assertEquals(
            "expected exactly one definition of ON_ACCENT_ARGB in Palette.kt",
            1,
            definitions.size,
        )
        assertEquals(
            "ON_ACCENT_ARGB must be defined from BG_NORMAL_ARGB rather than repeating its value. " +
                "The two are the same navy; writing the number twice is how one colour becomes " +
                "two, and no value assertion can see the difference - only this line can.",
            "const val ON_ACCENT_ARGB = BG_NORMAL_ARGB",
            definitions.single(),
        )
    }

    @Test
    fun `every palette colour is fully opaque`() {
        val translucent = paletteConstants().filterValues { (it ushr 24) != 0xFFL }
        assertEquals(
            "Palette constants with a non-opaque alpha byte. A dropped alpha renders as invisible " +
                "rather than as wrong, so it survives every screenshot taken indoors.",
            emptyMap<String, Long>(),
            translucent,
        )
    }
}
