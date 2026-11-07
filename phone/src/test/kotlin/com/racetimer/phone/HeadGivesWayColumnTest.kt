package com.racetimer.phone

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.racetimer.phone.ui.HEAD_SLOT
import com.racetimer.phone.ui.HeadGivesWayColumn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The timer screen's column (#304), against the `Column` it replaced.
 *
 * The claim that matters is that **without a head it lays out exactly as the old `Column` did**, in
 * both regimes: below capacity, where it centres and spaces evenly, and past it, where the last
 * child takes the overflow. The second regime is the one the phone is in today in landscape on a
 * small phone (cairn: `a-centred-column-past-full-pins-and-squeezes-its-last-child`), so a
 * replacement that only matched the first would move controls the officer already knows.
 *
 * Each case lays the same children out twice, side by side: in a real `Column` and in
 * [HeadGivesWayColumn]. The `Column` is the reference, so nothing here restates where a child
 * should land. Fixed-size boxes keep text metrics out of it. What the screen's own text does is
 * `TimerScreenTimeOfDayTest`'s question.
 */
@RunWith(RobolectricTestRunner::class)
class HeadGivesWayColumnTest {

    @get:Rule
    val compose = createComposeRule()

    /** Heights of the children below the head, and their widths, so centring is exercised too. */
    private val bodyHeights = listOf(30.dp, 50.dp, 90.dp, 40.dp)
    private val bodyWidths = listOf(120.dp, 77.dp, 140.dp, 101.dp)

    /**
     * Each column's width. Two of them side by side must fit Robolectric's 320 dp screen, or the
     * `Row` narrows the second and the comparison measures the `Row` instead of the columns.
     */
    private val columnWidth = 150.dp

    @Composable
    private fun Body(prefix: String) {
        bodyHeights.forEachIndexed { i, h ->
            Box(Modifier.size(bodyWidths[i], h).testTag("$prefix$i"))
        }
    }

    /**
     * Lay the body out in a `Column` and in [HeadGivesWayColumn], both [columnHeight] tall, the
     * second with a head of [headHeight] when that is not null.
     */
    private fun render(columnHeight: Dp, headHeight: Dp?) {
        compose.setContent {
            // #239: without it this class hangs whenever it is not the first Compose class the JVM
            // runs. *Measured* on #304: alone it passed, and in the whole suite every case here died
            // in AppNotIdleException.
            GlobalSnapshotFlushLoop()
            Row {
                Box(Modifier.size(columnWidth, columnHeight)) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.SpaceEvenly,
                    ) { Body("ref") }
                }
                Box(Modifier.size(columnWidth, columnHeight)) {
                    HeadGivesWayColumn(modifier = Modifier.fillMaxSize()) {
                        if (headHeight != null) {
                            Box(Modifier.size(60.dp, headHeight).layoutId(HEAD_SLOT).testTag("head"))
                        }
                        Body("new")
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun bounds(tag: String): Rect = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    /** The new column's child, moved back by the reference column's width so the two compare. */
    private fun shifted(tag: String): Rect {
        val offset = with(compose.density) { columnWidth.toPx() }
        return bounds(tag).translate(-offset, 0f)
    }

    private fun assertBodyMatchesTheColumn() {
        bodyHeights.indices.forEach { i ->
            assertEquals("child $i, against the Column it replaced", bounds("ref$i"), shifted("new$i"))
        }
    }

    private fun headIsPlaced(): Boolean =
        compose.onNodeWithTag("head").fetchSemanticsNode().layoutInfo.isPlaced

    @Test
    fun `with no head and room to spare it is the Column`() {
        // 210 dp of children in 400: centred, evenly spaced, every child its full height.
        render(columnHeight = 400.dp, headHeight = null)
        assertBodyMatchesTheColumn()
    }

    @Test
    fun `with no head and too little room it is the Column, last child squeezed`() {
        // 210 dp of children in 190: the Column pins at the top and the last child loses 20 dp.
        render(columnHeight = 190.dp, headHeight = null)
        assertBodyMatchesTheColumn()
        // The positive control: this really is the over-full regime, or the case proves nothing
        // the one above did not.
        assertEquals(20f, with(compose.density) { bounds("ref3").height.toDp().value }, 0.5f)
    }

    @Test
    fun `a head that fits is kept, and nothing below it is squeezed`() {
        // 210 + 40 in 400.
        render(columnHeight = 400.dp, headHeight = 40.dp)
        assertTrue("a head with room must be placed", headIsPlaced())
        bodyHeights.indices.forEach { i ->
            assertEquals("child $i keeps its height under the head", bounds("ref$i").height, bounds("new$i").height)
        }
        // And it heads the column: above the first child.
        assertTrue(bounds("head").bottom <= bounds("new0").top)
        // The body did move down to make room, so this case is not the no-head case again.
        assertNotEquals(bounds("ref0").top, shifted("new0").top)
    }

    @Test
    fun `a head that does not fit gives way, and the rest is the Column exactly`() {
        // 210 + 40 in 230: the head would leave the last child 20 dp short, so it goes instead.
        render(columnHeight = 230.dp, headHeight = 40.dp)
        assertTrue("a head with no room must not be placed", !headIsPlaced())
        assertBodyMatchesTheColumn()
    }

    @Test
    fun `a head that fits to the dp is kept`() {
        // 210 + 40 in exactly 250: the boundary is inclusive, because a full column with nothing
        // squeezed is a fit.
        render(columnHeight = 250.dp, headHeight = 40.dp)
        assertTrue(headIsPlaced())
        bodyHeights.indices.forEach { i ->
            assertEquals(bounds("ref$i").height, bounds("new$i").height)
        }
    }
}
