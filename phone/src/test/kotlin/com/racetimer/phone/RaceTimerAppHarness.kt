package com.racetimer.phone

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.racetimer.phone.ui.PhoneReadout
import com.racetimer.phone.ui.TAG_CONTINUE
import com.racetimer.phone.ui.TAG_FULL_BRIGHTNESS
import com.racetimer.shared.BuiltInSequences
import com.racetimer.shared.MonotonicClock
import com.racetimer.shared.RaceSequence
import org.junit.Assert.assertEquals

/**
 * Drives the **whole app** — [RaceTimerApp], not one screen — through a running race, so a test can
 * assert what an officer actually sees while the countdown moves (#239).
 *
 * ## The two clocks, and why a dwell that moves one of them is vacuous
 *
 * The running timer screen depends on two clocks that are entirely independent of each other:
 *
 *  - the **engine's** [MonotonicClock] — `SystemClock.elapsedRealtime` in production, which
 *    Robolectric holds frozen for the whole test unless something advances the looper;
 *  - **Compose's virtual test clock**, which is what the composition's display-poll `delay` sleeps
 *    on, and which only moves when a test advances it or the idling strategy pumps a frame.
 *
 * Neither drives the other. *Measured 2026-08-14, both arms run:* advancing Compose's clock alone
 * re-polls a frozen engine — `elapsedRealtime` held at 110 ms across five virtual seconds, so the
 * screen redraws the same second every pass — and advancing Robolectric's clock alone moves the
 * engine while the poll never wakes, virtual time held at 112 ms across 11 s of `idleFor`. Either
 * way the countdown never changes, the test dwells on a still screen, and it passes having asserted
 * nothing.
 *
 * That is the trap this class exists to close: [advance] moves **both**, and every test using it
 * asserts the readout before and after, so a dwell that stopped exercising the poll fails instead of
 * going quietly green.
 */
internal class RaceTimerAppHarness(private val compose: ComposeContentTestRule) {

    private val clock = AdvanceableClock()

    /** The runner the app is driven through, so a test can assert engine state as well as screen. */
    val runner = PhoneRaceRunner(clock)

    /**
     * Compose the whole app and answer the display surface, leaving the sequence picker up.
     *
     * [fullBrightness] answers the launch surface's second switch, and [applyDisplay] records what
     * reaches the display mechanism — both defaulted, so every test that does not care about the
     * screen reads exactly as it did before #279. A test that *does* care has to be able to open on
     * the officer having asked for the panel, because that is the only condition under which a
     * count-up has anything to release.
     */
    fun launch(
        fullBrightness: Boolean = DisplayChoice.INITIAL.fullBrightness,
        applyDisplay: (DisplayChoice) -> Unit = {},
    ) {
        compose.setContent {
            // The #239 flush loop rides the same frame pump that would otherwise spin forever —
            // see GlobalSnapshotFlushLoop for the measured mechanism. Composed before the app so
            // it exists from the first composition, which is where the hang bites.
            GlobalSnapshotFlushLoop()
            RaceTimerApp(applyDisplay = applyDisplay, runner = runner)
        }
        if (fullBrightness != DisplayChoice.INITIAL.fullBrightness) {
            compose.onNodeWithTag(TAG_FULL_BRIGHTNESS).performClick()
        }
        compose.onNodeWithTag(TAG_CONTINUE).performClick()
        // The choice is applied from a LaunchedEffect, so the tap alone does not land it.
        compose.waitForIdle()
    }

    /**
     * Put the runner past the gun **before anything is composed** (#279).
     *
     * This is the state an activity recreation lands in, reproduced without one: the engine is
     * counting up in the service, and the fresh composition opens on the picker because
     * `onTimerScreen` is `remember`ed rather than saved. Robolectric's compose rule cannot recreate
     * an activity, so the state is arranged rather than caused — the part under test is what the
     * app does *given* that state, which is identical either way.
     *
     * Deliberately does not touch the composition clock: there is no composition yet.
     */
    fun runnerAlreadyCountingUp(sequence: RaceSequence = BuiltInSequences.scholasticRaceManager) {
        runner.select(sequence)
        runner.start()
        clock.nowMs += sequence.totalMs + PAST_GUN_MS
        runner.tick()
    }

