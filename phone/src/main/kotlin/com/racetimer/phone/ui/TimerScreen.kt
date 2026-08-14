package com.racetimer.phone.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
 * @param onSync        Tapped to snap the countdown to the nearest minute (#204) — the officer who
 *                      missed the exact flag bringing the phone back into step with it. Only
 *                      offered while running: before the start there is nothing to correct, and
 *                      the engine refuses everywhere else anyway.
 * @param notice        A line the officer is owed under the sequence name — a degraded restore's
 *                      re-sync advice (#205) — or null for the ordinary nothing.
 * @param resumeOffer   The saved race's remaining time as display text, when a killed race is
 *                      waiting to be taken back (#205); null when there is nothing to offer. While
 *                      non-null and the race is not running, the controls become Resume and
 *                      Start over.
 * @param onResume      Tapped to take the saved race back exactly where it was.
 * @param onStartOver   Tapped to decline it and run the sequence from the top.
 */
@Composable
fun TimerScreen(
    readout: PhoneReadout,
    sequenceName: String,
    running: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onSync: () -> Unit,
    notice: String? = null,
    resumeOffer: String? = null,
    onResume: () -> Unit = {},
    onStartOver: () -> Unit = {},
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

            if (notice != null) {
                // One line, quiet, under the name: advice, not alarm. The phone has no tiered
                // message surface yet; when one arrives this is the line it absorbs.
                Text(
                    text = notice,
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 16.sp,
                    textAlign = TextAlign.Center,
                )
            }

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

            if (!running && resumeOffer != null) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    // The number is what resuming will actually put on the clock, so the officer
                    // decides against the truth — the watch learned that an offer showing the full
                    // duration resumed to a different number the instant it was tapped.
                    Text(
                        text = "Race under way — $resumeOffer left",
                        color = Color.White,
                        fontSize = 18.sp,
                        textAlign = TextAlign.Center,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(0.9f).padding(top = 12.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        Button(
                            onClick = onResume,
                            colors = ButtonDefaults.buttonColors(),
                            modifier = Modifier.weight(1f).padding(end = 8.dp),
                        ) {
                            Text(text = "Resume", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        }
                        Button(
                            onClick = onStartOver,
                            colors = ButtonDefaults.buttonColors(),
                            modifier = Modifier.weight(1f).padding(start = 8.dp),
                        ) {
                            Text(text = "Start over", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            } else if (running) {
                // Sync first, Stop second: sync is the control an officer reaches for mid-race at
                // a flag, stop is the one that ends everything — the destructive control goes
                // furthest from where an urgent thumb lands.
                Row(
                    modifier = Modifier.fillMaxWidth(0.9f),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    Button(
                        onClick = onSync,
                        colors = ButtonDefaults.buttonColors(),
                        modifier = Modifier.weight(1f).padding(end = 8.dp),
                    ) {
                        Text(text = "Sync", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    }
                    Button(
                        onClick = onStop,
                        colors = ButtonDefaults.buttonColors(),
                        modifier = Modifier.weight(1f).padding(start = 8.dp),
                    ) {
                        Text(text = "Stop", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    }
                }
            } else {
                Button(
                    onClick = onStart,
                    colors = ButtonDefaults.buttonColors(),
                    modifier = Modifier.fillMaxWidth(0.6f),
                ) {
                    Text(text = "Start", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
