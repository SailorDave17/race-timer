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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.racetimer.shared.TimerState
import com.racetimer.shared.formatCountdown
import com.racetimer.shared.formatElapsed

// ---------------------------------------------------------------------------
// Background colour states
// ---------------------------------------------------------------------------

private val BG_NORMAL = Color(0xFF1A1A2E)      // deep navy — calm
private val BG_ONE_MINUTE = Color(0xFFA0660A)  // amber — inside 1 minute
private val BG_FINAL_TEN = Color(0xFF7B0000)   // dark red — final 10 s
private val BG_FINISHED = Color(0xFF005000)    // dark green — gun fired

/** Pick the background colour for the given [remainingMs] and [state]. */
private fun backgroundColorFor(remainingMs: Long, state: TimerState): Color = when {
    // Both read as "this run is complete" — RACE_ENDED is a race committee's equivalent of the
    // gun having fired, just held open for review instead of lingering for a few seconds.
    state == TimerState.FINISHED || state == TimerState.RACE_ENDED -> BG_FINISHED
    state != TimerState.RUNNING  -> BG_NORMAL
    remainingMs <= 10_000L       -> BG_FINAL_TEN
    remainingMs <= 60_000L       -> BG_ONE_MINUTE
    else                         -> BG_NORMAL
}

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
 * @param resumeOffered  True when a race survived a process kill and [remainingMs] is *that* race's
 *                       clock rather than the sequence's full duration: Start becomes a choice
 *                       between resuming it and running the sequence from the top.
 * @param previewElapsed True when the offered race is already past its gun (a race-manager count-up),
 *                       so the readout shows [elapsedMs] instead of a countdown to a gun that fired.
 * @param onStart        Called when the user taps Start, or Resume when [resumeOffered].
 * @param onStartOver    Called when the user taps Start over. Only reachable when [resumeOffered].
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
    resumeOffered: Boolean = false,
    previewElapsed: Boolean = false,
    onStart: () -> Unit,
    onStartOver: () -> Unit = {},
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
            if (showResyncPrompt) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Recovered — tap Sync to confirm",
                    style = MaterialTheme.typography.caption2,
                    color = Color(0xFFFFC107),
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
                    if (resumeOffered) {
                        ResumeChoice(onResume = onStart, onStartOver = onStartOver)
                    } else {
                        StartButton(onClick = onStart)
                    }
                }
                TimerState.RUNNING -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SyncButton(onClick = onSync)
                        SecondaryButton(label = "Stop", onClick = onStop)
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

@Composable
private fun MessageBanner(message: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 2.dp, start = 12.dp, end = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = message,
            fontSize = 11.sp,
            color = Color(0xFFFFB74D),
            textAlign = TextAlign.Center,
            modifier = Modifier
                .background(Color(0xCC3A2A00), shape = RoundedCornerShape(8.dp))
                .padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}
