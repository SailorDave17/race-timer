package com.racetimer.wear.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
 * @param state          Current [TimerState] of the engine.
 * @param sequenceName   Name of the loaded sequence shown as a small label.
 * @param syncLabel      Non-null for ~2 s after a sync to flash "Synced → X:XX".
 * @param onStart        Called when the user taps Start or Resume.
 * @param onStop         Called when the user taps Stop.
 * @param onReset        Called when the user taps Reset.
 * @param onSync         Called when the user taps Sync.
 */
@Composable
fun TimerScreen(
    remainingMs: Long,
    state: TimerState,
    sequenceName: String,
    syncLabel: String?,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onReset: () -> Unit,
    onSync: () -> Unit,
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

            // Sequence name label
            Text(
                text = sequenceName,
                style = MaterialTheme.typography.caption1,
                color = Color.White.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(4.dp))

            // The big countdown display
            CountdownText(remainingMs = remainingMs, state = state)

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

            // Buttons
            when (state) {
                TimerState.IDLE, TimerState.FINISHED -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PrimaryButton(label = "Start", onClick = onStart)
                        SecondaryButton(label = "Reset", onClick = onReset)
                    }
                }
                TimerState.RUNNING -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SyncButton(onClick = onSync)
                        SecondaryButton(label = "Stop", onClick = onStop)
                    }
                }
                TimerState.PAUSED -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PrimaryButton(label = "Resume", onClick = onStart)
                        SecondaryButton(label = "Reset", onClick = onReset)
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Countdown text with final-10 flash
// ---------------------------------------------------------------------------

@Composable
private fun CountdownText(remainingMs: Long, state: TimerState) {
    val isFinalTen = state == TimerState.RUNNING && remainingMs in 1..10_000L
    val isFinished = state == TimerState.FINISHED

    val displayText = when {
        isFinished -> "GO!"
        remainingMs <= 0L && state == TimerState.RUNNING -> "GO!"
        else -> formatMmSs(remainingMs)
    }

    if (isFinalTen) {
        // Flashing effect in final 10 seconds
        val infiniteTransition = rememberInfiniteTransition(label = "flash")
        val alpha by infiniteTransition.animateFloat(
            initialValue = 1f,
            targetValue = 0.3f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 400, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "flashAlpha",
        )
        Text(
            text = displayText,
            fontSize = 52.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White.copy(alpha = alpha),
            textAlign = TextAlign.Center,
        )
    } else {
        Text(
            text = displayText,
            fontSize = 52.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            textAlign = TextAlign.Center,
        )
    }
}

// ---------------------------------------------------------------------------
// Button components
// ---------------------------------------------------------------------------

@Composable
private fun PrimaryButton(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.size(64.dp),
        colors = ButtonDefaults.buttonColors(
            backgroundColor = Color(0xFFFFD700),
            contentColor = Color(0xFF1A1A2E),
        ),
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
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
// Formatting helper
// ---------------------------------------------------------------------------

internal fun formatMmSs(ms: Long): String {
    val totalSec = (ms / 1_000L).coerceAtLeast(0L)
    val min = totalSec / 60
    val sec = totalSec % 60
    return "%d:%02d".format(min, sec)
}
