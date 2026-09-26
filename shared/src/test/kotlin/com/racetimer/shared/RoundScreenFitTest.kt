package com.racetimer.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Tests for fitting the pre-start controls, and the sequence name, to a round screen (#311).
 *
 * The inputs are measurements, not the model's own numbers. *Measured on the Wear emulator* at the
 * SM-R925U's 450 px and 340 dpi (#302's `wm density`), and at a Galaxy Watch 9 40 mm's published
 * 438 px at the same density: the Start / Lead-in row is 111 px tall with its centre 114.5 px below
 * the screen's at the default font size and 118.5 px below at 1.24, the largest Wear OS 5 offers.
 * The column above it is specified in dp and sp, so it puts the row the same distance down on both
 * screens, and the smaller circle is the whole difference. The Galaxy Watch 9's density is not
 * published. 340 is the SM-R925U's, from a panel of the same pixel density.
 *
 * The pill outline is judged by sampling it, independently of the closed form
 * [pillHalfWidthInCircle] uses, so the fit is checked by something it was not computed with.
 */
class RoundScreenFitTest {

    private val smR925u = 450f
    private val galaxyWatch9 = 438f

    /** The column's 8 dp padding at 340 dpi, each side. */
    private val padding = 17f

    private val rowHeight = 111f
    private val rowCentreAtDefaultFont = 114.5f
    private val rowCentreAtLargestFont = 118.5f

    /** What the row was before #311: `fillMaxWidth(0.92f)` of the padded column. */
    private fun todaysRowWidth(screen: Float) = 0.92f * (screen - 2 * padding)

    /** How far the furthest point of a pill's outline is from the screen's centre. */
    private fun outlineReach(width: Float, centreOffset: Float, height: Float): Float {
        val cap = height / 2f
        val capCentre = width / 2f - cap
        var furthest = 0f
        for (step in 0 until 720) {
            val angle = step * 2 * PI / 720
            // Each end is a semicircle; the straight edges finish on them, so the ends are the outline
            // that can reach furthest, and the straight edges are sampled too for completeness.
            val x = capCentre + cap * cos(angle).toFloat()
            val y = centreOffset + cap * sin(angle).toFloat()
            furthest = maxOf(furthest, hypot(x, y), hypot(-x, y))
        }
        for (step in 0..100) {
            val x = -capCentre + 2 * capCentre * step / 100f
            furthest = maxOf(furthest, hypot(x, centreOffset - cap), hypot(x, centreOffset + cap))
        }
        return furthest
    }

    // --- The defect ------------------------------------------------------------------------------

    @Test fun `today's row runs off the tester's watch`() {
        // The negative control, and #311 in one assertion. At 438 px the 0.92 row reaches past a
        // 219 px circle at both font sizes: the pills' outer corners are under the bezel.
        for (centre in listOf(rowCentreAtDefaultFont, rowCentreAtLargestFont)) {
            val reach = outlineReach(todaysRowWidth(galaxyWatch9), centre, rowHeight)
            assertTrue("reach $reach at +$centre", reach > galaxyWatch9 / 2f)
        }
    }

    @Test fun `today's row runs off the SM-R925U too`() {
        // The part #311 did not expect. The emulator at this watch's metrics drew the bezel over both
        // pills' outer corners, 277 fill pixels against the mask, so the row never fitted the watch
        // it was laid out on either.
        val reach = outlineReach(todaysRowWidth(smR925u), rowCentreAtDefaultFont, rowHeight)
        assertTrue("reach $reach", reach > smR925u / 2f)
    }

    // --- The fix ---------------------------------------------------------------------------------

    @Test fun `the fitted row sits inside the circle, gap and all, on both watches at both font sizes`() {
        var checked = 0
        for (screen in listOf(smR925u, galaxyWatch9)) {
            for (centre in listOf(rowCentreAtDefaultFont, rowCentreAtLargestFont)) {
                val width = roundScreenPillWidth(screen, centre, rowHeight, todaysRowWidth(screen))
                val allowed = screen / 2f - ROUND_SCREEN_EDGE_GAP_FRACTION * screen
                val reach = outlineReach(width, centre, rowHeight)
                assertTrue("$screen px at +$centre: reach $reach against $allowed", reach <= allowed + 0.01f)
                checked++
            }
        }
        assertEquals("the loops above ran over nothing", 4, checked)
    }

    @Test fun `the fitted row is narrower than today's on both watches, so the fit is doing the work`() {
        // Without this, the fit above could pass on a function that returned its cap untouched,
        // which the two negative controls say does not fit.
        for (screen in listOf(smR925u, galaxyWatch9)) {
            val cap = todaysRowWidth(screen)
            assertTrue(roundScreenPillWidth(screen, rowCentreAtDefaultFont, rowHeight, cap) < cap)
        }
    }

