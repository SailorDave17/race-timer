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
 * Deliberately renders [TimerScreen] alone rather than the whole app — the screen's own contract
 * (which controls exist in which state, which callback a tap reaches) is answerable without the
 * app around it, and a narrower test names a failure more precisely. The `RaceTimerApp` glue this
 * cannot see is covered by [RaceTimerAppTimerScreenTest], full-app, since #239.
 *
 * *(Until #239 this comment blamed the full composition's display poll loop for hanging the
 * idling strategy. Measured, the poll was innocent: the hang was compose's global-snapshot
 * flusher being dead in every Robolectric class but the JVM's first — see
 * [GlobalSnapshotFlushLoop] — and it would have hit any full-app composition, dwell or no dwell.)*
 */
@RunWith(RobolectricTestRunner::class)
class TimerScreenSyncTest {

    @get:Rule
    val compose = createComposeRule()

    private val taps = mutableListOf<String>()

    private fun render(state: TimerState) {
        compose.setContent {
            TimerScreen(
                readout = PhoneReadout.of(TimerState.RUNNING, 125_000L, 0L),
                sequenceName = "Scholastic (ICSA)",
                state = state,
                onStart = { taps += "start" },
                onStop = { taps += "stop" },
                onSync = { taps += "sync" },
            )
        }
    }

    @Test
    fun `tapping Sync fires the sync callback and nothing else`() {
        render(state = TimerState.RUNNING)
        compose.onNodeWithText("Sync").performClick()
        // Exactly the sync callback: a Sync wired to Stop would end the race the officer was
        // trying to correct, which is why "and nothing else" is the load-bearing half.
        assertEquals(listOf("sync"), taps)
    }

    @Test
    fun `the Sync control exists only while the race runs`() {
        render(state = TimerState.IDLE)
        compose.onNodeWithText("Start").assertIsDisplayed()
        compose.onAllNodesWithText("Sync").assertCountEquals(0)
    }

    @Test
    fun `while running the controls are Sync and Stop, with Start gone`() {
        render(state = TimerState.RUNNING)
        compose.onNodeWithText("Sync").assertIsDisplayed()
        compose.onNodeWithText("Stop").assertIsDisplayed()
        compose.onAllNodesWithText("Start").assertCountEquals(0)
    }
}
