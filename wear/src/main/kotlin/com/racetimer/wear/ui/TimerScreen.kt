package com.racetimer.wear.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.racetimer.shared.BANNER_MAX_WIDTH_FRACTION
import com.racetimer.shared.BANNER_TOP_FRACTION
import com.racetimer.shared.BG_FINAL_TEN_ARGB
import com.racetimer.shared.TIER1_SCRIM_ARGB
import com.racetimer.shared.TIER1_TEXT_ARGB
import com.racetimer.shared.TIER3_SCRIM_ARGB
import com.racetimer.shared.TIER3_TEXT_ARGB
import com.racetimer.shared.TimerState
import com.racetimer.shared.backgroundArgbFor
import com.racetimer.shared.bannerFitsRoundScreen
import com.racetimer.shared.formatCountdown
import com.racetimer.shared.formatElapsed
import kotlinx.coroutines.delay

/** How long a Tier 1 banner stays up, counted from the composition that puts it on screen (#102). */
private const val MESSAGE_DURATION_MS = 3_000L

// ---------------------------------------------------------------------------
// Background colour states
// ---------------------------------------------------------------------------

// The four background states, and the rule picking between them, live in
// `shared/MessageContrast.kt` — the contrast guard has to measure the same values the screen
// renders, so there is one definition and this file reads it (#123).
private val BG_FINAL_TEN = Color(BG_FINAL_TEN_ARGB)

/** Pick the background colour for the given [remainingMs] and [state]. */
private fun backgroundColorFor(remainingMs: Long, state: TimerState): Color =
    Color(backgroundArgbFor(remainingMs, state))

// ---------------------------------------------------------------------------
// Main timer screen
// ---------------------------------------------------------------------------

/**
 * Full-screen glanceable countdown for the Wear OS watch.
 *
 * @param remainingMs    Milliseconds until the gun (may be negative after the gun).
 * @param elapsedMs      Milliseconds since the gun — live in [TimerState.COUNTING_UP], frozen at
 *                       the final race time in [TimerState.RACE_ENDED]; ignored otherwise.
 * @param state          Current [TimerState] of the engine.
 * @param sequenceName   Name of the loaded sequence shown as a small label.
 * @param syncLabel      Non-null for ~2 s after a sync to flash "Synced → X:XX".
 * @param showResyncPrompt True after a degraded recovery (reboot / clock step): the restored gun
 *                       is best-effort, so prompt the sailor to tap Sync against the RC flag.
 * @param message        Non-null to show a transient notice/warning banner (e.g. clock jump).
 * @param onMessageExpired Called once [message] has been on screen for [MESSAGE_DURATION_MS], so the
 *                       caller can clear it. The dwell belongs here rather than at the call site
 *                       because it is screen time that was promised, not wall time — see the
 *                       LaunchedEffect below and #102.
 * @param resumeOffered  True when a race survived a process kill and [remainingMs] is *that* race's
 *                       clock rather than the sequence's full duration: Start becomes a choice
 *                       between resuming it and running the sequence from the top.
 * @param previewElapsed True when the offered race is already past its gun (a race-manager count-up),
 *                       so the readout shows [elapsedMs] instead of a countdown to a gun that fired.
 * @param discardWarning Non-null when a saved race is still recoverable but belongs to a *different*
 *                       sequence, so tapping Start destroys it. Tier 3 per `docs/message-surface.md`:
 *                       a standing caveat about the very next tap, so it persists rather than clearing
 *                       itself. Mutually exclusive with [resumeOffered] by construction — the saved
 *                       race either matches the selection or it does not.
 * @param leadInOffered  True when the selected sequence may be armed with a lead-in (#104), which is
 *                       race-manager modes only — the rule is `offersLeadIn` in `shared/`, never a
 *                       branch on a sequence name here. Every other sequence's pre-start screen is
 *                       byte-for-byte what it was.
 * @param inLeadIn       True while a running race is still in its lead-in. Drops the Sync button for
 *                       the duration: there is nothing to snap to before the signal box has been
 *                       started, and snapping would delete part of the lead (`isInLeadIn`).
 * @param onStart        Called when the user taps Start, or Resume when [resumeOffered].
 * @param onStartOver    Called when the user taps Start over. Only reachable when [resumeOffered].
 * @param onLeadIn       Called when the user taps the lead-in control. Only reachable when
 *                       [leadInOffered].
 * @param onStop         Called when the user taps Stop, or Done in [TimerState.RACE_ENDED].
 * @param onSync         Called when the user taps Sync.
 * @param onEndRace      Called when the user taps End Race in [TimerState.COUNTING_UP].
 * @param onPickSequence Called when the user taps the sequence name to change it (when not running).
 */
