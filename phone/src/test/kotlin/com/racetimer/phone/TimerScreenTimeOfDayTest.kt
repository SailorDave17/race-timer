package com.racetimer.phone

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.racetimer.phone.ui.PhoneReadout
import com.racetimer.phone.ui.TAG_TIME_OF_DAY
import com.racetimer.phone.ui.TimerScreen
import com.racetimer.shared.TimerState
import com.racetimer.shared.pairGunsApartLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The time of day on the phone's timer screen (#304): shown in every state, heading the column, and
 * giving way whenever the rest of the column leaves no room for it.
 *
 * **Native graphics, deliberately.** Every other phone screen test runs on Robolectric's legacy text
 * metrics, which do not measure text: *measured* on #304, a 19-character line came back 19 px wide,
 * one pixel a character, and every line about 35 px tall whatever its size. A question about what
 * fits on a screen cannot be asked of those numbers. Robolectric's native graphics lays text out
 * with real fonts.
 *
 * **The floor is Android's smallest phone, 320 × 470 dp** (owner decision on #304), in both
 * orientations, with the notice line and the count-up prompt up: the AC's worst case. In landscape
 * that column is already past full **before the clock exists**: End Race is squeezed to 10 dp and
 * its label is not laid out. That squeeze is not this story's: it is #355. What this file holds the
 * clock to there is that it adds none: with the clock given, every node lands exactly where it does
 * without it. When #355 lands, the cases here that give way are the ones to re-read.
 *
 * Each case renders once and then changes what is on screen through state, so "without the clock"
 * and "with it" are one composition on one screen. Nothing here restates where a node should land.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TimerScreenTimeOfDayTest {

    @get:Rule
    val compose = createComposeRule()

    private var clock by mutableStateOf<String?>(null)
    private var state by mutableStateOf(TimerState.IDLE)
    private var notice by mutableStateOf<String?>(null)
    private var prompt by mutableStateOf(false)
    private var choice by mutableStateOf<String?>(null)
    private var fontScale by mutableStateOf<Float?>(null)

    /** A node as laid out: what it says (or its role), and where. */
    private data class Shot(val label: String, val bounds: Rect)

    private fun render() {
        compose.setContent {
            GlobalSnapshotFlushLoop()
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale ?: base.fontScale)) {
                TimerScreen(
                    // 2:05 counted up, or 4:59 to go: four characters, so the readout is as tall as
                    // the screen lets it be, which is the tightest the column gets.
                    readout = PhoneReadout.of(state, 299_000L, 125_000L),
                    sequenceName = SEQUENCE,
                    state = state,
                    onStart = {},
                    onStop = {},
                    onSync = {},
                    notice = notice,
                    brightnessPrompt = prompt,
                    pairChoice = choice,
                    timeOfDay = clock,
                )
            }
        }
        compose.waitForIdle()
    }

    private fun settle() = compose.waitForIdle()

    private fun showClock() {
        clock = "12:59"
        settle()
    }

    private fun root(): SemanticsNode = compose.onRoot(useUnmergedTree = true).fetchSemanticsNode()

    private fun find(predicate: (SemanticsNode) -> Boolean): SemanticsNode? {
        fun walk(n: SemanticsNode): SemanticsNode? = if (predicate(n)) n else n.children.firstNotNullOfOrNull(::walk)
        return walk(root())
    }

    /** Every text and every control on screen, in tree order, the clock left out. */
    private fun shots(): List<Shot> {
        val out = mutableListOf<Shot>()
        fun walk(node: SemanticsNode) {
            if (node.config.getOrNull(SemanticsProperties.TestTag) != TAG_TIME_OF_DAY) {
                val text = node.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }
                val role = node.config.getOrNull(SemanticsProperties.Role)
                if (text != null) out += Shot(text, node.boundsInRoot)
                else if (role != null) out += Shot(role.toString(), node.boundsInRoot)
            }
            node.children.forEach(::walk)
        }
        walk(root())
        return out
    }

    private fun clockNode(): SemanticsNode? = find { it.config.getOrNull(SemanticsProperties.TestTag) == TAG_TIME_OF_DAY }

    /** Up means laid out and placed. A clock that gave way is composed but never placed. */
    private fun clockIsUp(): Boolean = clockNode()?.layoutInfo?.isPlaced == true

    private fun layoutOf(node: SemanticsNode): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
        return results.single()
    }

    private fun readoutNode(): SemanticsNode =
        find { n -> n.config.getOrNull(SemanticsProperties.Text)?.any { it.text == "2:05" || it.text == "4:59" } == true }!!

    private fun nameBounds(): Rect =
        find { it.config.getOrNull(SemanticsProperties.Text)?.any { t -> t.text == SEQUENCE } == true }!!.boundsInRoot

    private fun heightDp(bounds: Rect): Float = with(compose.density) { bounds.height.toDp().value }

    /**
     * Nothing clipped: every node inside the screen and with a size, and every control at least
     * Material's 40 dp button height, which a squeezed control drops below.
     */
    private fun assertNothingClips(shots: List<Shot>) {
        val screen = root().boundsInRoot
        for (shot in shots) {
            val b = shot.bounds
            assertTrue(
                "${shot.label} at $b must lie on the screen $screen",
                b.left >= screen.left && b.top >= screen.top && b.right <= screen.right && b.bottom <= screen.bottom,
            )
            assertTrue("${shot.label} must have a size, not be squeezed to nothing", b.height > 0f && b.width > 0f)
            if (shot.label == "Button") {
                assertTrue("a control is ${heightDp(b)} dp, under the 40 dp a button needs", heightDp(b) >= 40f)
            }
        }
    }

    /** What the readout computes, to hold it to the same after the clock arrives. */
    private data class ReadoutSize(val fontSizeSp: Float, val height: Float)

    private fun readoutSize(): ReadoutSize {
        val node = readoutNode()
        return ReadoutSize(layoutOf(node).layoutInput.style.fontSize.value, node.boundsInRoot.height)
    }

    /** With the clock up: every other node keeps its height, and nothing clips, the clock included. */
    private fun assertClockUpAndNothingSqueezed(before: List<Shot>, readoutBefore: ReadoutSize) {
        assertTrue("there is room for the clock here, so it must be up", clockIsUp())
        val after = shots()
        assertEquals(before.map { it.label }, after.map { it.label })
        before.zip(after).forEach { (b, a) ->
            assertEquals("${b.label} keeps its height with the clock up", b.bounds.height, a.bounds.height)
        }
        assertNothingClips(after + Shot("clock", clockNode()!!.boundsInRoot))
        assertEquals("the readout keeps the size it computes without the clock", readoutBefore, readoutSize())
        // It heads the column: above the sequence name, so never beside the readout.
        assertTrue(
            "the clock heads the column",
            clockNode()!!.boundsInRoot.bottom <= after.first { it.label == SEQUENCE }.bounds.top,
        )
    }

    /** With no room: the clock gives way, and every node is exactly where it was without it. */
    private fun assertClockGivesWay(before: List<Shot>, readoutBefore: ReadoutSize) {
        assertFalse("there is no room for the clock here, so it must give way", clockIsUp())
        assertEquals("every node lands exactly where it does without the clock", before, shots())
        assertEquals(readoutBefore, readoutSize())
    }

    // --- The AC's worst case, at the floor, in both orientations ---------------------------------

    @Test
    @Config(qualifiers = "w320dp-h470dp-port-xhdpi")
    fun `portrait at the floor with the notice and the prompt up, the clock is up and nothing clips`() {
        state = TimerState.COUNTING_UP; notice = NOTICE; prompt = true
        render()
        val before = shots()
        val readout = readoutSize()
        assertNothingClips(before)

        showClock()
        assertClockUpAndNothingSqueezed(before, readout)
    }

    @Test
    @Config(qualifiers = "w470dp-h320dp-land-xhdpi")
    fun `landscape at the floor with the notice and the prompt up, the clock gives way and moves nothing`() {
        state = TimerState.COUNTING_UP; notice = NOTICE; prompt = true
        render()
        val before = shots()
        val readout = readoutSize()

        showClock()
        assertClockGivesWay(before, readout)
    }

    // --- Landscape at the floor, with room ---------------------------------------------------------

    @Test
    @Config(qualifiers = "w470dp-h320dp-land-xhdpi")
    fun `landscape at the floor with nothing else up, the clock is up and nothing clips`() {
        state = TimerState.COUNTING_UP
        render()
        val before = shots()
        val readout = readoutSize()
        assertNothingClips(before)

        showClock()
        assertClockUpAndNothingSqueezed(before, readout)
    }

    @Test
    @Config(qualifiers = "w470dp-h320dp-land-xhdpi")
    fun `landscape at the floor with one notice line, the clock still fits`() {
        state = TimerState.RUNNING; notice = NOTICE
        render()
        val before = shots()
        val readout = readoutSize()
        assertNothingClips(before)

        showClock()
        assertClockUpAndNothingSqueezed(before, readout)
    }

    @Test
    @Config(qualifiers = "w470dp-h320dp-land-xhdpi")
    fun `landscape at the floor with the notice and the pair choice up, the clock gives way`() {
        state = TimerState.RUNNING; notice = NOTICE; choice = CHOICE
        render()
        val before = shots()
        val readout = readoutSize()

        showClock()
        assertClockGivesWay(before, readout)
    }

    // --- The owner's phone, where the AC's worst case is exactly full ------------------------------

    @Test
    @Config(qualifiers = "w824dp-h384dp-land-xxxhdpi")
    fun `on the SM-S918U in landscape the clock gives way to notice and prompt, and returns when the prompt goes`() {
        state = TimerState.COUNTING_UP; notice = NOTICE; prompt = true
        render()
        val full = shots()
        val readout = readoutSize()
        showClock()
        assertClockGivesWay(full, readout)

        // The prompt answered, the notice still up: there is room again, so the clock returns. On
        // the same composition, so it is known to come back rather than stay gone once it has gone.
        clock = null; prompt = false
        settle()
        val withoutPrompt = shots()
        showClock()
        assertClockUpAndNothingSqueezed(withoutPrompt, readout)
    }

    // --- Sized from the screen --------------------------------------------------------------------

    @Test
    @Config(qualifiers = "w360dp-h640dp-port-xxhdpi")
    fun `the clock is sized from the shorter side`() {
        render()
        showClock()
        assertTrue("the clock must be up for its size to mean anything", clockIsUp())
        // 1/11 of 360 dp, written in sp at font scale 1.
        assertEquals(360f / 11f, layoutOf(clockNode()!!).layoutInput.style.fontSize.value, 0.01f)
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp-port-xxhdpi")
    fun `a raised system font does not grow the clock`() {
        // The design constraint: sized from the screen through the density, like the readout, so the
        // accessible setting is never what pushes the clock into the controls' room.
        render()
        showClock()
        // Placed at both scales, or the two readings are both an empty box and agree for nothing.
        assertTrue(clockIsUp())
        val plain = clockNode()!!.boundsInRoot

        fontScale = 2f
        settle()
        assertTrue("a raised font must not be what makes the clock give way", clockIsUp())
        val scaled = clockNode()!!.boundsInRoot
        assertEquals("the clock's height at font scale 2", plain.height, scaled.height, 0.5f)
        assertEquals("the clock's width at font scale 2", plain.width, scaled.width, 0.5f)
        // The positive control: the same font scale does grow the sequence name, which is a bare sp.
        // Without it, a density this test failed to apply would pass the two lines above.
        // Bounds are read off the live layout, so each is taken as a value before the scale moves.
        val nameAt2 = nameBounds()
        fontScale = 1f
        settle()
        val nameAt1 = nameBounds()
        assertTrue("font scale 2 must grow a bare-sp label: $nameAt1 against $nameAt2", nameAt2.height > nameAt1.height * 1.5f)
    }

    // --- Every state --------------------------------------------------------------------------------

    @Test
    @Config(qualifiers = "w360dp-h640dp-port-xxhdpi")
    fun `the clock is up in every timer state`() {
        render()
        showClock()
        for (each in TimerState.values()) {
            state = each
            settle()
            assertTrue("the clock must be up in $each", clockIsUp())
            assertEquals("12:59", clockNode()!!.config[SemanticsProperties.Text].single().text)
        }
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp-port-xxhdpi")
    fun `no clock is composed when none is given`() {
        render()
        assertNull(clockNode())
        showClock()
        assertNotNull(clockNode())
    }

    private companion object {
        const val SEQUENCE = "US Sailing 5-4-1-Go"
        val NOTICE = pairGunsApartLine(12_300L, "Watch")
        const val CHOICE = "Watch started too, 0.6 s later — both following the watch"
    }
}
