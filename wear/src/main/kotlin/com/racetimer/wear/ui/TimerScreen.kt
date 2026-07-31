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
    state == TimerState.FINISHED -> BG_FINISHED
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
 * @param elapsedMs      Milliseconds since the gun, meaningful only in [TimerState.COUNTING_UP]
 *                       (a race-manager sequence's elapsed race time; ignored otherwise).
 * @param state          Current [TimerState] of the engine.
 * @param sequenceName   Name of the loaded sequence shown as a small label.
 * @param syncLabel      Non-null for ~2 s after a sync to flash "Synced → X:XX".
 * @param showResyncPrompt True after a degraded recovery (reboot / clock step): the restored gun
 *                       is best-effort, so prompt the sailor to tap Sync against the RC flag.
 * @param message        Non-null to show a transient notice/warning banner (e.g. clock jump).
 * @param onStart        Called when the user taps Start.
 * @param onStop         Called when the user taps Stop, or End Race in [TimerState.COUNTING_UP].
 * @param onSync         Called when the user taps Sync.
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
    onStart: () -> Unit,
    onStop: () -> Unit,
    onSync: () -> Unit,
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

            // Sequence name label — tappable to change the sequence when not running (or counting
            // up: a race-manager race in progress has just as little business swapping sequences
            // mid-race as a countdown does).
            val canPick = state != TimerState.RUNNING && state != TimerState.COUNTING_UP
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
            CountdownText(remainingMs = remainingMs, elapsedMs = elapsedMs, state = state)

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
                    StartButton(onClick = onStart)
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
                    EndRaceButton(onClick = onStop)
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
private fun CountdownText(remainingMs: Long, elapsedMs: Long, state: TimerState) {
    val isFinalTen = state == TimerState.RUNNING && remainingMs in 1..10_000L
    val isFinished = state == TimerState.FINISHED
    val isCountingUp = state == TimerState.COUNTING_UP

    val displayText = when {
        isCountingUp -> formatElapsed(elapsedMs)
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
        fontSize = if (isCountingUp) 40.sp else 52.sp,
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
