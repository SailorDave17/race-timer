package com.racetimer.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests that every message the timer face can show is legible on every background it can show up on.
 *
 * Built the same way as [BannerLayoutTest], and for the same reason: the tests that make this file
 * worth anything are the ones asserting a *failure*. The un-scrimmed Tier 3 prompt — what shipped
 * until #123 — is asserted to fall below the bar, so this cannot be read as a rubber stamp on
 * whatever colours happen to be in `MessageContrast.kt`. Take the Tier 3 scrim back out and the
 * suite goes red.
 *
 * The reachable background set per surface is **derived** by driving [backgroundArgbFor] rather
 * than by restating its branches here, so a moved threshold cannot leave the guard measuring a
 * combination the screen no longer produces.
 *
 * **What this file cannot see, stated so nobody has to rediscover it.** `shared` has no view of
 * `wear`, so these tests prove the *constants* are legible — never that `TimerScreen` still draws
 * with them. *Measured* during #123's mutation pass: re-hardcoding `Color(0xFFFFC107)` back into the
 * prompt and deleting its scrim leaves this suite green **and** `:wear:assembleDebug` successful, so
 * the whole fix can be undone without reddening anything. What holds it is that `MessageContrast` is
 * the single definition and `TimerScreen` imports it; the repo has no lint step to enforce that
 * (a deliberate 2026-08-02 decision), so it rests on review. Treat a literal colour appearing in
 * `TimerScreen` as the regression this file is blind to.
 */
class MessageContrastTest {

    /** Enough samples either side of every threshold in [backgroundArgbFor] to reach all its branches. */
    private val remainingSamples = listOf(
        -60_000L, -1L, 0L, 1L, 9_999L, 10_000L, 10_001L, 59_999L, 60_000L, 60_001L, 300_000L,
    )

    private fun backgroundsWhen(statePredicate: (TimerState) -> Boolean): Set<Long> =
        TimerState.values()
            .filter(statePredicate)
            .flatMap { state -> remainingSamples.map { backgroundArgbFor(it, state) } }
            .toSet()

    private fun assertLegible(name: String, text: Long, scrim: Long?, backgrounds: Set<Long>) {
        for (bg in backgrounds) {
            val ratio = effectiveContrast(text, scrim, bg)
            assertTrue(
                "$name on background ${bg.toString(16)} is %.2f : 1, below the %.1f : 1 bar"
                    .format(ratio, WCAG_NORMAL_TEXT_MIN),
                ratio >= WCAG_NORMAL_TEXT_MIN,
            )
        }
    }

    // --- The arithmetic itself, against answers WCAG fixes independently of this repo ----------

    @Test fun `contrast of black on white is the WCAG maximum`() {
        assertEquals(21.0, contrastRatio(0xFF000000L, 0xFFFFFFFFL), 0.01)
    }

    @Test fun `a colour against itself has no contrast at all`() {
        assertEquals(1.0, contrastRatio(BG_ONE_MINUTE_ARGB, BG_ONE_MINUTE_ARGB), 0.0001)
    }

    @Test fun `an opaque scrim hides the background entirely`() {
        // Every background composites to the scrim itself, which is the property #102 bought.
        for (bg in listOf(BG_NORMAL_ARGB, BG_ONE_MINUTE_ARGB, BG_FINAL_TEN_ARGB, BG_FINISHED_ARGB)) {
            assertEquals(TIER1_SCRIM_ARGB, compositeOver(TIER1_SCRIM_ARGB, bg))
        }
    }

    // --- Which backgrounds each surface can actually be drawn on -------------------------------

    @Test fun `a running race reaches navy amber and red but never the finished green`() {
        // The re-sync prompt's whole exposure follows from this: MainActivity gates it on
        // `engine.currentState == RUNNING`, and green needs FINISHED or RACE_ENDED.
        assertEquals(
            setOf(BG_NORMAL_ARGB, BG_ONE_MINUTE_ARGB, BG_FINAL_TEN_ARGB),
            backgroundsWhen { it == TimerState.RUNNING },
        )
    }