    /**
     * Pick [sequence] and tap Start, then confirm the race is really on before returning.
     *
     * The Stop assertion is a positive control, not decoration: the controls are a function of
     * engine state, so "Stop" being on screen is the app itself reporting that it left IDLE. Without
     * it every assertion downstream would hold just as well on a countdown that never started.
     */
    fun startRace(sequence: RaceSequence = BuiltInSequences.usSailing) {
        // Scrolled to first. Since #206 the picker offers five sequences rather than three, so an
        // entry low in the list sits below the fold on a small phone and a bare click lands on
        // nothing — the same thing `CustomSequenceRoutingTest` measured for the Custom entry.
        compose.onNodeWithText(sequence.name).performScrollTo().performClick()
        compose.onNodeWithText("Start").performClick()
        compose.onNodeWithText("Stop").assertIsDisplayed()
    }

    /**
     * Move both clocks forward by [totalMs], in [STEP_MS] slices, and let the screen settle.
     *
     * The slice is a *sampling* choice and deliberately not the composition's refresh interval: the
     * readout is derived from the clock rather than accumulated, so any slice reads the correct time,
     * and a slice well under one second guarantees the display poll runs several times per displayed
     * second rather than once per assertion.
     */
    fun advance(totalMs: Long, stepMs: Long = STEP_MS) {
        require(stepMs > 0 && totalMs > 0 && totalMs % stepMs == 0L) {
            "advance() takes a positive whole multiple of its step ($stepMs ms), got $totalMs"
        }
        repeat((totalMs / stepMs).toInt()) {
            clock.nowMs += stepMs
            compose.mainClock.advanceTimeBy(stepMs)
        }
        compose.waitForIdle()
    }

    /**
     * Run the clock from the top of a sequence to just past its gun, in whole seconds (#206).
     *
     * A coarser step than [advance]'s default, and safe to be coarse for one reason worth stating:
     * `TimerEngine.tick` drains **every** cue whose boundary has passed on the tick that crosses
     * it, so a stride cannot step over the gun — it can only make the display change in bigger
     * jumps on the way there, which no assertion here is about. The fine slice still matters where
     * the readout itself is under test, and that is what [advance]'s default is for.
     *
     * Deliberately overshoots by [PAST_GUN_MS] rather than landing exactly on zero: a race-manager
     * sequence's whole subject is what happens *after* the gun, and an assertion taken at the
     * instant of it would be reading the boundary rather than the state it opens.
     */
    fun runPastTheGun(sequence: RaceSequence) {
        advance(sequence.totalMs + PAST_GUN_MS, stepMs = 1_000L)
    }

    /**
     * What the big readout currently says.
     *
     * Found by shape rather than by test tag — a countdown is the only text on this screen that
     * looks like a clock, and the alternative is a tag on production code that exists only for this.
     * The `single` is load-bearing: two matches would mean the screen gained a second clock-shaped
     * line and the assertion below had quietly started reading the wrong one.
     */
    fun readout(): String {
        val texts = compose
            .onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text))
            .fetchSemanticsNodes()
            .flatMap { node -> node.config[SemanticsProperties.Text].map { it.text } }
        val clockShaped = texts.filter { it.matches(READOUT_SHAPE) || it == PhoneReadout.GUN_LABEL }
        return clockShaped.singleOrNull()
            ?: error("expected exactly one clock-shaped text on screen, found $clockShaped in $texts")
    }

    /** Assert the readout, naming both sides — a bare node lookup reports only what it failed to find. */
    fun assertReadout(expected: String) =
        assertEquals("the countdown on screen", expected, readout())

    private class AdvanceableClock(var nowMs: Long = 0L) : MonotonicClock {
        override fun elapsedMs(): Long = nowMs
    }

    // Not private since #279: a test that has to land *inside* a timed window needs to know how
    // much of it [runPastTheGun] has already spent, and re-stating the number in the test is the
    // duplication that makes the window drift out from under it.
    companion object {
        const val STEP_MS = 250L

        /** How far past the gun [runPastTheGun] lands — a few whole seconds of count-up. */
        const val PAST_GUN_MS = 4_000L

        val READOUT_SHAPE = Regex("""\d+:\d{2}""")
    }
}
