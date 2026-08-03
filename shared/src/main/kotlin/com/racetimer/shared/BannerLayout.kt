package com.racetimer.shared

import kotlin.math.abs
import kotlin.math.max

// ---------------------------------------------------------------------------
// Where the Tier 1 banner can sit on a round watch (pure, testable)
//
// #102 was filed as "the banner never renders", and rendering was never the fault — see the dwell
// note on `MainActivity.showTransientMessage` for the defect that actually hid it. What this file
// answers is the question that only became askable once the banner stayed up long enough to look
// at: *where does it go?*
//
// A round display has almost no usable width at the very top, which is exactly where the banner was
// pinned — 2 dp down, full width. At that height a 450 px circle has a visible chord of about 84 px,
// so a message long enough to wrap lost its first line to the bezel mask outright. Moving it far
// enough down to clear the mask then put it over the countdown, which `docs/message-surface.md`
// forbids in its first rule. Neither is a tuning problem: no top-centre geometry fits a
// forty-character notice inside the circle *and* above the readout.
//
// So the banner sits **below the readout**, in the gap above the Start button. That is the widest
// part of the circle, so the same message needs two lines instead of three and still clears the
// mask with room to spare, and it cannot cover the readout by construction rather than by a careful
// choice of numbers — which matters most for the one Tier 1 notice that fires mid-race, where the
// countdown under it is the whole product.
//
// The rule lives here rather than in `wear/` for the same reason `ScreenPolicy` and `RestorePlan`
// do: it is arithmetic about a circle, it has nothing Android in it, and a constant nobody can
// assert is a constant that drifts back. The test that guards it is the defect written down.
// ---------------------------------------------------------------------------

/**
 * How far down the screen the transient banner starts, as a fraction of screen height.
 *
 * Below the readout, above the Start button. Measured on the watch, the countdown ends at about
 * `0.42` of screen height and the button begins at about `0.63`, so this hands the banner the gap
 * between them.
 */
const val BANNER_TOP_FRACTION = 0.44f

/**
 * The widest the banner may be, as a fraction of screen width.
 *
 * A cap, not a width: a short notice still gets a band snug around its own text, so this only bites
 * on the long ones — which are the only ones that ever wrapped.
 */
const val BANNER_MAX_WIDTH_FRACTION = 0.85f

/**
 * The tallest the banner is allowed to grow before it would foul the Start button, as a fraction of
 * screen height.
 *
 * Not enforced on the text — clipping a warning is worse than crowding a button — but it is the
 * height [bannerFitsRoundScreen] proves the geometry against, so the check answers "does the banner
 * fit at its worst" rather than "does today's shortest message fit". Two lines of 11 sp come to
 * roughly `0.15`; this leaves most of a third line's grace above the button.
 */
const val BANNER_HEIGHT_BUDGET_FRACTION = 0.19f

/**
 * True when a banner of [widthFraction] × [heightFraction], starting [topFraction] down, fits
 * inside a round screen.
 *
 * All three are fractions of the screen's own size, so this holds for any round watch rather than
 * for the one it was measured on: the circle and the banner scale together.
 *
 * Taking the screen as a unit square, the display is a circle of radius `0.5` about its centre. The
 * banner's four corners sit `widthFraction / 2` horizontally from that centre, and its top and
 * bottom edges some distance above or below it. Only the edge **further** from the centre can fail,
 * because a circle is widest at its equator — so the check runs against whichever of the two that
 * is, and does not care which side of the equator the banner ended up on. That generality is the
 * point rather than showing off: the banner has already moved once, from the top of the screen to
 * the middle of it, and the first version of this check silently assumed it never would.
 */
fun bannerFitsRoundScreen(
    topFraction: Float = BANNER_TOP_FRACTION,
    widthFraction: Float = BANNER_MAX_WIDTH_FRACTION,
    heightFraction: Float = BANNER_HEIGHT_BUDGET_FRACTION,
): Boolean {
    val halfWidth = widthFraction / 2f
    val topFromCentre = abs(0.5f - topFraction)
    val bottomFromCentre = abs(0.5f - (topFraction + heightFraction))
    val worstFromCentre = max(topFromCentre, bottomFromCentre)
    return halfWidth * halfWidth + worstFromCentre * worstFromCentre <= 0.25f
}
