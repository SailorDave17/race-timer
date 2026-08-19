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
 * The resume offer's controls (#205): the saved race's number is on screen, Resume and Start over
 * fire their own callbacks and nothing else's, and a running race shows no offer.
 *
 * [TimerScreen] alone, deterministically — the full-app harness is #239's flake.
 */
@RunWith(RobolectricTestRunner::class)
class TimerScreenResumeOfferTest {

    @get:Rule
    val compose = createComposeRule()

    private val taps = mutableListOf<String>()

    private fun render(state: TimerState, resumeOffer: String?, notice: String? = null) {
        compose.setContent {
            TimerScreen(
                readout = PhoneReadout.of(TimerState.IDLE, 180_000L, 0L),
                sequenceName = "Scholastic (ICSA)",
                state = state,
                onStart = { taps += "start" },
                onStop = { taps += "stop" },
                onSync = { taps += "sync" },
                notice = notice,
                resumeOffer = resumeOffer,
                onResume = { taps += "resume" },
                onStartOver = { taps += "startOver" },
            )
        }
    }

    @Test
    fun `the offer shows the number resuming will actually give, and Resume fires its callback`() {
        render(state = TimerState.IDLE, resumeOffer = "2:14")
        compose.onNodeWithText("Race under way — 2:14 left").assertIsDisplayed()
        compose.onNodeWithText("Resume").performClick()
        assertEquals(listOf("resume"), taps)
    }

    @Test
    fun `Start over fires its own callback, not Resume's and not Start's`() {
        render(state = TimerState.IDLE, resumeOffer = "2:14")
        compose.onNodeWithText("Start over").performClick()
        assertEquals(listOf("startOver"), taps)
        // The plain Start control is absent while the offer stands: two different starts on one
        // screen is exactly the ambiguity the offer exists to remove.
        compose.onAllNodesWithText("Start").assertCountEquals(0)
    }

    @Test
    fun `a running race shows no offer whatever persistence claims`() {
        render(state = TimerState.RUNNING, resumeOffer = "2:14")
        compose.onAllNodesWithText("Resume").assertCountEquals(0)
        compose.onNodeWithText("Stop").assertIsDisplayed()
    }

    @Test
    fun `the degraded-restore notice is on screen when owed`() {
        render(state = TimerState.IDLE, resumeOffer = null, notice = "Restored after a reboot — re-sync at the next flag")
        compose.onNodeWithText("Restored after a reboot — re-sync at the next flag").assertIsDisplayed()
    }
}
