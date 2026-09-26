package com.racetimer.phone

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import com.racetimer.phone.ui.PhoneReadout
import com.racetimer.phone.ui.TAG_PAIR_STATUS
import com.racetimer.phone.ui.TimerScreen
import com.racetimer.shared.TimerState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The pair's status row is drawn on the pre-start screen and nowhere else (#219).
 *
 * Whether there is a row to draw at all is `pairStatusLine`'s rule, in `:shared`, where the JVM
 * tests cover it. This covers the other half, which only the screen knows: a running race, a
 * count-up and a finished race's summary lay out without it, so a status line can never crowd the
 * controls an officer reaches for once the sequence is under way.
 */
@RunWith(RobolectricTestRunner::class)
class TimerScreenPairStatusTest {

    @get:Rule
    val compose = createComposeRule()

    private fun render(state: TimerState, pairStatus: String?) {
        compose.setContent {
            GlobalSnapshotFlushLoop()
            TimerScreen(
                readout = PhoneReadout.of(state, 125_000L, 0L),
                sequenceName = "Scholastic (ICSA)",
                state = state,
                onStart = {},
                onStop = {},
                onSync = {},
                pairStatus = pairStatus,
            )
        }
    }

    @Test
    fun `the row is drawn on the pre-start screen`() {
        render(TimerState.IDLE, ROW)
        compose.onNodeWithTag(TAG_PAIR_STATUS).assertIsDisplayed().assertTextEquals(ROW)
    }

    @Test
    fun `no row while the race runs`() {
        render(TimerState.RUNNING, ROW)
        compose.onAllNodesWithTag(TAG_PAIR_STATUS).assertCountEquals(0)
    }

    @Test
    fun `no row while the race counts up`() {
        render(TimerState.COUNTING_UP, ROW)
        compose.onAllNodesWithTag(TAG_PAIR_STATUS).assertCountEquals(0)
    }

    @Test
    fun `no row on a finished race's summary`() {
        render(TimerState.RACE_ENDED, ROW)
        compose.onAllNodesWithTag(TAG_PAIR_STATUS).assertCountEquals(0)
    }

    @Test
    fun `nothing to report draws nothing`() {
        render(TimerState.IDLE, null)
        compose.onAllNodesWithTag(TAG_PAIR_STATUS).assertCountEquals(0)
    }

    private companion object {
        const val ROW = "Watch -7:12:05.042 ±31 ms"
    }
}
