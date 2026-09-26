package com.racetimer.wear.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.LocalTextStyle
import androidx.wear.compose.material.Text
import com.racetimer.shared.fittedTextSize

/** Kept clear at each end of a pill, so a label stepped down to fit never touches the curve. */
private val LABEL_END_INSET = 4.dp

/**
 * One line of text, drawn at [style]'s size when it fits the width it is given and a little smaller
 * when it does not (#311). Never larger.
 *
 * Narrowing things to the round screen narrows the room for their text. *Measured on the Wear
 * emulator* at a 438 px screen and font scale 1.24: "Start over" was cut to "Start", the name of the
 * control beside it, and the sequence name ran under the bezel at either font size. The owner chose
 * shrinking for both, over wrapping, an ellipsis, or letting them run off. Where the text fits,
 * which on an SM-R925U at the default font size is every case measured, it is drawn exactly as a
 * plain `Text` would draw it.
 *
 * Measured with [style] at the screen's font scale, the style the `Text` renders in, so what is
 * measured is what is drawn. [fittedTextSize] does the stepping.
 *
 * **Holds the height of its full-size line**, whatever size it draws at. That is what lets a
 * [RoundScreenColumn] ask it how tall it is before placing it, since the constraints this needs are
 * not known until then and cannot answer an intrinsic query. It also means a shrunk line sits in the
 * slot the full-size one had, so nothing below it moves.
 *
 * @param endInset kept clear at each end of the width given
 */
@Composable
fun FittedText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    endInset: Dp = 0.dp,
) {
    val measurer = rememberTextMeasurer()
    val fullSize = remember(text, style, measurer) {
        measurer.measure(text = AnnotatedString(text), style = style, softWrap = false, maxLines = 1).size
    }
    val lineHeight = with(LocalDensity.current) { fullSize.height.toDp() }
    BoxWithConstraints(
        modifier = modifier
            .height(lineHeight)
            .padding(horizontal = endInset),
        contentAlignment = Alignment.Center,
    ) {
        val available = constraints.maxWidth
        val fitted = remember(text, style, available, measurer) {
            val base = style.fontSize.value
            when {
                !constraints.hasBoundedWidth || fullSize.width <= available -> base
                else -> fittedTextSize(base, available.toFloat()) { size ->
                    measurer.measure(
                        text = AnnotatedString(text),
                        style = style.copy(fontSize = size.sp),
                        softWrap = false,
                        maxLines = 1,
                    ).size.width.toFloat()
                }
            }
        }
        Text(
            text = text,
            style = style,
            color = color,
            fontSize = fitted.sp,
            maxLines = 1,
            softWrap = false,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * A pill's one-line label: [FittedText] in the button's own [LocalTextStyle], at [fontSize] and
 * [fontWeight], kept [LABEL_END_INSET] clear of the pill's ends.
 */
@Composable
fun FittedLabel(
    text: String,
    fontSize: TextUnit,
    fontWeight: FontWeight,
    modifier: Modifier = Modifier,
) {
    FittedText(
        text = text,
        style = LocalTextStyle.current.merge(TextStyle(fontSize = fontSize, fontWeight = fontWeight)),
        modifier = modifier,
        endInset = LABEL_END_INSET,
    )
}
