package com.racetimer.phone.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.racetimer.shared.PAIR_STATUS_TEXT_ARGB
import com.racetimer.shared.PHONE_NOTICE_TEXT_ARGB
import com.racetimer.shared.SEQUENCE_NAME_TEXT_ARGB
import com.racetimer.shared.TIME_OF_DAY_TEXT_ARGB
import com.racetimer.shared.TimerState
import kotlin.math.min

/**
 * Test tags for the count-up brightness prompt (#279), so its assertions find the two controls by
 * identity rather than by display text.
 */
const val TAG_KEEP_BRIGHT = "count-up-keep-bright"
const val TAG_DIM_COUNT_UP = "count-up-dim"

/** Test tag for the pair's status row (#219). */
const val TAG_PAIR_STATUS = "pair-status"

/** Test tag for the time of day at the top of the column (#304). */
const val TAG_TIME_OF_DAY = "time-of-day"

/** Test tags for the choice between the phone's start and the watch's (#220). */
const val TAG_SYNC_TO_PHONE = "pair-sync-to-phone"
const val TAG_SYNC_TO_WATCH = "pair-sync-to-watch"

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
 * The time of day's type size, as a fraction of the screen's shorter side (#304, owner decision).
 *
 * 1/11 is about 35 sp on the owner's SM-S918U and 29 sp at Android's 320 dp floor. That is roughly
 * two and a half times the status bar's clock, which the officer could not read from across the
 * committee boat, and about a fifth of the readout, so the clock stays visibly secondary to the
 * countdown. 1/8 was rejected as too close to the readout's scale, and it would give way more
 * often in landscape. 1/14 was rejected as barely larger than the status bar's.
 */
private const val TIME_OF_DAY_SHORTER_SIDE_FRACTION = 1f / 11f

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
 * @param leadInOffered True when the selected sequence may be armed with a lead-in (#207), which is
 *                      race-manager modes only — the rule is `offersLeadIn` in `shared/`, never a
 *                      decision taken here. Puts a Lead-in control beside Start on the pre-start
 *                      screen. Absent while a resume offer stands, for the watch's reason: the
 *                      question that screen asks is *this race or a fresh one*, and Start over
 *                      already re-runs whatever lead the saved race carried.
 * @param inLeadIn      True while a running race is still in its lead-in. Drops the Sync button
 *                      for the duration — the sequence proper has not started, so there is nothing
 *                      to snap to and the engine refuses anyway (`isInLeadIn`); a button that takes
 *                      the tap and does nothing reads as broken.
 * @param onLeadIn      Tapped to choose the box alert and start. Only reachable when
 *                      [leadInOffered].
 * @param pairStatus    The pair link's row (#219): the watch, its clock offset and the bound on it.
 *                      Drawn on the pre-start screen only, small and last, so it moves nothing a
 *                      running race lays out. Null draws nothing, and is what a phone with no watch
 *                      gets.
 * @param pairChoice    The line saying the watch started too and which start both devices follow
 *                      (#220), or null. While a countdown runs it puts the choice of either start
 *                      in front of the officer: the owner's rule at #220's pickup is that the later
 *                      tap wins and the console says so, with a way back to the other. Above the
 *                      readout for the brightness prompt's reason — nowhere near Sync and Stop, which
 *                      an urgent thumb is reaching for.
 * @param onSyncToPhone Tapped to run both devices on the phone's start.
 * @param onSyncToWatch Tapped to run both devices on the watch's start.
 * @param timeOfDay     The time of day as the clock writes it (`formatTimeOfDay` in `shared/`), or
 *                      null for no clock (#304). It heads the column, above the sequence name, so it
 *                      is never next to the readout: a second large number beside "0:58" is the
 *                      misread #303 rejected the under-readout slot for. In every timer state. It
 *                      **gives way whenever the rest of the column leaves no room for it**, which
 *                      in landscape is any time a notice line and the count-up prompt or the pair
 *                      choice are up together. It returns when a line clears, so it never squeezes a
 *                      control and never moves the readout's size (owner decision on #304, following
 *                      the watch's clock giving way to its message lines in #303).
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
    leadInOffered: Boolean = false,
    inLeadIn: Boolean = false,
    onLeadIn: () -> Unit = {},
    pairStatus: String? = null,
    pairChoice: String? = null,
    onSyncToPhone: () -> Unit = {},
    onSyncToWatch: () -> Unit = {},
    timeOfDay: String? = null,
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
        // The same conversion, for the same reason: fitted to the screen, so a raised system font
        // must not be what grows it into the room the controls need (#304).
        val timeOfDaySize = with(LocalDensity.current) {
            (min(maxWidth.value, maxHeight.value) * TIME_OF_DAY_SHORTER_SIDE_FRACTION).dp.toSp()
        }

        HeadGivesWayColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
        ) {
            if (timeOfDay != null) {
                // The head the column drops when it is full. Drawn straight onto the state
                // background, so its colour is the constant MessageContrastTest holds to every
                // background, the watch's clock included.
                Text(
                    text = timeOfDay,
                    color = Color(TIME_OF_DAY_TEXT_ARGB),
                    fontSize = timeOfDaySize,
                    maxLines = 1,
                    softWrap = false,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .layoutId(HEAD_SLOT)
                        .testTag(TAG_TIME_OF_DAY),
                )
            }

            Text(
                text = sequenceName,
                color = Color(SEQUENCE_NAME_TEXT_ARGB),
                fontSize = 16.sp,
                textAlign = TextAlign.Center,
            )

            if (notice != null) {
                // One line, quiet, under the name: advice, not alarm. The phone has no tiered
                // message surface yet; when one arrives this is the line it absorbs.
                Text(
                    text = notice,
                    color = Color(PHONE_NOTICE_TEXT_ARGB),
                    fontSize = 16.sp,
                    textAlign = TextAlign.Center,
                )
            }

            if (brightnessPrompt) {
                CountUpBrightnessPrompt(onKeepBright = onKeepBright, onDim = onDimCountUp)
            }

            if (state == TimerState.RUNNING && pairChoice != null) {
                PairStartChoice(line = pairChoice, onSyncToPhone = onSyncToPhone, onSyncToWatch = onSyncToWatch)
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
            } else if (state == TimerState.RUNNING && inLeadIn) {
                // Sync has nothing to act on until the sequence proper is under way (#207) — see
                // `isInLeadIn`. Rather than leave a button that takes the tap and does nothing,
                // the lead-in gets the sole-control layout Start and End Race use, and Sync
                // reappears on the tick the sequence's own first signal fires.
                Button(
                    onClick = onStop,
                    colors = ButtonDefaults.buttonColors(),
                    modifier = Modifier.fillMaxWidth(0.6f),
                ) {
                    Text(text = "Stop", fontSize = 22.sp, fontWeight = FontWeight.Bold)
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
            } else if (leadInOffered) {
                // Two ways to begin a race, in equal halves, exactly as the watch splits them
                // (#207): a race manager on a committee boat with a signal box reaches for the
                // right-hand one every start of the day, and sizing it as a narrow afterthought
                // would make the more common of the two the harder to hit. Tapping Start here is
                // byte-for-byte what it was — same anchor, same cues; the lead-in is reached only
                // through its own control.
                Row(
                    modifier = Modifier.fillMaxWidth(0.9f),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    Button(
                        onClick = onStart,
                        colors = ButtonDefaults.buttonColors(),
                        modifier = Modifier.weight(1f).padding(end = 8.dp),
                    ) {
                        Text(text = "Start", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    }
                    Button(
                        onClick = onLeadIn,
                        colors = ButtonDefaults.buttonColors(),
                        modifier = Modifier.weight(1f).padding(start = 8.dp),
                    ) {
                        Text(text = LEAD_IN_LABEL, fontSize = 22.sp, fontWeight = FontWeight.Bold)
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

            if (state == TimerState.IDLE && pairStatus != null) {
                Text(
                    text = pairStatus,
                    color = Color(PAIR_STATUS_TEXT_ARGB),
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.testTag(TAG_PAIR_STATUS),
                )
            }
        }
    }
}

/** The `layoutId` that marks the child [HeadGivesWayColumn] may drop: the time of day (#304). */
internal const val HEAD_SLOT = "head"

/**
 * The timer screen's column: centred, spaced evenly, and dropping its head when it is full (#304).
 *
 * **Without a head it is the `Column` it replaced, to the pixel.** It measures each child in order
 * against the height still left, as `Column` does, so a full column still takes its overflow out of
 * its last child (cairn: `a-centred-column-past-full-pins-and-squeezes-its-last-child`). It places
 * them with `Arrangement.SpaceEvenly` and `Alignment.CenterHorizontally`, the two objects the old
 * `Column` was given, so every position rounds the same way. `HeadGivesWayColumnTest` holds it
 * equal to a `Column` built from the same children, below capacity and past it.
 *
 * **The head is the child marked `layoutId(HEAD_SLOT)`, and it is kept only while everything fits.**
 * Before measuring anything, the column asks each child its intrinsic height at the full width and
 * keeps the head only if the total fits. A head that did not fit would have been measured first
 * and left the shortfall to the last child, which is End Race, or Sync and Stop. So the head goes
 * rather than a control shrinking. It is the question *does it fit* rather than *which message is
 * up* because the answer depends on the phone and its orientation. On the SM-S918U in landscape the
 * column is exactly full with a notice line and the count-up prompt up, while in portrait it has
 * room to spare under all of it.
 *
 * A dropped head is composed but never measured or placed, so nothing draws it and it has no
 * bounds. It returns on the first layout pass with room for it.
 */
@Composable
internal fun HeadGivesWayColumn(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val head = measurables.firstOrNull { it.layoutId == HEAD_SLOT }
        val rest = measurables.filter { it !== head }

        val keepHead = head != null &&
            head.maxIntrinsicHeight(width) + rest.sumOf { it.maxIntrinsicHeight(width) } <= height
        val laidOut = if (keepHead) listOf(head!!) + rest else rest

        // Column's measuring, for children with no weight: in order, each against what is left.
        var left = height
        val placeables = laidOut.map { measurable ->
            measurable.measure(Constraints(maxWidth = width, maxHeight = left.coerceAtLeast(0)))
                .also { left -= it.height }
        }

        val sizes = IntArray(placeables.size) { placeables[it].height }
        val tops = IntArray(placeables.size)
        with(Arrangement.SpaceEvenly) { arrange(totalSize = height, sizes = sizes, outPositions = tops) }

        layout(width, height) {
            placeables.forEachIndexed { i, placeable ->
                placeable.place(
                    x = Alignment.CenterHorizontally.align(placeable.width, width, layoutDirection),
                    y = tops[i],
                )
            }
        }
    }
}

/** The lead-in control's label, shared with the tests so the copy lives in one place (#207). */
const val LEAD_IN_LABEL = "Lead-in"

/**
 * Both starts, and which one both devices are following (#220): tap the other to move both there.
 *
 * Two equal halves with the device named on each, rather than Keep and Switch. A Keep reads as the
 * safe tap, and here neither is: the officer is choosing which signal the race is timed from.
 */
@Composable
private fun PairStartChoice(line: String, onSyncToPhone: () -> Unit, onSyncToWatch: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = line,
            color = Color.White,
            fontSize = 16.sp,
            textAlign = TextAlign.Center,
        )
        // The two halves have to read as equals, and on the owner's SM-S918U they did not: "Sync to
        // phone" wrapped inside the default 24 dp side padding while "Sync to watch" did not, and the
        // halves drew at two heights (measured on hardware, #220). Narrower padding fits both on one
        // line there; the shared height keeps them equal on a phone or a font size where one wraps.
        Row(
            modifier = Modifier.fillMaxWidth(0.9f).padding(top = 8.dp).height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            Button(
                onClick = onSyncToPhone,
                colors = ButtonDefaults.buttonColors(),
                contentPadding = CHOICE_PADDING,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(end = 8.dp)
                    .testTag(TAG_SYNC_TO_PHONE),
            ) {
                Text(text = "Sync to phone", fontSize = 16.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            }
            Button(
                onClick = onSyncToWatch,
                colors = ButtonDefaults.buttonColors(),
                contentPadding = CHOICE_PADDING,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(start = 8.dp)
                    .testTag(TAG_SYNC_TO_WATCH),
            ) {
                Text(text = "Sync to watch", fontSize = 16.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            }
        }
    }
}

/** Inside each half of the pair choice: Material's vertical padding, a third of its horizontal. */
private val CHOICE_PADDING = PaddingValues(horizontal = 8.dp, vertical = 8.dp)

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
