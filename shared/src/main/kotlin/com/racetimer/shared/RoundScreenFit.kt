package com.racetimer.shared

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

// ---------------------------------------------------------------------------
// How wide a pill may be at the height it sits on a round watch (#311)
//
// The pre-start controls were sized as a fraction of the column's width — Start at 0.68, the
// Start / Lead-in and Resume / Start over rows at 0.92 — and placed wherever the centred column
// above them left them. Nothing related the two. The column is specified in dp and sp, so it is the
// same height on every watch, and on a round screen a row's outer ends sit on the chord at that
// height, which a width fraction knows nothing about.
//
// *Measured on the Wear emulator*, with the round mask it composites: at the SM-R925U's 450 px and
// 340 dpi the Start / Lead-in row's centre sits 114.5 px below the screen's, and the bezel cuts the
// outer lower corner of both pills. A tester's Galaxy Watch 9 showed the same cut (#311), and at its
// published 438 px the same row sits the same 53.9 dp down on a smaller circle. So this was never a
// fault of one watch. The row did not fit the watch it was laid out on either: computed from that
// measured position and its 0.92 width, its ends reach about 8 px past the SM-R925U's 225 px circle.
//
// A pill's width is therefore taken from where it actually sits. [pillHalfWidthInCircle] is the
// widest a stadium can be at a given distance from the centre, and `wear/ui/RoundScreenColumn` asks
// it once the column has placed the row. It is arithmetic about a circle with nothing Android in
// it, so it lives here beside [bannerFitsRoundScreen], for the same reason.
// ---------------------------------------------------------------------------

/**
 * The gap a fitted pill keeps from the edge of a round display, as a fraction of its diameter.
 *
 * About 4 dp on the 206–226 dp screens Samsung ships. A fraction, not a dp figure, so it is the
 * same proportion of the bezel on every watch rather than a margin tuned to one of them. Without a
 * gap the fitted pill touches the edge at one point, which reads as cut on a bezel with any
 * anti-aliasing at all.
 */
const val ROUND_SCREEN_EDGE_GAP_FRACTION = 0.02f

/**
 * Half the widest a pill can be and still lie wholly inside a circle.
 *
 * A pill here is a stadium, a rectangle with semicircular ends, which is what a Wear `Button` draws
 * at a width greater than its height. Only its ends can leave the circle: each end is a circle of
 * radius `pillHeight / 2` centred on the pill's centre line, and the pill's straight top and bottom
 * edges finish on those end circles. So the pill fits exactly when both end circles do, which is
 * when the distance from the screen's centre to an end circle's centre, plus that radius, is at most
 * [circleRadius].
 *
 * All lengths in one unit.
 *
 * @param circleRadius the radius the pill must stay inside, any edge gap already taken off
 * @param centreOffset how far the pill's centre line is above or below the circle's centre; the
 *   sign does not matter
 * @param pillHeight the pill's height, which is also the diameter of its ends
 * @return half the pill's greatest width, or 0 when not even a circle of [pillHeight] fits at that
 *   offset
 */
fun pillHalfWidthInCircle(circleRadius: Float, centreOffset: Float, pillHeight: Float): Float {
    val capRadius = pillHeight / 2f
    // How far from the screen's centre an end circle's centre may be.
    val reach = circleRadius - capRadius
    val offset = abs(centreOffset)
    if (reach <= 0f || offset > reach) return 0f
    return capRadius + sqrt(reach * reach - offset * offset)
}

/**
 * How wide to draw a pill on a round screen: [widthCap], unless the circle is narrower than that at
 * the height the pill sits, keeping [ROUND_SCREEN_EDGE_GAP_FRACTION] clear of the edge.
 *
 * Only the width moves. The height is the caller's, so a pill cannot be shrunk below the touch
 * target to make it fit (#311's fifth criterion); a pill that does not fit is made narrower.
 *
 * All lengths in one unit, px on the watch.
 *
 * @param screenDiameter the round display's diameter
 * @param centreOffset how far the pill's centre line sits from the display's centre
 * @param pillHeight the pill's height
 * @param widthCap the width the pill takes wherever the circle leaves it room
 * @return the width to draw. [widthCap] when not even a circle of [pillHeight] fits at that height:
 *   a Start button that runs off the bezel is still a Start button, and one drawn zero wide is not.
 */