    @Test fun `the model predicts the width the emulator drew`() {
        // The cross-check. The row's position and height were read off the emulator; its width was
        // not used to build anything. The emulator drew the fitted row 58 to 392 px at 450 px, and
        // 60 to 378 px at 438 px. The layout truncates to a whole pixel, as here.
        assertEquals(335, roundScreenPillWidth(smR925u, rowCentreAtDefaultFont, rowHeight, todaysRowWidth(smR925u)).toInt())
        assertEquals(319, roundScreenPillWidth(galaxyWatch9, rowCentreAtDefaultFont, rowHeight, todaysRowWidth(galaxyWatch9)).toInt())
    }

    @Test fun `the cap still decides where the circle has room`() {
        // The single Start button: 0.68 of the column, measured clear of the bezel at 0.89 to 0.92
        // of the radius, and drawn identically before and after #311. The fit must leave it alone.
        val startHeight = 119f
        for (screen in listOf(smR925u, galaxyWatch9)) {
            val cap = 0.68f * (screen - 2 * padding)
            assertEquals(cap, roundScreenPillWidth(screen, rowCentreAtLargestFont, startHeight, cap), 0f)
        }
    }

    @Test fun `a pill that cannot fit at all keeps its cap rather than vanishing`() {
        // Its centre line at the very edge of the screen: not even a circle of its height fits there.
        // A Start button that runs off the bezel is still a Start button; one drawn zero wide is not.
        assertEquals(300f, roundScreenPillWidth(450f, 224f, 111f, 300f), 0f)
    }

    // --- The geometry itself ---------------------------------------------------------------------

    @Test fun `a pill on the centre line may span the whole diameter`() {
        // Its ends then touch the circle at the two points furthest apart. A rule that refused this
        // would be measuring something other than the circle.
        assertEquals(100f, pillHalfWidthInCircle(100f, 0f, 40f), 0.001f)
    }

    @Test fun `a pill as far out as it can go is only its own end`() {
        // Centre line 80 out on a radius of 100 with ends of radius 20: the two end circles meet.
        assertEquals(20f, pillHalfWidthInCircle(100f, 80f, 40f), 0.001f)
        assertEquals(0f, pillHalfWidthInCircle(100f, 81f, 40f), 0f)
    }

    @Test fun `above and below the centre are the same`() {
        assertEquals(pillHalfWidthInCircle(100f, 50f, 30f), pillHalfWidthInCircle(100f, -50f, 30f), 0f)
    }

    // --- The sequence name's box (#311) ---------------------------------------------------------

    @Test fun `a box is limited by its edge further from the centre`() {
        // The same line of text, once above the centre and once straddling it. Only the far edge
        // matters, which is what bannerFitsRoundScreen found the hard way.
        val high = roundScreenBoxWidth(450f, topOffset = -170f, bottomOffset = -140f, widthCap = 450f)
        val straddling = roundScreenBoxWidth(450f, topOffset = -15f, bottomOffset = 15f, widthCap = 450f)
        assertTrue(high < straddling)
        assertEquals(2 * kotlin.math.sqrt(225f * 225f - 170f * 170f), high, 0.01f)
    }

    @Test fun `bare text keeps no gap from the edge, unlike a pill`() {
        // A box whose far corners land exactly on the circle is allowed. The pill's gap would have
        // cut the sequence name short on the SM-R925U at the default font size, where it has
        // always fitted.
        val width = roundScreenBoxWidth(450f, topOffset = -135f, bottomOffset = -105f, widthCap = 450f)
        assertEquals(360f, width, 0.01f)
    }

    @Test fun `a box wholly outside the circle keeps its cap`() {
        assertEquals(200f, roundScreenBoxWidth(450f, topOffset = -230f, bottomOffset = -200f, widthCap = 200f), 0f)
    }

    // --- Text stepped down to fit (#311) --------------------------------------------------------

    /** A measurer whose type is 5 units wide per unit of size, plus rounding up to whole units. */
    private val roughlyLinear: (Float) -> Float = { size -> kotlin.math.ceil(5f * size) }

    @Test fun `text that fits is left at its own size`() {
        assertEquals(12f, fittedTextSize(12f, available = 60f, widthAt = roughlyLinear), 0f)
        assertEquals("never grows", 12f, fittedTextSize(12f, available = 1000f, widthAt = roughlyLinear), 0f)
    }

    @Test fun `text too wide is stepped down until it fits, and not much further`() {
        // "Start over" at 1.24 on the tester's watch is this case. The result must fit, and be
        // within one step of the largest size that does, or the label is smaller than it had to be.
        val fitted = fittedTextSize(14.9f, available = 60f, widthAt = roughlyLinear)
        assertTrue("fits", roughlyLinear(fitted) <= 60f)
        assertTrue("not a whole step smaller than it needed to be", roughlyLinear(fitted + 0.25f) > 60f)
    }

    @Test fun `a measurer that never agrees cannot hang the screen`() {
        var calls = 0
        val stubborn: (Float) -> Float = { calls++; 1_000f }
        fittedTextSize(12f, available = 10f, widthAt = stubborn)
        assertTrue("called $calls times", calls <= MAX_FIT_STEPS)
    }
}
