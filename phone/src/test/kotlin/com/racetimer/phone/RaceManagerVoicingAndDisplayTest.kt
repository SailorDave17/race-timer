package com.racetimer.phone

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.racetimer.phone.ui.TAG_CONTINUE
import com.racetimer.shared.BuiltInSequences
import com.racetimer.shared.TimerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The two no-new-code criteria of #206 — AC 3 (voicing) and AC 4 (screen policy).
 *
 * Both say the phone should *not* have gained something, which is the hardest shape of claim to
 * test and the easiest to discharge by inspection and call done. `ModuleBoundaryTest` covers the
 * textual half of AC 3; what is here is the half a source scan cannot reach — that the objects the
 * phone actually runs are shared's own, and that a whole race-manager race changes nothing about
 * the display.
 */
@RunWith(RobolectricTestRunner::class)
class RaceManagerVoicingAndDisplayTest {

    @get:Rule
    val compose = createComposeRule()

    // --- AC 3: the voicing is shared's, unmodified -------------------------------

    @Test
    fun `the race-manager sequences the console runs are shared's own objects`() {
        val offered = PhoneRaceRunner.CONSOLE_SEQUENCES.filter { it.countUpAfterFinish }

        // Identity, not equality. `assertEquals` on a data class would pass just as well against a
        // faithful local copy — and a faithful copy is precisely what goes wrong later, when shared
        // changes and the copy does not. Identity is the only assertion that cannot be satisfied by
        // re-declaring the same values.
        assertEquals(2, offered.size)
        assertSame(BuiltInSequences.usSailingRaceManager, offered[0])
        assertSame(BuiltInSequences.scholasticRaceManager, offered[1])
    }

    @Test
    fun `the committee voicing that reaches the engine is the shared cue list, cue for cue`() {
        val runner = PhoneRaceRunner()
        runner.select(BuiltInSequences.scholasticRaceManager)

        // Through the runner rather than off the constant: `select` is what the picker calls, and
        // it is the path a re-declaration would most plausibly sit on. Comparing the *loaded*
        // sequence is what makes this a claim about what the officer will hear.
        assertSame(BuiltInSequences.scholasticRaceManager, runner.engine.loadedSequence)
        assertEquals(
            BuiltInSequences.scholasticRaceManager.cues,
            runner.engine.loadedSequence?.cues,
        )
        // The distinguishing fact, named: below the minute the committee sequence doubles the three
        // flag marks where the sailor one does not. If this ever stops differing, the two-audience
        // voicing has collapsed into one and AC 3 is about nothing.
        assertNotEquals(
            "the committee and sailor tails are identical — there is no two-audience voicing left",
            BuiltInSequences.scholastic.cues,
            BuiltInSequences.scholasticRaceManager.cues,
        )
    }

    // --- AC 4: count-up gets the officer's choice, and nothing state-driven ------

    /**
     * #206 AC 4, settled as the phone's screen policy actually stands.
     *
     * The criterion was written asking the count-up to "follow the shared rules for that state".
     * That was the phone's model when this story was filed; #199 and #225 then made both display
     * properties the **officer's** choice, made once per launch, with no engine state reaching the
     * display path at all — `ModuleBoundaryTest` has two tests holding that boundary. So the rule
     * count-up follows is the officer's, and the substantive claim left in AC 4 is that count-up
     * does not get a rule of its own.
     *
     * That is what this measures, and it needs a running race to mean anything: the whole point is
     * that the *transitions* — gun, count-up, End Race — produce no display call. A test that only
     * read the choice would pass on a build that re-applied a state-derived value every tick.
     *
     * `DisplayChoiceRoutingTest` already drives the app into RUNNING and asserts the same property
     * there (#225 AC 4). This is that claim extended over the two states RUNNING cannot reach, and
     * they are the two the watch's shared table actually disagrees on — so if any state was going
     * to acquire a rule of its own, it was one of these.
     */
    @Test
    fun `a whole race-manager race applies the officer's choice once and never re-decides`() {
        val applied = mutableListOf<DisplayChoice>()
        val app = RaceTimerAppHarness(compose)

        compose.setContent {
            GlobalSnapshotFlushLoop()
            RaceTimerApp(applyDisplay = { applied += it }, runner = app.runner)
        }
        // The list is empty here and not after — the control that makes every assertion below a
        // statement about *this* race rather than about a mechanism that never fires at all.
        assertEquals("something applied before Continue", emptyList<DisplayChoice>(), applied)

        compose.onNodeWithTag(TAG_CONTINUE).performClick()
        // The application happens in a LaunchedEffect, so the click alone does not land it.
        compose.waitForIdle()
        assertEquals(listOf(DisplayChoice.INITIAL), applied)

        compose.onNodeWithText(BuiltInSequences.scholasticRaceManager.name)
            .performScrollTo().performClick()
        compose.onNodeWithText("Start").performClick()
        app.advance(BuiltInSequences.scholasticRaceManager.totalMs + 4_000L, stepMs = 1_000L)
        assertEquals(TimerState.COUNTING_UP, app.runner.engine.currentState)
        compose.onNodeWithText("End Race").performClick()
        assertEquals(TimerState.RACE_ENDED, app.runner.engine.currentState)

        // Still exactly one application, after a countdown, a gun, a count-up and a freeze. A
        // count-up that dropped brightness the way the watch's shared table does — which is the
        // literal reading of AC 4, and a real product question filed separately — would land a
        // second entry here and redden this.
        assertEquals(
            "the display was re-decided during the race; count-up must inherit the officer's " +
                "choice, not derive one from engine state (#199, #225)",
            listOf(DisplayChoice.INITIAL),
            applied,
        )
    }
}
