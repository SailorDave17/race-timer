package com.racetimer.phone

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.racetimer.phone.ui.LEAD_IN_LABEL
import com.racetimer.phone.ui.PhoneReadout
import com.racetimer.phone.ui.TimerScreen
import com.racetimer.shared.TimerState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The lead-in control's contract on the timer screen (#207 AC 2's screen half): it exists beside
 * Start exactly when offered, yields to a resume offer, reaches its own callback and nothing
 * else's, and Sync is gone for the run-up.
 *
 * [TimerScreen] alone, as [TimerScreenSyncTest] and [TimerScreenResumeOfferTest] are — the screen's
 * own contract is answerable without the app around it. What decides `leadInOffered` and
 * `inLeadIn` is [PhoneLeadInTest], full-app.
 */
@RunWith(RobolectricTestRunner::class)
class TimerScreenLeadInTest {

    @get:Rule
    val compose = createComposeRule()

    private val taps = mutableListOf<String>()

    private fun render(
        state: TimerState,
        leadInOffered: Boolean = false,
        inLeadIn: Boolean = false,
        resumeOffer: String? = null,
    ) {
        compose.setContent {
            TimerScreen(
                readout = PhoneReadout.of(state, 250_000L, 0L),
                sequenceName = "Scholastic - Race Manager",
                state = state,
                onStart = { taps += "start" },
                onStop = { taps += "stop" },
                onSync = { taps += "sync" },
                resumeOffer = resumeOffer,
                onResume = { taps += "resume" },
                onStartOver = { taps += "startOver" },
                leadInOffered = leadInOffered,
                inLeadIn = inLeadIn,
                onLeadIn = { taps += "leadIn" },
            )
        }
    }

    @Test
    fun `an offered lead-in sits beside Start and reaches its own callback only`() {
        render(state = TimerState.IDLE, leadInOffered = true)
        compose.onNodeWithText("Start").assertIsDisplayed()
        compose.onNodeWithText(LEAD_IN_LABEL).performClick()
        // Exactly the lead-in callback: a Lead-in wired to Start would run a plain race under a
        // control that promised a run-up, which is the misaligned start the whole feature exists
        // to prevent.
        assertEquals(listOf("leadIn"), taps)
    }

    @Test
    fun `Start beside the lead-in is still Start`() {
        render(state = TimerState.IDLE, leadInOffered = true)
        compose.onNodeWithText("Start").performClick()
        assertEquals(listOf("start"), taps)
    }

    @Test
    fun `a sequence that offers no lead-in shows no control for it`() {
        render(state = TimerState.IDLE, leadInOffered = false)
        compose.onNodeWithText("Start").assertIsDisplayed()
        compose.onAllNodesWithText(LEAD_IN_LABEL).assertCountEquals(0)
    }

    @Test
    fun `a resume offer outranks the lead-in control`() {
        render(state = TimerState.IDLE, leadInOffered = true, resumeOffer = "3:42")
        compose.onNodeWithText("Resume").assertIsDisplayed()
        compose.onNodeWithText("Start over").assertIsDisplayed()
        // The question that screen asks is *this race or a fresh one*; a third control would put
        // a second kind of fresh start under the thumb answering it.
        compose.onAllNodesWithText(LEAD_IN_LABEL).assertCountEquals(0)
    }

    @Test
    fun `Sync is gone for the lead-in and Stop stays`() {
        render(state = TimerState.RUNNING, inLeadIn = true)
        compose.onAllNodesWithText("Sync").assertCountEquals(0)
        compose.onNodeWithText("Stop").performClick()
        assertEquals(listOf("stop"), taps)
    }

    @Test
    fun `Sync is back once the sequence proper is under way`() {
        // The positive control for the absence above: same state, flag cleared, control present.
        render(state = TimerState.RUNNING, inLeadIn = false)
        compose.onNodeWithText("Sync").assertIsDisplayed()
        compose.onNodeWithText("Stop").assertIsDisplayed()
        compose.onAllNodesWithText(LEAD_IN_LABEL).assertCountEquals(0)
    }
}