fun roundScreenPillWidth(
    screenDiameter: Float,
    centreOffset: Float,
    pillHeight: Float,
    widthCap: Float,
): Float {
    val gap = ROUND_SCREEN_EDGE_GAP_FRACTION * screenDiameter
    val halfWidth = pillHalfWidthInCircle(screenDiameter / 2f - gap, centreOffset, pillHeight)
    if (halfWidth <= 0f) return widthCap
    return min(widthCap, 2f * halfWidth)
}

/**
 * The widest a square-cornered box may be on a round screen: [widthCap], unless the circle is
 * narrower than that at whichever of the box's edges is further from the centre.
 *
 * For the sequence name, a line of text with no plate (#311). *Measured on the Wear emulator*, on
 * the build before this one: at a 438 px screen and 340 dpi it reaches 0.998 of the radius at the
 * default font size, and at 1.24 it runs under the bezel there and at 450 px as well. A box's
 * corners are what leave a circle, and only the edge further from the centre decides that, as
 * [bannerFitsRoundScreen] has it.
 *
 * **No edge gap, unlike [roundScreenPillWidth].** A pill is a filled shape, and one touching the
 * bezel reads as cut. Bare text has no plate for the bezel to cut. Its line box already runs above
 * its glyphs, so the box's corner touching the circle leaves the letters clear. With the gap as
 * well, the name was cut short on the SM-R925U at the default font size, where it had always fitted.
 *
 * All lengths in one unit, px on the watch.
 *
 * @param topOffset where the box's top edge is, relative to the display's centre; either sign
 * @param bottomOffset where its bottom edge is, likewise
 * @return the width to allow. [widthCap] when the far edge is outside the circle altogether, for the
 *   reason [roundScreenPillWidth] gives.
 */
fun roundScreenBoxWidth(
    screenDiameter: Float,
    topOffset: Float,
    bottomOffset: Float,
    widthCap: Float,
): Float {
    val radius = screenDiameter / 2f
    val far = max(abs(topOffset), abs(bottomOffset))
    if (far >= radius) return widthCap
    return min(widthCap, 2f * sqrt(radius * radius - far * far))
}

// --- Text that fits the width it is given (#311) --------------------------------------------
//
// Narrowing a pill to the circle narrows the room for its label. On the Wear emulator at a 438 px
// screen and the largest font size Wear OS 5 offers (1.24), "Start over" was cut to "Start", which
// is the name of the control beside it. The owner chose to step the type down until the word fits,
// rather than wrap it or let the pill run under the bezel again, and chose the same for the sequence
// name over an ellipsis, which cut the part of the name that tells two sequences apart. The loop
// lives here so it can be tested without a font. The watch passes a real text measurer.

/**
 * The largest type size, no larger than [size], at which a one-line label fits [available].
 *
 * Never grows the text: a label that fits is left at the size it was given. Past that it jumps
 * straight to the size proportion suggests, because type width is close to linear in size, and then
 * steps down a quarter of a unit at a time while the measurer still says too wide. That covers the
 * part that is not linear: glyph advances round to whole pixels, and Android 14 scales large type
 * less than small.
 *
 * @param size the label's own type size, in any unit [widthAt] takes (sp on the watch)
 * @param available the width the label must fit in, in the unit [widthAt] returns
 * @param widthAt how wide the label is at a given type size
 * @return the size to draw at. After [MAX_FIT_STEPS] tries it returns the smallest size it reached,
 *   so a measurer that never agrees cannot hang the screen.
 */
fun fittedTextSize(size: Float, available: Float, widthAt: (Float) -> Float): Float {
    var candidate = size
    repeat(MAX_FIT_STEPS) {
        val width = widthAt(candidate)
        if (width <= available || candidate <= FIT_STEP) return candidate
        candidate = min(candidate - FIT_STEP, candidate * available / width).coerceAtLeast(FIT_STEP)
    }
    return candidate
}

/** How many sizes [fittedTextSize] tries before it gives up and returns the smallest it reached. */
const val MAX_FIT_STEPS = 40

/** How far [fittedTextSize] steps down at a time once proportion has had its go. */
private const val FIT_STEP = 0.25f
