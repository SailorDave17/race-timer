package com.racetimer.phone

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.racetimer.shared.BuiltInSequences
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What the watch's controls leave on the phone's screen (#221): the line about its Sync or about a
 * control for a race not running here, and the pre-start screen following the watch's setup.
 *
 * The words and the rules are `:shared`'s; the service carries a control out (`PairControlsTest`).
 * This is the screen's half — how long the line stands, what it stands over, and that a setup the
 * watch sent is what the pre-start screen names — driven through the real `RaceTimerApp`.
 */
@RunWith(RobolectricTestRunner::class)
class PairNewsOnScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val app = RaceTimerAppHarness(compose)

    @Test
    fun `the watch's Sync reads over the standing flag for its dwell, and the flag is back after`() {
        app.launch(readPairNotice = { FLAG })
        app.joinFromTheWatch()
        app.advance(1_000L)
        compose.onNodeWithText(FLAG).assertIsDisplayed()

        app.pairNews = PairNews(SYNCED, serial = 1L)
        app.advance(1_000L)
        compose.onNodeWithText(SYNCED).assertIsDisplayed()
        compose.onNodeWithText(FLAG).assertDoesNotExist()

        app.advance(PAIR_NEWS_DWELL_MS)
        compose.onNodeWithText(SYNCED).assertDoesNotExist()
        compose.onNodeWithText(FLAG).assertIsDisplayed()
    }

    @Test
    fun `the same words twice are two lines, each with its own dwell`() {
        app.launch()
        app.joinFromTheWatch()
        app.advance(1_000L)

        app.pairNews = PairNews(STALE, serial = 1L)
        app.advance(2_000L)
        app.pairNews = PairNews(STALE, serial = 2L)
        app.advance(2_000L)
        compose.onNodeWithText(STALE).assertIsDisplayed()

        app.advance(1_500L)
        compose.onNodeWithText(STALE).assertDoesNotExist()
    }

    @Test
    fun `the watch's setup is the sequence the pre-start screen names`() {
        app.launch()
        compose.onNodeWithText(BuiltInSequences.club.name).performScrollTo().performClick()
        compose.onNodeWithText(BuiltInSequences.club.name).assertIsDisplayed()
        // Club and Scholastic are both 3:00, so nothing on the readout moves when one replaces the
        // other: only the name can say which race the next Start runs.
        assertEquals(BuiltInSequences.club.totalMs, BuiltInSequences.scholastic.totalMs)

        // What the service does with the watch's setup, and what MainActivity then does.
        app.runner.select(BuiltInSequences.scholastic)
        app.selectionVersion++
        app.advance(1_000L)

        compose.onNodeWithText(BuiltInSequences.scholastic.name).assertIsDisplayed()
        compose.onNodeWithText(BuiltInSequences.club.name).assertDoesNotExist()
    }

    // --- #222: the watch out of range mid-race ------------------------------------------------------

    @Test
    fun `a lost link stands on the notice line while the race runs, and goes when the watch is back`() {
        var lost = false
        app.launch(readPairLinkLost = { lost })
        app.startRace()
        app.advance(1_000L)
        compose.onNodeWithText(LOST).assertDoesNotExist()

        lost = true
        app.advance(1_000L)
        compose.onNodeWithText(LOST).assertIsDisplayed()
        app.advance(30_000L)
        compose.onNodeWithText(LOST).assertIsDisplayed()

        lost = false
        app.advance(1_000L)
        compose.onNodeWithText(LOST).assertDoesNotExist()
    }

    @Test
    fun `a lost link stands through the count-up too`() {
        app.launch(readPairLinkLost = { true })
        app.startRace(BuiltInSequences.scholasticRaceManager)
        app.runPastTheGun(BuiltInSequences.scholasticRaceManager)

        compose.onNodeWithText("End Race").assertIsDisplayed()
        compose.onNodeWithText(LOST).assertIsDisplayed()
    }

    @Test
    fun `a lost link is the lowest line, under the flag that asks for Sync and under the watch's news`() {
        var flag: String? = null
        app.launch(readPairNotice = { flag }, readPairLinkLost = { true })
        app.joinFromTheWatch()
        app.advance(1_000L)
        compose.onNodeWithText(LOST).assertIsDisplayed()

        flag = APART
        app.advance(1_000L)
        compose.onNodeWithText(APART).assertIsDisplayed()
        compose.onNodeWithText(LOST).assertDoesNotExist()

        app.pairNews = PairNews(CORRECTED, serial = 1L)
        app.advance(1_000L)
        compose.onNodeWithText(CORRECTED).assertIsDisplayed()

        flag = null
        app.advance(PAIR_NEWS_DWELL_MS)
        compose.onNodeWithText(LOST).assertIsDisplayed()
    }

    @Test
    fun `a lost link says nothing on the pre-start screen, where the pair row already speaks`() {
        app.launch(readPairLinkLost = { true })
        compose.onNodeWithText(BuiltInSequences.usSailing.name).performScrollTo().performClick()
        app.advance(1_000L)

        compose.onNodeWithText("Start").assertIsDisplayed()
        compose.onNodeWithText(LOST).assertDoesNotExist()
    }

    private companion object {
        const val FLAG = "Watch start ±180 ms — tap Sync to confirm"
        const val SYNCED = "Watch synced → 4:00"
        const val STALE = "Watch synced a race not running here"
        const val LOST = "Watch out of range — counting alone"
        const val APART = "Watch gun 8.0 s apart — tap Sync to confirm"
        const val CORRECTED = "Watch back — gun matched, 40 ms"
    }
}
