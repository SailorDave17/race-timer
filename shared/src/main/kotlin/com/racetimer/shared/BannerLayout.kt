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
 * `0.57`; this leaves a margin rather than sitting on the boundary.
 *
 * *This sentence ended "and it still holds the longest shipped notice in two lines" until #231.*
 * True when #13 wrote it and false from #96, whose 49-character notice renders on **three** —
 * which is the whole of what #231 found. [MessageSurface.STATUS_LINE] is where that is now
 * computed rather than asserted in a comment.
 */
const val STATUS_LINE_MAX_WIDTH_FRACTION = 0.55f

/**
 * The tallest a **two-line** Tier 3 status line may grow, as a fraction of screen height.
 *
 * Two lines of `caption2` plus its scrim padding came to `0.227` when measured, so this is that
 * with a little grace. As with the banner, it is the height the geometry is *proved* against rather
 * than a clamp on the text: clipping a warning is worse than crowding the readout.
 *
 * **Two lines is no longer the tallest this surface goes, and this figure has not been re-measured
 * (#231).** #96 ships a notice that draws three — see [MessageSurface.STATUS_LINE] — so the check
 * in `BannerLayoutTest` proves the circle against a smaller plate than the one on the watch.
 *
 * It is left as the measurement it is rather than scaled into a three-line estimate, and the reason
 * is worth stating: this plate is drawn inside a vertically **centred** `Column`, so a third line
 * does not simply extend it downwards — it lifts the top edge onto a narrower chord, by an amount
 * nothing here can compute. Scaling this by 3/2 would produce a number that looks derived and is
 * not, which is the exact defect #231 was filed about. The evidence that the three-line plate
 * clears the bezel is #96's check on an SM-R925U; the arithmetic to replace that check needs a
 * measurement this issue did not take.
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

// --- How much copy a surface holds (#231) -----------------------------------
//
// `NOTICE_MAX_CHARS` is one ceiling over every string this app shows a sailor, and
// `docs/message-surface.md` derives it from **Tier 1's** geometry: 11 sp inside the 0.85 width cap
// is about 34 characters a line, and two lines is all the gap above the Start button holds.
//
// Every clause of that is about the transient banner, and the constant governs two other surfaces.
// The Tier 3 status line is capped at [STATUS_LINE_MAX_WIDTH_FRACTION] — 0.55, under two thirds of
// the banner's — because it sits far higher up the circle. So the same sentence buys a different
// number of lines on each surface, and #96's 49-character notice, comfortably inside the 60,
// *measured on an SM-R925U*, rendered on **three** lines rather than two.
//
// Nothing was broken by it. Three lines clears the bezel there and covers no readout, and it was
// checked before shipping. But 60 was a ceiling that happened not to bite, described as a fit that
// had been measured — the shape `docs/message-surface.md` has already recorded twice about itself,
// most recently the 8.6 : 1 contrast figure that was right about the wrong foreground. The remedy
// is the one #123 used: move the arithmetic somewhere a test can drive it.
//
// **What this models, and what it does not.** An average character advance, not a text-measurement
// engine — it cannot know that "—" is a full em wide while "i" is a fifth of one, and it wraps
// greedily on spaces the way Compose does for text this short. Two things pin it to reality rather
// than to itself, and both are assertions in `BannerLayoutTest`: it is calibrated to reproduce the
// one figure the doc already publishes (34 characters at 11 sp inside 0.85), and it is checked
// against the one render anyone has actually measured (49 characters on three lines at Tier 3).
// A change that breaks either is a change to a claim, not to a constant.

/**
 * The screen these character budgets are quoted at, in dp — 450 px at 2× on an SM-R925U.
 *
 * The fractions above need no reference screen: a circle and a plate scale together, so they hold
 * on any round watch. A *character* count does not, because type is specified in sp and does not
 * shrink with the display — a smaller watch holds fewer characters on the same fraction of its
 * width. This is the size the budgets are computed for, and the reason they are a house standard
 * rather than a universal one.
 */
const val REFERENCE_SCREEN_DP = 225f

/**
 * Average character advance as a fraction of type size, for the default sans family.
 *
 * Not looked up — **calibrated**, so that [MessageSurface.BANNER] comes out at the 34 characters a
 * line `docs/message-surface.md` publishes for the Tier 1 banner. That figure is the only one this
 * repo had already committed to, so anchoring on it means the model cannot quietly disagree with
 * the doc it exists to make checkable.
 *
 * The cross-check is independent of the calibration and is what makes it more than circular:
 * applied to Tier 3's narrower cap it predicts #96's notice on three lines, which is what the
 * watch drew. `memory/projects/race-timer-transient-message-surface` carries a second, looser
 * estimate — "at 11 sp a forty-character notice needs ~140 dp" — that works out at `0.32` here and
 * is **falsified** by that same render: it would put the notice on two lines.
 */
const val AVERAGE_CHAR_WIDTH_EM = 0.51f

/** Type size of the Tier 1 banner, in sp. Applied by `TimerScreen`, so this is the live value. */
const val BANNER_TEXT_SP = 11f