    @Test fun `the pre-start screen is only ever navy`() {
        // The discard warning is set only in refreshUiState's IDLE branch and cleared by
        // clearResumeOffer the moment an engine holds a race, so navy is its whole world.
        assertEquals(setOf(BG_NORMAL_ARGB), backgroundsWhen { it == TimerState.IDLE })
    }

    // --- Every shipped surface, on every background it can reach --------------------------------

    @Test fun `the Tier 1 banner is legible on every background`() {
        // No state gate at all: the clock-adjustment notice fires mid-race, so all four are live.
        assertLegible(
            "Tier 1 banner", TIER1_TEXT_ARGB, TIER1_SCRIM_ARGB,
            backgroundsWhen { true },
        )
    }

    @Test fun `the degraded re-sync prompt is legible on every background it can appear on`() {
        // The criterion #123 exists for. Un-scrimmed this failed on amber at 2.93 : 1.
        assertLegible(
            "Tier 3 re-sync prompt", TIER3_TEXT_ARGB, TIER3_SCRIM_ARGB,
            backgroundsWhen { it == TimerState.RUNNING },
        )
    }

    @Test fun `the discard warning is legible on the pre-start screen it is confined to`() {
        // Deliberately un-scrimmed: rule 1 of docs/message-surface.md permits bare text where every
        // reachable background has been checked, and this one reaches only navy at 10.46 : 1.
        assertLegible(
            "Tier 3 discard warning", TIER3_TEXT_ARGB, null,
            backgroundsWhen { it == TimerState.IDLE },
        )
    }

    // --- Negative controls: the defects this file was written to catch --------------------------

    @Test fun `the un-scrimmed prompt that shipped before failed on the amber background`() {
        val ratio = effectiveContrast(TIER3_TEXT_ARGB, null, BG_ONE_MINUTE_ARGB)
        assertEquals(2.93, ratio, 0.01)
        assertFalse("amber-on-amber must not pass, or this suite proves nothing", ratio >= WCAG_NORMAL_TEXT_MIN)
    }

    @Test fun `the 80 percent scrim replaced by 102 was the worst case it was said to be`() {
        // docs/message-surface.md still proposed this alpha as Tier 3's remedy. It passes, which is
        // why it survived unchallenged — but it gives back 1.5 : 1 on amber for nothing.
        val eighty = 0xCC3A2A00L
        assertEquals(6.55, effectiveContrast(TIER1_TEXT_ARGB, eighty, BG_ONE_MINUTE_ARGB), 0.01)
        assertTrue(
            "the opaque scrim must beat the 80 % one it replaced",
            effectiveContrast(TIER1_TEXT_ARGB, TIER1_SCRIM_ARGB, BG_ONE_MINUTE_ARGB) >
                effectiveContrast(TIER1_TEXT_ARGB, eighty, BG_ONE_MINUTE_ARGB),
        )
    }

    // --- The figures docs/message-surface.md quotes ---------------------------------------------

    @Test fun `the contrast figures quoted in the message-surface doc are the computed ones`() {
        // The doc said 8.6 : 1 for Tier 1 until #123; that is this repo's Tier 3 colour on the same
        // scrim, so the published figure had been computed with the wrong foreground. Both are
        // asserted here so the next edit to either colour fails rather than quietly ageing the doc.
        assertEquals(8.03, effectiveContrast(TIER1_TEXT_ARGB, TIER1_SCRIM_ARGB, BG_NORMAL_ARGB), 0.01)
        assertEquals(8.53, effectiveContrast(TIER3_TEXT_ARGB, TIER3_SCRIM_ARGB, BG_NORMAL_ARGB), 0.01)
        assertEquals(10.46, effectiveContrast(TIER3_TEXT_ARGB, null, BG_NORMAL_ARGB), 0.01)
    }
}
