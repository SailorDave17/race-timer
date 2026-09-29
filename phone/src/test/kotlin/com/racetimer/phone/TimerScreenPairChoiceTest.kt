package com.racetimer.phone

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.racetimer.phone.ui.PhoneReadout
import com.racetimer.phone.ui.TAG_SYNC_TO_PHONE
import com.racetimer.phone.ui.TAG_SYNC_TO_WATCH
import com.racetimer.phone.ui.TimerScreen
import com.racetimer.shared.TimerState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The phone's choice between its own start and the watch's (#220): the owner's rule at pickup is
 * that the later tap wins and the console says so, with a way to move both devices to either start.
 *
 * Which start is which, and what choosing does to both devices, is `:shared`'s (`PairRaceTest`). This
 * covers what only the screen knows: the choice is up while the countdown runs and at no other time,
 * and each half reaches its own answer.
 *
 * **Not covered here: the two halves drawing at one height.** On the owner's SM-S918U "Sync to phone"
 * wrapped and "Sync to watch" did not, and the fix ties their heights. A test of that tie needs a width
 * where exactly one label wraps, and under Robolectric's text metrics the two labels measure the same,
 * so no such width exists — measured, by a positive control that searched 420 dp down to 160 dp and
 * found none. A width where both wrap passes with the tie removed (also measured). So the fix is
 * verified on the phone, by screenshot, and not claimed here.
 */
@RunWith(RobolectricTestRunner::class)
class TimerScreenPairChoiceTest {

    @get:Rule
    val compose = createComposeRule()

    private var toPhone = 0
    private var toWatch = 0

    private fun render(state: TimerState, choice: String?) {
        compose.setContent {
            GlobalSnapshotFlushLoop()
            TimerScreen(
                readout = PhoneReadout.of(state, 125_000L, 0L),
                sequenceName = "US Sailing 5-4-1-Go",
                state = state,
                onStart = {},
                onStop = {},
                onSync = {},
                pairChoice = choice,
                onSyncToPhone = { toPhone++ },
                onSyncToWatch = { toWatch++ },
            )
        }
    }

    @Test
    fun `the choice is up while the countdown runs, and each half reaches its own answer`() {
        render(TimerState.RUNNING, LINE)
        compose.onNodeWithText(LINE).assertIsDisplayed()

        compose.onNodeWithTag(TAG_SYNC_TO_PHONE).performClick()
        assertEquals(1 to 0, toPhone to toWatch)
        compose.onNodeWithTag(TAG_SYNC_TO_WATCH).performClick()
        assertEquals(1 to 1, toPhone to toWatch)
    }

    @Test
    fun `no choice once the gun has fired`() {
        render(TimerState.COUNTING_UP, LINE)
        compose.onAllNodesWithTag(TAG_SYNC_TO_PHONE).assertCountEquals(0)
        compose.onNodeWithText(LINE).assertDoesNotExist()
    }

    @Test
    fun `no choice on a finished race`() {
        render(TimerState.FINISHED, LINE)
        compose.onAllNodesWithTag(TAG_SYNC_TO_WATCH).assertCountEquals(0)
    }

    @Test
    fun `no choice without a conflict`() {
        render(TimerState.RUNNING, null)
        compose.onAllNodesWithTag(TAG_SYNC_TO_PHONE).assertCountEquals(0)
        compose.onAllNodesWithTag(TAG_SYNC_TO_WATCH).assertCountEquals(0)
    }

    private companion object {
        const val LINE = "Watch started too, 0.6 s later — both following the watch"
    }
}
