package com.racetimer.shared

import org.junit.Assert.assertEquals
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

    // --- Tier 3 status line (#13) ---------------------------------------------------------------

    @Test fun `the status line width that shipped before the cap did not fit`() {
        // The negative control, and the measurement that justified the constant. #13's notification
        // warning drew its scrim across 390 px of a 450 px screen (0.87) starting 40 px down; the
        // visible chord there is 256 px, so the plate's top corners were outside the circle.
        // Without this assertion the fit below passes just as happily against a cap of 0.9.
        //
        // Pinned to #13's own box, (30,40)-(420,142), rather than read from the live geometry. It
        // read `STATUS_LINE_TOP_FRACTION` until #233 re-measured that edge and found it had moved
        // down, and a control that reads a live figure goes red when the figure *improves*.
        assertFalse(
            "an uncapped status line must not fit, or the cap proves nothing",
            bannerFitsRoundScreen(
                topFraction = 40f / 450f,
                widthFraction = 0.87f,
                heightFraction = 102f / 450f,
            )
        )
    }

    @Test fun `the status line is capped harder than the banner because it sits higher`() {
        // The relationship is the point, not the two numbers. A circle is widest at its equator, so
        // a surface nearer the top has less room — and anyone widening the status line to match the
        // banner "for consistency" is undoing a measurement.
        for (screen in StatusLineScreen.values()) {
            assertTrue(screen.name, screen.fullColumnTopFraction < BANNER_TOP_FRACTION)
        }
        assertTrue(STATUS_LINE_MAX_WIDTH_FRACTION < BANNER_MAX_WIDTH_FRACTION)
    }

    // --- Where the plate sits, per line count (#233) --------------------------------------------
    //
    // #231 left no test that the three-line plate fits, because the only one it could write passed
    // for every height: a plate growing downwards from a fixed top moves its far edge towards the
    // equator. It expected the real plate to rise as it grew, since it sits in a centred `Column`,
    // and did not measure by how much. #233 measured it, and the plate does rise, but only until
    // the column fills. Every notice that ships fills it, so from there the plate does grow
    // downwards. What lets the fit below go red is the ceiling, and the control after it shows the
    // circle cutting the plate without one.

    /** One px of the reference screen, which is as fine as a `screencap` measurement reads. */
    private val onePx = 1f / REFERENCE_SCREEN_PX

    @Test fun `the plate grows by one measured line per line`() {
        // Wiring rather than evidence: the line height and the inset are *solved* from these two
        // plates, so this proves the function puts them back together, not that they are right.
        // The next test is the one that checks the model against something it was not built from.
        assertEquals(72f / 450f, statusLineHeightFraction(2), onePx / 2)
        assertEquals(106f / 450f, statusLineHeightFraction(3), onePx / 2)
    }

    @Test fun `the model predicts the button squeeze the watch drew`() {
        // The cross-check, and why the model is more than its inputs read back. The spare room
        // comes from where the label sat, the spacer from the source and the density, and the
        // squeeze from the buttons, which nothing above was measured from. Start went from 119 to
        // 111 px under the two-line notice, and Sync from 136 to 81 px under the three-line one.
        assertEquals(8f / 450f, statusLineOverflowFraction(2, StatusLineScreen.PRE_START), 2 * onePx)
        assertEquals(55f / 450f, statusLineOverflowFraction(3, StatusLineScreen.RUNNING), 2 * onePx)
    }

    @Test fun `a notice that ships fills the column, so its plate is pinned where the watch drew it`() {
        // The two states the watch was measured in, and the finding in one assertion each. Both
        // overflow, which is what pins the top, and the pinned top is the measured edge.
        assertTrue(statusLineOverflowFraction(2, StatusLineScreen.PRE_START) > 0f)
        assertTrue(statusLineOverflowFraction(3, StatusLineScreen.RUNNING) > 0f)
        assertEquals(55f / 450f, statusLineTopFraction(2, StatusLineScreen.PRE_START), onePx)
        assertEquals(51f / 450f, statusLineTopFraction(3, StatusLineScreen.RUNNING), onePx)
    }

    @Test fun `a plate with room to spare sits lower than one that fills the column`() {
        // Without this the top-edge function could be a constant and every test above would still
        // hold. A one-line plate fits the spare room on both screens, so the column is still
        // centred and the plate sits below where a full column pins it.
        for (screen in StatusLineScreen.values()) {
            assertEquals(screen.name, 0f, statusLineOverflowFraction(1, screen), 0f)
            assertTrue(
                screen.name,
                statusLineTopFraction(1, screen) > statusLineTopFraction(MessageSurface.STATUS_LINE.maxLines, screen),
            )
        }
    }

    @Test fun `every line count the status line allows fits the round screen, on both screens`() {
        // The geometry assertion #231 could not write. Driven off the surface's own line budget and
        // the screens enum, so a fourth line or a third screen is proved rather than assumed.
        var checked = 0
        for (screen in StatusLineScreen.values()) {
            for (lines in 1..MessageSurface.STATUS_LINE.maxLines) {
                assertTrue(
                    "${screen.name} at $lines line(s)",
                    bannerFitsRoundScreen(
                        topFraction = statusLineTopFraction(lines, screen),
                        widthFraction = STATUS_LINE_MAX_WIDTH_FRACTION,
                        heightFraction = statusLineHeightFraction(lines),
                    )
                )
                checked++
            }
        }
        assertEquals("the loops above ran over nothing", 6, checked)
    }

    @Test fun `a plate that kept centring past a full column would be cut by the bezel`() {
        // The negative control for the fit above, and it is #231's own premise: that a third line
        // lifts the top edge onto a narrower chord. Pinned to the running measurement, not read
        // from the live model, so it cannot go red because the geometry got better. The three-line
        // plate and its spacer cost 110.25 px against 56 px spare. A column that kept centring
        // would share the 54.25 px overflow above and below and lift the top from 51 px to about
        // 24 px, where the chord is too short for a 248 px plate.
        val width = 248f / 450f
        val height = 106f / 450f
        val centredTop = (51f - (106f + 4.25f - 56f) / 2f) / 450f
        assertFalse(bannerFitsRoundScreen(topFraction = centredTop, widthFraction = width, heightFraction = height))
        // And the same plate at the edge the watch drew fits, so the ceiling is the whole difference.
        assertTrue(bannerFitsRoundScreen(topFraction = 51f / 450f, widthFraction = width, heightFraction = height))
    }

    // --- How much copy each surface holds (#231) ------------------------------------------------

    @Test fun `the character model reproduces the figure the doc already publishes`() {
        // The calibration, and the reason `AVERAGE_CHAR_WIDTH_EM` is 0.51 rather than a number
        // looked up in a font table. docs/message-surface.md has said "roughly 34 characters" for
        // the Tier 1 banner since #102; if this model disagreed with that, one of the two would be
        // wrong and nobody would find out. Anchoring here means the doc's own sentence is now an
        // assertion, so moving either without the other turns the suite red.
        assertEquals(34, MessageSurface.BANNER.charsPerLine)
    }

    @Test fun `the status line holds far less copy per line than the banner`() {
        // The whole of #231 in one comparison. Same screen, same font family, and a surface that
        // holds about three fifths as much — because it is capped a third narrower and set a
        // point larger. Anyone who reads "keep it under 60" and pictures the banner's 34-character
        // lines is picturing the wrong surface.
        assertEquals(20, MessageSurface.STATUS_LINE.charsPerLine)
        assertTrue(MessageSurface.STATUS_LINE.charsPerLine < MessageSurface.BANNER.charsPerLine)
    }

    @Test fun `the notice that exposed the gap takes three lines on the surface it renders on`() {
        // The cross-check, and it is independent of the calibration above: nothing about matching
        // the doc's 34 forces this to come out at three. *Measured on an SM-R925U* (#96), the Do
        // Not Disturb warning renders on three lines — so a model that said two would be a model
        // of nothing, whatever else it reproduced.
        assertEquals(3, MessageSurface.STATUS_LINE.linesFor(NOTICE_CUE_VOLUME_REFUSED))
        assertTrue(MessageSurface.STATUS_LINE.holds(NOTICE_CUE_VOLUME_REFUSED))
    }

    @Test fun `the same notice would have taken two lines on the banner`() {
        // The negative control for the test above: it pins the *cause* to the width cap rather than
        // to the string being long. Identical text, identical model, one surface — and the reason
        // the 60-character ceiling read as a two-line fit for four months.
        assertEquals(2, MessageSurface.BANNER.linesFor(NOTICE_CUE_VOLUME_REFUSED))
    }

    @Test fun `every surface has a budget, including any added later`() {
        // Driven off the enum rather than a list written by hand, so a fourth surface added without
        // a width, a type size or a line budget fails here instead of silently inheriting the
        // shared character ceiling — which is the defect this issue is, one surface at a time.
        for (surface in MessageSurface.values()) {
            assertTrue("${surface.name} holds no characters", surface.charsPerLine > 0)
            assertTrue("${surface.name} allows no lines", surface.maxLines > 0)
        }
        assertTrue("the enum is empty, so the loop above proves nothing", MessageSurface.values().isNotEmpty())
    }

    // --- The wrap model itself ------------------------------------------------------------------

    @Test fun `text that fits stays on one line`() {
        assertEquals(1, linesNeededFor("short", 20))
        assertEquals(1, linesNeededFor("exactly twenty chars", 20))
    }

    @Test fun `a word that will not fit starts the next line rather than overflowing`() {
        // Greedy wrap on spaces, which is what Compose does for a single paragraph of LTR text.
        assertEquals(2, linesNeededFor("exactly twenty chars!", 20))
    }

    @Test fun `a word longer than the line is broken across as many as it needs`() {
        // Compose breaks inside a word rather than letting it run off the plate, so the model has
        // to as well — otherwise a single long token would report one line and clear every budget.
        assertEquals(3, linesNeededFor("a".repeat(25), 10))
        assertEquals(4, linesNeededFor("word " + "b".repeat(25), 10))
    }

    @Test fun `empty text still occupies its surface`() {
        assertEquals(1, linesNeededFor("", 20))
    }

    @Test fun `the wrap model is sensitive to the width it is given`() {
        // Without this, every assertion above would hold against a model that returned a constant.
        val notice = NOTICE_CUE_VOLUME_REFUSED
        assertEquals(1, linesNeededFor(notice, 60))
        assertEquals(2, linesNeededFor(notice, 30))
        assertEquals(3, linesNeededFor(notice, 20))
    }
}
