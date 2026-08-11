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

// --- Tier 3 status line (#13) -----------------------------------------------
// The same arithmetic, applied to the other scrimmed surface. It sits directly under the sequence
// name — much closer to the top of the circle than the Tier 1 banner, and therefore on a much
// narrower chord — so a notice wide enough to look fine in a square preview has its scrim corners
// cut by the bezel. *Measured on an SM-R925U*: the #13 notification warning drew a scrim from
// x=30 to x=420 at y=40, where the visible chord runs only x=97 to x=353.
//
// This is #102's defect one tier over, and milder: what was clipped is the *plate*, not a line of
// text, because the copy is centred and shorter than its box. Worth fixing anyway — a scrim that
// runs off the edge reads as a rendering fault — and worth fixing here, where the constant can be
// asserted, rather than as a number typed into a modifier.

/**
 * How far down the screen the Tier 3 status line starts, as a fraction of screen height.
 *
 * Directly beneath the sequence-name label. Measured rather than chosen: the label's box ends at
 * 40 px of a 450 px screen.
 */
const val STATUS_LINE_TOP_FRACTION = 0.09f

/**
 * The widest a Tier 3 status line may be, as a fraction of screen width.
 *
 * A cap like [BANNER_MAX_WIDTH_FRACTION], and a much tighter one, because the chord this high up
 * the circle is far shorter than the chord at the banner's height. The geometry permits about
 * `0.57`; this leaves a margin rather than sitting on the boundary, and it still holds the longest
 * shipped notice in two lines.
 */
const val STATUS_LINE_MAX_WIDTH_FRACTION = 0.55f

/**
 * The tallest a Tier 3 status line may grow, as a fraction of screen height.
 *
 * Two lines of `caption2` plus its scrim padding came to `0.227` when measured, so this is that
 * with a little grace. As with the banner, it is the height the geometry is *proved* against rather
 * than a clamp on the text: clipping a warning is worse than crowding the readout.
 */
const val STATUS_LINE_HEIGHT_BUDGET_FRACTION = 0.23f

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
