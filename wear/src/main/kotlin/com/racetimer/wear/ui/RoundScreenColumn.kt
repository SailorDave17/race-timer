package com.racetimer.wear.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.ParentDataModifier
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.racetimer.shared.roundScreenBoxWidth
import com.racetimer.shared.roundScreenPillWidth
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * [TimerScreen]'s column: the `Column(Arrangement.Center, Alignment.CenterHorizontally)` centred in a
 * full-screen `Box` that it replaced, measured the same way, plus the one thing a `Column` cannot do
 * (#311). A child marked [fitsRoundScreen] or [staysInsideRoundScreen] is drawn only as wide as the
 * round display is at the height the child ends up at.
 *
 * ### Why not a Column
 *
 * A child of a `Column` is measured before it is placed, so it cannot know how far down the screen it
 * will sit, and on a round watch that is what decides how wide it may be. The pre-start row was
 * therefore given a fraction of the width and left wherever the column put it, and on a smaller circle,
 * or lower in the same one, its ends ran under the bezel. The sequence name at the top did the same.
 * Here a marked child's height is read first, from its intrinsic height, since the children it is for
 * are all one fixed height. The column is laid out around that height, and only then is the child
 * measured, at the width the circle allows there.
 *
 * ### Measured exactly as the Column was
 *
 * #233's plate positions and #301's squeeze were measured on that `Column`, so this reproduces it
 * rather than improving on it. Children are measured in order, each offered the height the ones before
 * it left, so an overflow still comes out of the last child. The block is centred in the height the
 * padding leaves, and each child is centred on the screen. Only a marked child's width differs.
 *
 * *Checked on the Wear emulator* at 438 and 450 px, 340 dpi, font scales 1.0 and 1.24, against the
 * `Column` build: every text row sits on the same pixel rows. Horizontally a child can land 1 px to
 * one side of where it was, because the `Column` rounded twice, centring itself on the screen and
 * then the child in itself, and this rounds once.
 *
 * Fills its parent, which must be the whole screen: the circle it fits to is inscribed in its own
 * bounds.
 *
 * @param isRound whether the display is round. On a square one a marked child takes its cap.
 * @param padding the column's padding on every side, as the `Column` it replaced had.
 */
@Composable
fun RoundScreenColumn(
    isRound: Boolean,
    modifier: Modifier = Modifier,
    padding: Dp = 0.dp,
    content: @Composable () -> Unit,
) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val pad = padding.roundToPx()
        val innerWidth = (width - 2 * pad).coerceAtLeast(0)
        val innerHeight = (height - 2 * pad).coerceAtLeast(0)

        // First pass: every child's height, in order, as a Column finds it. A marked child is not
        // measured yet. Its height is read from its intrinsics, and it is squeezed exactly as a
        // Column would squeeze it if the children above have left less than it wants.
        val placeables = arrayOfNulls<Placeable>(measurables.size)
        val heights = IntArray(measurables.size)
        var used = 0
        measurables.forEachIndexed { i, measurable ->
            val remaining = (innerHeight - used).coerceAtLeast(0)
            val fit = measurable.parentData as? RoundScreenFit
            heights[i] = if (fit == null) {
                measurable.measure(Constraints(maxWidth = innerWidth, maxHeight = remaining))
                    .also { placeables[i] = it }
                    .height
            } else {
                measurable.maxIntrinsicHeight(fit.cap(innerWidth)).coerceAtMost(remaining)
            }
            used += heights[i]
        }

        // Where the Box centred the Column, rounded the same way, so no child moves up or down.
        val top = pad + ((innerHeight - used) / 2f).roundToInt()

        // Second pass: now that every child's place is known, the marked ones are measured at the
        // width of the circle where they sit.
        val diameter = min(width, height).toFloat()
        var y = top
        measurables.forEachIndexed { i, measurable ->
            val fit = measurable.parentData as? RoundScreenFit
            val childHeight = heights[i]
            val cap = fit?.cap(innerWidth) ?: 0
            placeables[i] = when (fit) {
                null -> placeables[i]
                is RoundScreenFit.Pill -> {
                    val pillWidth = if (!isRound) cap else roundScreenPillWidth(
                        screenDiameter = diameter,
                        centreOffset = y + childHeight / 2f - height / 2f,
                        pillHeight = childHeight.toFloat(),
                        widthCap = cap.toFloat(),
                    ).toInt()
                    measurable.measure(Constraints.fixed(pillWidth, childHeight))
                }
                RoundScreenFit.Box -> {
                    val maxWidth = if (!isRound) cap else roundScreenBoxWidth(
                        screenDiameter = diameter,
                        topOffset = y - height / 2f,
                        bottomOffset = y + childHeight - height / 2f,
                        widthCap = cap.toFloat(),
                    ).toInt()
                    measurable.measure(
                        Constraints(maxWidth = maxWidth, minHeight = childHeight, maxHeight = childHeight)
                    )
                }
            }
            y += childHeight
        }

        layout(width, height) {
            var rowTop = top
            placeables.forEach { placeable ->
                val child = checkNotNull(placeable) { "every child is measured in one of the two passes" }
                child.place(Alignment.CenterHorizontally.align(child.width, width, layoutDirection), rowTop)
                rowTop += child.height
            }
        }
    }
}

/**
 * Draws this child of a [RoundScreenColumn] as a pill exactly as wide as the round display allows at
 * the height it sits, and no wider than [maxWidthFraction] of the column where the display leaves more
 * room than that.
 *
 * For fixed-height pills only. The column reads the child's height from its intrinsics before placing
 * it, and then measures it at exactly that height, so the child must say how tall it is (a `height`
 * modifier does). It needs no width modifier of its own. It is given one fixed width, and a
 * `fillMaxWidth` on it would do nothing.
 *
 * Does nothing outside a [RoundScreenColumn].
 */
fun Modifier.fitsRoundScreen(maxWidthFraction: Float): Modifier =
    this.then(RoundScreenFit.Pill(maxWidthFraction))

/**
 * Lets this child of a [RoundScreenColumn] be no wider than the round display at whichever of its top
 * and bottom edges is further from the centre. It keeps its own width inside that, so a short line
 * stays snug and centred.
 *
 * For a one-line child, such as the sequence name: its height is read before it is placed and then
 * held, so it must not wrap to a second line at the narrower width. [FittedText] is built for this.
 * It holds its line's height and draws smaller rather than wrap.
 *
 * Does nothing outside a [RoundScreenColumn].
 */
fun Modifier.staysInsideRoundScreen(): Modifier = this.then(RoundScreenFit.Box)

/** The markers [fitsRoundScreen] and [staysInsideRoundScreen] leave for [RoundScreenColumn]. */
private sealed interface RoundScreenFit : ParentDataModifier {
    override fun Density.modifyParentData(parentData: Any?): Any = this@RoundScreenFit

    /** The widest this child is drawn where the circle does not constrain it, in px. */
    fun cap(innerWidth: Int): Int

    data class Pill(val maxWidthFraction: Float) : RoundScreenFit {
        override fun cap(innerWidth: Int): Int = (innerWidth * maxWidthFraction).roundToInt()
    }

    object Box : RoundScreenFit {
        override fun cap(innerWidth: Int): Int = innerWidth
    }
}