@Composable
fun TimerScreen(
    remainingMs: Long,
    elapsedMs: Long = 0L,
    state: TimerState,
    sequenceName: String,
    syncLabel: String?,
    showResyncPrompt: Boolean = false,
    message: String? = null,
    onMessageExpired: () -> Unit = {},
    resumeOffered: Boolean = false,
    previewElapsed: Boolean = false,
    discardWarning: String? = null,
    leadInOffered: Boolean = false,
    inLeadIn: Boolean = false,
    onStart: () -> Unit,
    onStartOver: () -> Unit = {},
    onLeadIn: () -> Unit = {},
    onStop: () -> Unit,
    onSync: () -> Unit,
    onEndRace: () -> Unit = {},
    onPickSequence: () -> Unit = {},
) {
    val targetBg = backgroundColorFor(remainingMs, state)
    val animatedBg by animateColorAsState(
        targetValue = targetBg,
        animationSpec = tween(durationMillis = 300),
        label = "bgColor"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(animatedBg),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(8.dp),
        ) {

            // Sequence name label — tappable to change the sequence when not running, counting up,
            // or reviewing a just-ended race: none of those have any business swapping sequences.
            val canPick = state != TimerState.RUNNING &&
                state != TimerState.COUNTING_UP &&
                state != TimerState.RACE_ENDED
            Text(
                text = if (canPick) "$sequenceName  ▾" else sequenceName,
                style = MaterialTheme.typography.caption1,
                color = Color.White.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
                modifier = if (canPick) Modifier.clickable(onClick = onPickSequence) else Modifier,
            )

            // Degraded-recovery prompt: gun was reconstructed best-effort, confirm against the flag.
            //
            // Scrimmed (#123). This is the one Tier 3 line that can be on screen during a *running*
            // race, so it is the one that meets the amber one-minute background — where the bare
            // amber text it used to be computed 2.93 : 1 against a 4.5 : 1 bar, on the screen a
            // sailor reads under stress. The scrim is Tier 1's, opaque, so the tier has one contrast
            // case rather than four; `MessageContrastTest` asserts it and asserts the old bare text
            // failing, so removing this reddens the suite.
            if (showResyncPrompt) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Recovered — tap Sync to confirm",
                    style = MaterialTheme.typography.caption2,
                    color = Color(TIER3_TEXT_ARGB),
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .background(Color(TIER3_SCRIM_ARGB), shape = RoundedCornerShape(6.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }

            // What Start is about to throw away (#89). The same Tier 3 surface as the prompt above,
            // and they cannot collide: this one only appears pre-start, while showResyncPrompt
            // requires a RUNNING engine.
            //
            // Deliberately un-scrimmed, and now measured rather than argued (#123). The amber
            // background only appears inside the final minute of a *running* race, and this line is
            // set only on the IDLE pre-start screen and cleared by `clearResumeOffer` the moment an
            // engine holds a race — so navy at 10.46 : 1 is the whole of its exposure. Rule 1 of
            // docs/message-surface.md permits bare text exactly where every reachable background has
            // been checked; `MessageContrastTest` is that check, and it derives the reachable set
            // from `backgroundArgbFor` rather than trusting this comment.
            if (discardWarning != null) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = discardWarning,
                    style = MaterialTheme.typography.caption2,
                    color = Color(TIER3_TEXT_ARGB),
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            // The big countdown / elapsed-time display
            CountdownText(
                remainingMs = remainingMs,
                elapsedMs = elapsedMs,
                state = state,
                previewElapsed = previewElapsed,
            )

            Spacer(modifier = Modifier.height(4.dp))

            // Sync flash label
            if (syncLabel != null) {
                Text(
                    text = syncLabel,
                    style = MaterialTheme.typography.caption1,
                    color = Color(0xFFFFD700),
                    textAlign = TextAlign.Center,
                )
            } else {
                Spacer(modifier = Modifier.height(16.dp))
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Buttons. The app never enters PAUSED (there is no pause control), but the engine
            // still models the state, so it shares the idle layout rather than going unhandled.
            when (state) {
                // Waiting to start: there is nothing yet to reset, so Start is the only control and
                // takes the whole width. FINISHED shares this because it is transient — the service
                // returns the engine to IDLE once the gun cue and its "GO!" linger are done, so the
                // sailor is never left on a finished screen with no way back.
                TimerState.IDLE, TimerState.FINISHED, TimerState.PAUSED -> {
                    // A race that outlived the process turns the one control into a question. The
                    // readout above is already showing that race's own clock, so both answers are
                    // legible before the tap: Resume continues the number on screen, Start over
                    // replaces it with the sequence's full duration.
                    //
                    // The lead-in control is deliberately absent from that state and only that one.
                    // Three controls do not fit this column: the row above already runs to roughly
                    // 160 dp of a 192 dp screen, so a third button in the Resume row leaves each
                    // about 53 dp and "Start over" no longer fits on its one line. The question the
                    // resume screen asks is *this race or a fresh one*, and Start over already
                    // re-runs whatever lead the saved race carried — a race manager wanting a
                    // different lead reaches it after Stop, which is a rare path inside a rare one.
                    if (resumeOffered) {
                        ResumeChoice(onResume = onStart, onStartOver = onStartOver)
                    } else if (leadInOffered) {
                        StartWithLeadIn(onStart = onStart, onLeadIn = onLeadIn)
                    } else {
                        StartButton(onClick = onStart)
                    }
                }
                TimerState.RUNNING -> {
                    // Sync has nothing to act on until the sequence proper is under way — see
                    // `isInLeadIn`. Rather than leave a button that takes the tap and does nothing,
                    // the lead-in gets the wide sole-control layout Start and End Race use, and Sync
                    // reappears on the same tick the sequence's own first signal fires.
                    if (inLeadIn) {
                        WideStopButton(onClick = onStop)
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SyncButton(onClick = onSync)
                            SecondaryButton(label = "Stop", onClick = onStop)
                        }
                    }
                }
                // Sync doesn't apply once the gun has fired — there is no committee flag left to
                // snap to — so this is the one control left, and it takes the wide layout the way
                // Start does rather than the small paired-button one RUNNING uses.
                TimerState.COUNTING_UP -> {
                    EndRaceButton(onClick = onEndRace)
                }
                // The final time is on screen to be read, not acted on — Done is the only control,
                // and deliberately not styled like End Race's alarm-red: there is nothing left to
                // warn about here, just a way to move on once the race committee is ready to.
                TimerState.RACE_ENDED -> {
                    DoneButton(onClick = onStop)
                }
            }
        }

        // Transient notice / warning banner (e.g. clock adjustment)
        if (message != null) {
            // The dwell is counted here, from the composition that puts the banner on screen, and
            // not by whoever posted the message (#102). A notice posted in `MainActivity.onCreate`
            // is posted about four and a half seconds before a cold launch paints anything, so a
            // timer started at the call site spent its whole life on a screen that did not exist.
            //
            // Keyed on the text: a different notice arriving restarts the three seconds, which is
            // what makes the two launch notices' "one message line, so one notice" rule survive
            // contact with a second message. The same text twice running does not restart it — it is
            // the same news, and re-arming would let a repeating condition pin the banner up.
            LaunchedEffect(message) {
                delay(MESSAGE_DURATION_MS)
                onMessageExpired()
            }
            MessageBanner(
                message = message,
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Countdown text with final-10 flash
// ---------------------------------------------------------------------------

@Composable
private fun CountdownText(
    remainingMs: Long,
    elapsedMs: Long,
    state: TimerState,
    previewElapsed: Boolean = false,
) {
    val isFinalTen = state == TimerState.RUNNING && remainingMs in 1..10_000L
    val isFinished = state == TimerState.FINISHED
    // Same elapsed-time display in all three: live while COUNTING_UP, frozen once RACE_ENDED
    // (elapsedMs itself carries that distinction — see the frozen-getter note on
    // TimerEngine.remainingMs), and live again while IDLE previewing a count-up race that survived a
    // process kill, which is running whether or not this app is.
    val showsElapsed = previewElapsed ||
        state == TimerState.COUNTING_UP || state == TimerState.RACE_ENDED

    val displayText = when {
        showsElapsed -> formatElapsed(elapsedMs)
        isFinished -> "GO!"
        remainingMs <= 0L && state == TimerState.RUNNING -> "GO!"
        else -> formatCountdown(remainingMs)
    }

    // Only the alpha differs between flashing and steady, so the branch decides that one value and
    // the countdown itself is written once.
    val alpha = if (isFinalTen) {
        val infiniteTransition = rememberInfiniteTransition(label = "flash")
        val flashAlpha by infiniteTransition.animateFloat(
            initialValue = 1f,
            targetValue = 0.3f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 400, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "flashAlpha",
        )
        flashAlpha
    } else {
        1f
    }

    // Smaller than the countdown's 52 sp: formatElapsed grows an extra "H:" group past an hour,
    // and sizing for that up front keeps the readout a constant size rather than shrinking the
    // instant a race crosses the hour mark.
    Text(
        text = displayText,
        fontSize = if (showsElapsed) 40.sp else 52.sp,
        fontWeight = FontWeight.Bold,
        color = Color.White.copy(alpha = alpha),
        textAlign = TextAlign.Center,
    )
}

// ---------------------------------------------------------------------------
// Button components
// ---------------------------------------------------------------------------

/**
 * The sole pre-start control: a wide pill rather than a bigger circle.
 *
 * The column above it (sequence name, 52 sp readout, sync-label slot) already fills most of a 192 dp
 * small round screen, so there is no vertical room to grow — this trades 8 dp of height for the width
 * Reset used to take, roughly doubling the tap area. Wear's [Button] defaults to a circle shape, which
 * at a non-square size renders as a stadium; the rounded ends also tuck inside the display's curve
 * better than square corners would at this width.
 */
@Composable
private fun StartButton(onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth(0.68f)
            .height(56.dp),
        colors = ButtonDefaults.buttonColors(
            backgroundColor = Color(0xFFFFD700),
            contentColor = Color(0xFF1A1A2E),
        ),
    ) {
        Text(
            text = "Start",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Resume / Start over, the pre-start screen's one two-answer state.
 *
 * Side by side rather than stacked because there is no vertical room: the column above already runs
 * to roughly 160 dp of a 192 dp screen, and a second full-width pill under the first would push the
 * readout off. Splitting one row is the only shape that fits without shrinking the readout, which is
 * the thing being asked about and must stay the largest element on screen.
 *
 * Resume keeps [StartButton]'s gold because it is the same action — arm the race that is already on
 * screen — while Start over takes the muted secondary colour. Neither is destructive by accident:
 * Start over is only ever reachable next to the number it would discard.
 */
@Composable
private fun ResumeChoice(onResume: () -> Unit, onStartOver: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(0.92f),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(
            onClick = onResume,
            modifier = Modifier
                .weight(1f)
                .height(52.dp),
            colors = ButtonDefaults.buttonColors(
                backgroundColor = Color(0xFFFFD700),
                contentColor = Color(0xFF1A1A2E),
            ),
        ) {
            Text(
                text = "Resume",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                textAlign = TextAlign.Center,
            )
        }
        Button(
            onClick = onStartOver,
            modifier = Modifier
                .weight(1f)
                .height(52.dp),
            colors = ButtonDefaults.buttonColors(
                backgroundColor = Color(0xFF555577),
                contentColor = Color.White,
            ),
        ) {
            Text(
                text = "Start over",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * Start, with the lead-in control beside it — the pre-start screen of a race-manager mode (#104).
 *
 * Side by side for the same reason as [ResumeChoice]: there is no vertical room for a second row, so
 * the only shape that fits without shrinking the readout is to split one.
 *
 * **Equal halves**, exactly as [ResumeChoice] splits its two. These are the two ways to begin a race
 * and neither is a lesser version of the other — a race manager on a committee boat with a signal box
 * reaches for the right-hand one every race of the day. Sizing the lead-in as a narrow afterthought
 * would make the more common of the two the harder to hit, on a wet round screen.
 *
 * Tapping Start here is byte-for-byte what it was: same anchor, same cues, same screen. The lead-in
 * is reached only through its own control.
 */
@Composable
private fun StartWithLeadIn(onStart: () -> Unit, onLeadIn: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(0.92f),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(
            onClick = onStart,
            modifier = Modifier
                .weight(1f)
                .height(52.dp),
            colors = ButtonDefaults.buttonColors(
                backgroundColor = Color(0xFFFFD700),
                contentColor = Color(0xFF1A1A2E),
            ),
        ) {
            Text(
                text = "Start",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                textAlign = TextAlign.Center,
            )
        }
        Button(
            onClick = onLeadIn,
            modifier = Modifier
                .weight(1f)
                .height(52.dp),
            colors = ButtonDefaults.buttonColors(
                backgroundColor = Color(0xFF64B5F6),
                contentColor = Color(0xFF1A1A2E),
            ),
        ) {
            Text(
                text = "Lead-in",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * Stop as the sole control, during a lead-in.
 *
 * Sized like [StartButton] and [EndRaceButton] for the same "only thing in the column" reason, and
 * keeping [SecondaryButton]'s muted colour rather than taking on End Race's alarm-red: it is the
 * same Stop it always was, just without a Sync button to share the row with.
 */
@Composable
private fun WideStopButton(onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth(0.68f)
            .height(56.dp),
        colors = ButtonDefaults.buttonColors(
            backgroundColor = Color(0xFF555577),
            contentColor = Color.White,
        ),
    ) {
        Text(
            text = "Stop",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * The sole control during [TimerState.COUNTING_UP], sized like [StartButton] for the same reason:
 * it is the only thing in the column, so it can take the wide layout the RUNNING screen's paired
 * Sync/Stop buttons can't.
 */
@Composable
private fun EndRaceButton(onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth(0.68f)
            .height(56.dp),
        colors = ButtonDefaults.buttonColors(
            backgroundColor = BG_FINAL_TEN,
            contentColor = Color.White,
        ),
    ) {
        Text(
            text = "End Race",
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * The sole control during [TimerState.RACE_ENDED], sized like [StartButton] and [EndRaceButton]
 * for the same "only thing in the column" reason. Uses [StartButton]'s yellow rather than a new
 * colour: tapping Done is a step back toward Start, not a warning, and the background is already
 * carrying the "this run is complete" signal (see [backgroundColorFor]).
 */
@Composable
private fun DoneButton(onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth(0.68f)
            .height(56.dp),
        colors = ButtonDefaults.buttonColors(
            backgroundColor = Color(0xFFFFD700),
            contentColor = Color(0xFF1A1A2E),
        ),
    ) {
        Text(
            text = "Done",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun SyncButton(onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.size(64.dp),
        colors = ButtonDefaults.buttonColors(
            backgroundColor = Color(0xFF64B5F6),
            contentColor = Color(0xFF1A1A2E),
        ),
    ) {
        Text(
            text = "Sync",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun SecondaryButton(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.size(56.dp),
        colors = ButtonDefaults.buttonColors(
            backgroundColor = Color(0xFF555577),
            contentColor = Color.White,
        ),
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            textAlign = TextAlign.Center,
        )
    }
}

// ---------------------------------------------------------------------------
// Message / warning banner
// ---------------------------------------------------------------------------

/**
 * The Tier 1 transient banner (see `docs/message-surface.md`).
 *
 * Positioned off the screen's own size rather than off fixed dp, because the constraint is the shape
 * of the display and not a margin — and placed **below the readout** rather than above it, which is
 * where it used to sit. The reasoning is in `shared/BannerLayout.kt`, along with
 * [bannerFitsRoundScreen], which asserts these fractions clear the circle at the banner's full
 * height budget.
 */
@Composable
private fun MessageBanner(message: String, modifier: Modifier = Modifier) {
    val configuration = LocalConfiguration.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = configuration.screenHeightDp.dp * BANNER_TOP_FRACTION),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = message,
            fontSize = 11.sp,
            color = Color(TIER1_TEXT_ARGB),
            textAlign = TextAlign.Center,
            modifier = Modifier
                // A cap ahead of the background, so the scrim stops where the text does and a short
                // notice keeps a band snug around itself instead of a fixed-width slab.
                .widthIn(max = configuration.screenWidthDp.dp * BANNER_MAX_WIDTH_FRACTION)
                // Opaque, where it used to be 80 %. At 80 % the background contributed a fifth of
                // the composite, which made the amber one-minute state the worst case in
                // docs/message-surface.md's contrast table at 6.55 : 1 — and that state is not a
                // hypothetical for this banner, since the clock-adjustment notice is the one Tier 1
                // consumer that fires mid-race. Contributing nothing collapses four cases to one at
                // 8.03 : 1, on the screen where legibility is the whole product. (This comment and
                // the doc both said 8.6 : 1 until #123 — that is this colour's ratio on the *Tier 3*
                // text, not on #FFB74D. `MessageContrastTest` now computes both.)
                .background(Color(TIER1_SCRIM_ARGB), shape = RoundedCornerShape(8.dp))
                .padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}
