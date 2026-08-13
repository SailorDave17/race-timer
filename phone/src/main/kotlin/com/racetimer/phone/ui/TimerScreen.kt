package com.racetimer.phone.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.min

/**
 * How much of the shorter screen dimension the readout may occupy vertically.
 *
 * The rest is the sequence name above and the one button below, which have to stay reachable in
 * landscape on a short phone.
 */
private const val READOUT_HEIGHT_FRACTION = 0.44f

/**
 * Approximate advance width of one bold glyph, as a fraction of the font size.
 *
 * Compose (at the version pinned here) cannot size text to fit its box, so the readout's size is
 * computed rather than measured, and this is the constant that makes the arithmetic conservative.
 * A real digit in the default bold face runs about 0.58; 0.68 leaves the margin, because the two
 * failure directions are not equal — a readout 10 % smaller than it could be is invisible, and one
 * 10 % too large is a clock with its minutes clipped off the edge.
 *
 * Whether the result is actually readable across a committee boat is not a thing this file can
 * assert. That is #215, on the water, with the owner's eyes.
 */
private const val GLYPH_WIDTH_FRACTION = 0.68f

/**
 * The console clock: the whole screen is the countdown.
 *
 * @param readout       What to draw — text and background, both derived from the engine
 *                      ([PhoneReadout.of]); this composable computes neither.
 * @param sequenceName  The loaded sequence, small, above the readout — the officer's confirmation
 *                      that the phone is running the race they think it is.
 * @param running       True while the engine is in a state Stop applies to.
 * @param onStart       Tapped to start the sequence.
 * @param onStop        Tapped to abandon the run and return to the top of the same sequence.
 */
@Composable
fun TimerScreen(
    readout: PhoneReadout,
    sequenceName: String,
    running: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(readout.backgroundArgb)),
    ) {
        val byWidth = maxWidth.value / (readout.text.length * GLYPH_WIDTH_FRACTION)
        val byHeight = maxHeight.value * READOUT_HEIGHT_FRACTION
        // Converted through the density rather than used as a bare `.sp`, which would be the same
        // number only at font scale 1. The size above was fitted to the *screen*, so it has to stay
        // that size on a phone whose owner has turned the system font up — otherwise the accessible
        // setting is what clips the clock.
        val readoutSize = with(LocalDensity.current) { min(byWidth, byHeight).dp.toSp() }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceEvenly,
        ) {
            Text(
                text = sequenceName,
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 16.sp,
                textAlign = TextAlign.Center,
            )

            Text(
                text = readout.text,
                color = Color.White,
                fontSize = readoutSize,
                lineHeight = readoutSize,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                softWrap = false,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )

            Button(
                onClick = if (running) onStop else onStart,
                colors = ButtonDefaults.buttonColors(),
                modifier = Modifier.fillMaxWidth(0.6f),
            ) {
                Text(
                    text = if (running) "Stop" else "Start",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}
