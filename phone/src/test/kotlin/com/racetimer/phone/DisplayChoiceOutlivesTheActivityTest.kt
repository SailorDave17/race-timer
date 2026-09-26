package com.racetimer.phone

import androidx.lifecycle.ViewModelProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

/**
 * The officer's display answers last for the life of the **process**, not the activity (#281, #225).
 *
 * ### The claim that was false
 *
 * #225 ratified this retention and `DisplayChoiceViewModel`'s KDoc stated it in as many words — "it
 * **dies with the process**, which is the whole retention policy". A `ViewModel` gives you that only
 * if the store you resolve it from lives that long, and an activity-scoped `viewModel()` does not:
 * it is cleared when the activity finishes. #281 measured the consequence on the owner's SM-S918U —
 * "Screen for today" re-asked over a race still counting in a live process with its foreground
 * service ticking.
 *
 * ### What each half of this file proves
 *
 * The first test is the **mechanism**: [RaceTimerPhoneApplication] owns a store, and a view-model
 * taken from it is one object with one state, however many times it is resolved. The Application
 * outlives every activity in the process by construction, so that is the retention #225 asked for.
 *
 * The second is the **wiring**, and it reads source deliberately. The two spellings — resolving from
 * the Application, or letting the parameter default to `viewModel()` — compile to the same types and
 * differ only in an object identity that no test in this harness can observe, because Robolectric's
 * compose rule cannot destroy and rebuild a real activity. Where the correct and incorrect forms are
 * indistinguishable at runtime, the source is the only instrument there is (cairn
 * `a-guard-that-reads-source-must-survive-its-own-docs`). It is a change-detector on spelling and is
 * accepted as one: the alternative is the claim above resting on nothing.
 *
 * The product-level behaviour — a rebuilt composition not re-asking — is asserted by
 * `ReattachToLiveRaceTest#the officer is not asked to set the screen up again mid-race`, against the
 * harness's recreation model.
 */
@RunWith(RobolectricTestRunner::class)
class DisplayChoiceOutlivesTheActivityTest {

    private val repoRoot: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile }
        ?: error("Could not find the repo root from ${File("").absolutePath}")

    @Test
    fun `the application owns one display choice, and it keeps its answer`() {
        val application = RuntimeEnvironment.getApplication() as RaceTimerPhoneApplication

        val first = ViewModelProvider(application)[DisplayChoiceViewModel::class.java]
        // Positive control on the arrangement: a fresh view-model has not been answered, so the
        // assertion below is about state being *kept* rather than about a flag that was already set.
        assertEquals("a fresh display choice reads as answered", false, first.answered)

        first.setFullBrightness(true)
        first.confirm()
        first.answerCountUpBrightness(keepBright = true)

        val second = ViewModelProvider(application)[DisplayChoiceViewModel::class.java]

        assertSame("the application handed out a second display choice", first, second)
        assertEquals("the officer would be asked to set the screen up again", true, second.answered)
        assertEquals(true, second.choice.fullBrightness)
        assertEquals(
            "the count-up question would be re-offered after a recreation",
            true,
            second.countUpKeepsBrightness,
        )
    }

    @Test
    fun `MainActivity resolves the display choice from the application, not from the activity`() {
        val source = File(repoRoot, "phone/src/main/kotlin/com/racetimer/phone/MainActivity.kt")
        assertTrue("MainActivity.kt was not found — this scan read nothing", source.isFile)
        val text = source.readText()

        // Not a bare `contains`: the point is the *owner* passed to ViewModelProvider. Naming the
        // application class is what distinguishes this from any other view-model lookup.
        assertTrue(
            "MainActivity no longer resolves a ViewModel from ${RaceTimerPhoneApplication::class.simpleName}. " +
                "#225 requires the officer's display answers to last the life of the process; an " +
                "activity-scoped viewModel() dies with the activity, which is the defect #281 measured.",
            text.contains("ViewModelProvider(applicationContext as RaceTimerPhoneApplication)"),
        )

        // And it must actually be handed to the app — a lookup whose result is never passed would
        // satisfy the assertion above while the composition still used the activity-scoped default.
        assertTrue(
            "the process-scoped display choice is resolved but never handed to RaceTimerApp",
            text.contains("displayChoice = processDisplayChoice"),
        )
    }

    @Test
    fun `the application store is never cleared by the app's own code`() {
        // A `ViewModelStore.clear()` anywhere under :phone would reintroduce #281 through a
        // different door — the state would die on whatever event called it rather than with the
        // process. Nothing needs to clear it: the process ending is the only event that should end
        // this state, and that takes the whole object with it.
        val offenders = File(repoRoot, "phone/src/main/kotlin")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                file.readLines().withIndex()
                    .filterNot { (_, line) -> line.trimStart().startsWith("//") }
                    .filter { (_, line) -> line.contains("viewModelStore.clear()") }
                    .map { (index, _) -> "${file.name}:${index + 1}" }
            }
            .toList()

        assertEquals(
            "the process-scoped ViewModelStore is cleared under :phone, which ends the display " +
                "choice on something other than the process ending (#225, #281)",
            emptyList<String>(),
            offenders,
        )
        // The scan has to have read something, or an empty offender list is a fact about the walk.
        assertNotEquals(
            "no phone sources were scanned",
            0,
            File(repoRoot, "phone/src/main/kotlin").walkTopDown().count { it.extension == "kt" },
        )
    }
}
