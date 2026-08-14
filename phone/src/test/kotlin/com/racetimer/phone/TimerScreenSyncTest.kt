package com.racetimer.phone

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.racetimer.phone.ui.PhoneReadout
import com.racetimer.phone.ui.TimerScreen
import com.racetimer.shared.TimerState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The Sync control exists exactly while running, and a tap reaches the sync callback — not a
 * neighbouring one (#204 AC 1's control half).
 *
 * Deliberately renders [TimerScreen] alone rather than the whole app. The full composition's
 * display poll loop can hang the Compose-Robolectric idling strategy nondeterministically — the
 * same test passed and then hung on identical code — and a flaky proof is worse than a narrower
 * solid one. What this costs is the two-line `RaceTimerApp` glue (`onSync = { syncRace() }`)
 * going untested; that hop is disclosed in the story rather than papered over, and the engine
 * half of the chain is `CueDispatchIsScheduledTest`'s sync re-anchor test, at the runner seam.
 * The harness flake itself is filed as its own issue.
 */
@RunWith(RobolectricTestRunner::class)
class TimerScreenSyncTest {

    @get:Rule
    val compose = createComposeRule()

    private val taps = mutableListOf<String>()

    private fun render(running: Boolean) {
        compose.setContent {
            TimerScreen(
                readout = PhoneReadout.of(TimerState.RUNNING, 125_000L, 0L),
                sequenceName = "Scholastic (ICSA)",
                running = running,
                onStart = { taps += "start" },
                onStop = { taps += "stop" },
                onSync = { taps += "sync" },
            )
        }
    }

    @Test
    fun `tapping Sync fires the sync callback and nothing else`() {
        render(running = true)
        compose.onNodeWithText("Sync").performClick()
        // Exactly the sync callback: a Sync wired to Stop would end the race the officer was
        // trying to correct, which is why "and nothing else" is the load-bearing half.
        assertEquals(listOf("sync"), taps)
    }

    @Test
    fun `the Sync control exists only while the race runs`() {
        render(running = false)
        compose.onNodeWithText("Start").assertIsDisplayed()
        compose.onAllNodesWithText("Sync").assertCountEquals(0)
    }

    @Test
    fun `while running the controls are Sync and Stop, with Start gone`() {
        render(running = true)
        compose.onNodeWithText("Sync").assertIsDisplayed()
        compose.onNodeWithText("Stop").assertIsDisplayed()
        compose.onAllNodesWithText("Start").assertCountEquals(0)
    }
}
