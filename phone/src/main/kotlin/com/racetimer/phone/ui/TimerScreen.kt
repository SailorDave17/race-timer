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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.racetimer.shared.TimerState
import kotlin.math.min

/**
 * Test tags for the count-up brightness prompt (#279), so its assertions find the two controls by
 * identity rather than by display text.
 */
const val TAG_KEEP_BRIGHT = "count-up-keep-bright"
const val TAG_DIM_COUNT_UP = "count-up-dim"

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
 * @param state         Where the engine is. The control row is a function of this and nothing else,
 *                      which is why #206 replaced the `running: Boolean` this took until then: a
 *                      race-manager race has *three* live states after Start — counting down,
 *                      counting up, and a frozen summary — and each offers a different control. A
 *                      second boolean beside the first would have been one state table written out
 *                      twice, which is the shape the watch's own display rules warn about in as
 *                      many words. (Named by description rather than by symbol on purpose: this
 *                      module asserts in a test that it never reaches for that file, and the
 *                      assertion reads source text, so a mention in a comment trips it — which is
 *                      exactly what it did on the first run of this story.)
 * @param onStart       Tapped to start the sequence.
 * @param onStop        Tapped to abandon the run and return to the top of the same sequence — and
 *                      the same control, labelled Done, that dismisses a finished race's summary.
 * @param onEndRace     Tapped to end a race-manager count-up (#206), freezing the elapsed time for
 *                      the committee to read.
 * @param onSync        Tapped to snap the countdown to a whole minute (#204) — the officer who
 *                      missed the exact flag bringing the phone back into step with it. Only
 *                      offered while running: before the start there is nothing to correct, and
 *                      the engine refuses everywhere else anyway.
 * @param notice        A line the officer is owed under the sequence name — a degraded restore's
 *                      re-sync advice (#205) — or null for the ordinary nothing.
 * @param brightnessPrompt Whether to ask, this once, whether an unbounded count-up may keep the
 *                      officer's full brightness (#279). Above the readout rather than beside End
 *                      Race, deliberately: the control an officer reaches for past the gun is End
 *                      Race, and a mis-tap that answered a display question instead of ending the
 *                      race — or ended the race instead of answering it — is the worse of the two
 *                      layouts in both directions.
 * @param onKeepBright  Tapped to keep the panel at full brightness through count-ups. Asked once
 *                      per launch, so this answer stands for the rest of the session.
 * @param onDimCountUp  Tapped to let count-ups drop back to system brightness. The countdown is
 *                      untouched — it is the state with a gun to justify the cost.
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
    state: TimerState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onSync: () -> Unit,
    onEndRace: () -> Unit = {},
    notice: String? = null,
    brightnessPrompt: Boolean = false,
    onKeepBright: () -> Unit = {},
    onDimCountUp: () -> Unit = {},
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

            if (brightnessPrompt) {
                CountUpBrightnessPrompt(onKeepBright = onKeepBright, onDim = onDimCountUp)
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

            if (state == TimerState.COUNTING_UP) {
                // The sole control, full width like Start: past the gun there is exactly one thing
                // left to do to this race, and the officer doing it is watching the water rather
                // than the phone. Nothing to sync to and nothing to abandon — Stop is deliberately
                // absent, because a mis-tap that discarded a race in progress has no undo.
                Button(
                    onClick = onEndRace,
                    colors = ButtonDefaults.buttonColors(),
                    modifier = Modifier.fillMaxWidth(0.6f),
                ) {
                    Text(text = "End Race", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                }
            } else if (state == TimerState.RACE_ENDED) {
                // The final time is on screen to be read, not acted on, so Done is the only way out
                // — and it is the same callback as Stop, because dismissing a summary and
                // abandoning a run are one thing to the engine: return to the top of the sequence.
                Button(
                    onClick = onStop,
                    colors = ButtonDefaults.buttonColors(),
                    modifier = Modifier.fillMaxWidth(0.6f),
                ) {
                    Text(text = "Done", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                }
            } else if (state != TimerState.RUNNING && resumeOffer != null) {
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
            } else if (state == TimerState.RUNNING) {
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

/**
 * The one question a count-up asks, and only when there is something to release (#279).
 *
 * A committee count-up has no bound — an hour is ordinary — and no gun left to justify burning the
 * panel for it, which is why the watch's own state table excludes it. The phone does not inherit
 * that rule, because on a console the battery-against-legibility trade belongs to the officer and
 * the day (#225). This is the middle: the officer is asked at the one moment the trade changes, in
 * one tap, once per launch, and their answer stands.
 *
 * Left unanswered it answers itself — see `COUNT_UP_PROMPT_DWELL_MS`, and the reason silence dims
 * rather than keeps.
 */
@Composable
private fun CountUpBrightnessPrompt(onKeepBright: () -> Unit, onDim: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = "Keep the screen bright?",
            color = Color.White,
            fontSize = 16.sp,
            textAlign = TextAlign.Center,
        )
        Row(
            modifier = Modifier.fillMaxWidth(0.9f).padding(top = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            Button(
                onClick = onKeepBright,
                colors = ButtonDefaults.buttonColors(),
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp)
                    .testTag(TAG_KEEP_BRIGHT),
            ) {
                Text(text = "Keep bright", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
            Button(
                onClick = onDim,
                colors = ButtonDefaults.buttonColors(),
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 8.dp)
                    .testTag(TAG_DIM_COUNT_UP),
            ) {
                Text(text = "Dim", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}
