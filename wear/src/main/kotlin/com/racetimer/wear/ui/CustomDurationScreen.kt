package com.racetimer.wear.ui

import androidx.compose.foundation.background
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.racetimer.shared.BuiltInSequences
import com.racetimer.shared.DISABLED_BUTTON_ARGB
import com.racetimer.shared.ON_ACCENT_ARGB
import com.racetimer.shared.PRIMARY_ARGB
import com.racetimer.shared.SECONDARY_ARGB

/**
 * Sets the length of a Custom race, in whole minutes.
 *
 * A stepper rather than a list of durations, because a custom race has no maximum — a picker would
 * have to invent one to have something to scroll. Steps are one minute: the sequence is defined in
 * whole minutes (one long blast on each), so there is nothing finer to choose.
 *
 * Sized for a wrist in sun on the same terms as the timer face (see #12): white on the app's navy
 * background at 30 sp for the duration itself, and 44 dp tap targets for the steps — the minimum
 * that survives a gloved, moving hand. Confirming is deliberately a separate control from stepping,
 * so a mis-tap changes a number rather than arming a race of the wrong length.
 *
 * @param initialMinutes Where the stepper opens. Clamped to the minimum, so a caller passing a
 *                       restored value it did not validate cannot open below 1:00.
 * @param onConfirm      Called with the chosen whole-minute duration when the user taps Set.
 */
@Composable
fun CustomDurationScreen(
    initialMinutes: Int = DEFAULT_CUSTOM_MINUTES,
    onConfirm: (Int) -> Unit,
) {
    var minutes by remember {
        mutableStateOf(initialMinutes.coerceAtLeast(BuiltInSequences.CUSTOM_MIN_MINUTES))
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colors.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = 6.dp),
        ) {
            Text(
                text = "Custom",
                style = MaterialTheme.typography.caption1,
                color = Color.White.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StepButton(
                    glyph = "−", // true minus sign, not a hyphen: it reads as a pair with +
                    enabled = minutes > BuiltInSequences.CUSTOM_MIN_MINUTES,
                    onClick = { minutes-- },
                )
                DurationReadout(
                    minutes = minutes,
                    modifier = Modifier.weight(1f),
                )
                StepButton(
                    glyph = "+",
                    enabled = true,
                    onClick = { minutes++ },
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = { onConfirm(minutes) },
                modifier = Modifier
                    .fillMaxWidth(0.6f)
                    .height(48.dp),
                colors = ButtonDefaults.buttonColors(
                    backgroundColor = Color(PRIMARY_ARGB),
                    contentColor = Color(ON_ACCENT_ARGB),
                ),
            ) {
                Text(
                    text = "Set",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/**
 * The duration as the countdown will show it — `M:00`, not a bare minute count.
 *
 * Shrinks past four characters rather than wrapping or clipping: the steps have no maximum, so a
 * long enough race reaches three digits, and the two 44 dp step buttons flanking this leave a fixed
 * width to fit it in.
 */
@Composable
private fun DurationReadout(minutes: Int, modifier: Modifier = Modifier) {
    val label = "$minutes:00"
    Text(
        text = label,
        fontSize = when {
            label.length <= 4 -> 30.sp
            label.length == 5 -> 26.sp
            else -> 20.sp
        },
        fontWeight = FontWeight.Bold,
        color = Color.White,
        textAlign = TextAlign.Center,
        maxLines = 1,
        modifier = modifier,
    )
}

/**
 * One step of the duration.
 *
 * Disabled rather than clamping silently at the minimum: a control that accepts the tap and does
 * nothing reads as a broken button. The disabled colours stay legible instead of dropping to the
 * near-invisible default — this is a watch in sun, and "greyed out" still has to be readable enough
 * that the sailor can see *which* button stopped.
 */
@Composable
private fun StepButton(glyph: String, enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(44.dp),
        colors = ButtonDefaults.buttonColors(
            backgroundColor = Color(SECONDARY_ARGB),
            contentColor = Color(ON_ACCENT_ARGB),
            disabledBackgroundColor = Color(DISABLED_BUTTON_ARGB),
            disabledContentColor = Color.White.copy(alpha = 0.5f),
        ),
    ) {
        Text(
            text = glyph,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Where the stepper opens when there is no earlier choice to reopen at.
 *
 * Five minutes, matching the US Sailing sequence — the longest built-in, so the common reason to
 * reach for Custom at all (a race run to a length the built-ins do not cover) starts from a familiar
 * number rather than from the 1:00 minimum.
 */
const val DEFAULT_CUSTOM_MINUTES = 5
