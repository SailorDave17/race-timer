package com.racetimer.shared

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for where the Tier 1 banner may sit on a round watch.
 *
 * The first test is the one that matters, and the two after it are why it is worth anything: the
 * geometry the app shipped with is asserted to *fail*, so this file cannot be read as a rubber stamp
 * on whatever numbers happen to be in `BannerLayout.kt`. Put the old constants back and the suite
 * goes red.
 */
class BannerLayoutTest {

    @Test fun `the shipped banner geometry fits inside a round screen`() {
        assertTrue(bannerFitsRoundScreen())
    }

    @Test fun `the geometry that shipped before did not fit`() {
        // What #102's clipping actually was, in numbers. The banner sat 2 dp from the top of a
        // 225 dp screen and took the full width less 12 dp either side, so its top corners were far
        // outside the circle and the first line of any wrapping message was cut away by the mask.
        assertFalse(
            bannerFitsRoundScreen(
                topFraction = 2f / 225f,
                widthFraction = 201f / 225f,
                heightFraction = 32f / 225f,
            )
        )
    }

    @Test fun `a banner pinned to the very top never fits, however narrow`() {
        // The top edge of a circle is a point. Moving the banner down is the only fix available —
        // narrowing it cannot buy back a row that has no screen on it at all.
        assertFalse(
            bannerFitsRoundScreen(topFraction = 0f, widthFraction = 0.05f, heightFraction = 0.01f)
        )
    }

    @Test fun `the check measures the far edge, not the top one`() {
        // The reason this function takes a height at all. A banner hung just above the equator has
        // its *bottom* corners furthest out, and a check that looked only at the top edge would wave
        // through a band that runs off the bottom of the circle. Same width, same top, and the only
        // difference is how far down it goes.
        assertTrue(
            bannerFitsRoundScreen(topFraction = 0.45f, widthFraction = 0.85f, heightFraction = 0.15f)
        )
        assertFalse(
            bannerFitsRoundScreen(topFraction = 0.45f, widthFraction = 0.85f, heightFraction = 0.45f)
        )
    }

    @Test fun `a banner at the equator may span the full width`() {
        // The widest row of a circle is its middle, and the check has to agree: a rule that refused
        // this would be measuring something other than the screen.
        assertTrue(
            bannerFitsRoundScreen(topFraction = 0.5f, widthFraction = 1f, heightFraction = 0f)
        )
    }

    @Test fun `the banner sits below the middle of the screen`() {
        // Not a geometry fact — a decision, recorded where it can be contradicted. The readout is
        // above the centre and must never be covered (docs/message-surface.md), which is what ruled
        // out every top-centre placement and is the whole reason these numbers are what they are.
        assertTrue(BANNER_TOP_FRACTION > 0.5f - BANNER_HEIGHT_BUDGET_FRACTION)
    }

    @Test fun `the width cap leaves the long launch notices room to wrap`() {
        // "Saved sequence unreadable — using default" is 40 characters at 11 sp; it was always going
        // to wrap. The cap has to be wide enough that it wraps to two readable lines rather than
        // three that would foul the Start button below.
        assertTrue(BANNER_MAX_WIDTH_FRACTION > 0.8f)
    }
}