/**
 * Type size of the Tier 2 blocking panel, in sp.
 *
 * `caption1`, which this app's theme sets to 12 rather than the Wear default of 14. `TimerScreen`
 * now passes this explicitly instead of leaning on the theme — otherwise the number here would be
 * a *copy* of a value two files away, which is the failure mode #231 is about.
 */
const val BLOCKING_PANEL_TEXT_SP = 12f

/**
 * Type size of the Tier 3 status line, in sp.
 *
 * `caption2`, which the theme does **not** override, so until #231 this was a Wear Compose library
 * default that a version bump could have moved with nothing here to notice. Passed explicitly by
 * `TimerScreen` for that reason.
 */
const val STATUS_LINE_TEXT_SP = 12f

/**
 * How much of the screen's width the Tier 2 panel and its remedy button take.
 *
 * Wider than the Start button's 0.68 because this surface carries up to three lines of copy rather
 * than one word, and narrower than full width so the round bezel never clips a corner of the
 * scrim — the mistake #102 made with the Tier 1 banner and had to move the whole surface to fix.
 *
 * Lived in `TimerScreen` as a private constant until #231. It moved here for the same reason the
 * other two fractions are here: it is an input to a budget the JVM suite has to be able to compute.
 */
const val BLOCKING_PANEL_WIDTH_FRACTION = 0.86f

/**
 * The three places a message can be drawn, and how much copy each one holds.
 *
 * The tiers of `docs/message-surface.md`, expressed as the only three things that decide how a
 * string wraps: how wide the surface is, how big its type is, and how many lines it may take. A
 * fourth surface cannot be added without answering all three, which is the point.
 *
 * @property widthFraction how much of the screen's width the text may occupy
 * @property textSizeSp the type size the surface renders at
 * @property maxLines the most lines the surface may take before it fouls something
 */
enum class MessageSurface(
    val widthFraction: Float,
    val textSizeSp: Float,
    val maxLines: Int,
) {
    /**
     * Tier 1 — the transient banner, below the readout and above Start.
     *
     * Two lines is the gap's own budget: [BANNER_HEIGHT_BUDGET_FRACTION] is set at roughly two
     * lines of 11 sp with a third line's grace, and a third line would start crowding the button.
     */
    BANNER(BANNER_MAX_WIDTH_FRACTION, BANNER_TEXT_SP, maxLines = 2),

    /**
     * Tier 2 — the blocking panel that takes Start's place.
     *
     * Three lines because `TimerScreen` sets `maxLines = 3` on it, and that is a **clip**, not a
     * wrap: a fourth line is not crowded, it is invisible. Of the three surfaces this is the one
     * where overflowing is silently destructive, and nothing checked it before #231.
     */
    BLOCKING_PANEL(BLOCKING_PANEL_WIDTH_FRACTION, BLOCKING_PANEL_TEXT_SP, maxLines = 3),

    /**
     * Tier 3 — the persistent status line under the sequence name.
     *
     * Three lines, and this is the number #231 exists to write down. Not derived: it is what #96
     * shipped and checked on an SM-R925U, where the three-line plate cleared the bezel and covered
     * no readout. `statusLineHeightFraction(3)` is the geometric half of that, and it is the
     * weaker half — see its note on the centred column.
     */
    STATUS_LINE(STATUS_LINE_MAX_WIDTH_FRACTION, STATUS_LINE_TEXT_SP, maxLines = 3),

    ;

    /** How many characters of average width fit on one line of this surface. */
    val charsPerLine: Int
        get() = ((widthFraction * REFERENCE_SCREEN_DP) / (AVERAGE_CHAR_WIDTH_EM * textSizeSp)).toInt()

    /** How many lines [text] wraps to here. */
    fun linesFor(text: String): Int = linesNeededFor(text, charsPerLine)

    /** True when [text] fits this surface without spilling past [maxLines]. */
    fun holds(text: String): Boolean = linesFor(text) <= maxLines
}

/**
 * How many lines [text] takes when wrapped greedily at [charsPerLine] characters.
 *
 * Greedy on spaces, which is what Compose does for single-paragraph LTR text: fill a line until
 * the next word will not fit, then break. A word longer than the line gets broken across as many
 * lines as it needs rather than overflowing, which is also what Compose does and is why the return
 * value can exceed the word count.
 *
 * Empty text is one line, not zero — a blank notice still occupies its surface. That case cannot
 * arise from any constant this app ships and is defined so the function has no undefined input.
 */
fun linesNeededFor(text: String, charsPerLine: Int): Int {
    require(charsPerLine > 0) { "a surface that holds no characters cannot render anything" }
    var lines = 1
    var used = 0
    for (word in text.split(' ').filter { it.isNotEmpty() }) {
        val needed = if (used == 0) word.length else used + 1 + word.length
        when {
            // Fits on the line being filled.
            needed <= charsPerLine -> used = needed
            // Does not fit, and is short enough to start a fresh line.
            word.length <= charsPerLine -> {
                lines++
                used = word.length
            }
            // Longer than a whole line: break it across as many as it needs.
            else -> {
                if (used > 0) lines++
                val full = (word.length - 1) / charsPerLine
                lines += full
                used = word.length - full * charsPerLine
            }
        }
    }
    return lines
}
