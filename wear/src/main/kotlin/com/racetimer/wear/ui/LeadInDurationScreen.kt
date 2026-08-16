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
import com.racetimer.shared.BOX_ALERT_MAX_SECONDS
import com.racetimer.shared.BOX_ALERT_MIN_SECONDS
import com.racetimer.shared.leadInSecondsFor
import com.racetimer.shared.DISABLED_BUTTON_ARGB
import com.racetimer.shared.ON_ACCENT_ARGB
import com.racetimer.shared.PRIMARY_ARGB
import com.racetimer.shared.SECONDARY_ARGB

/**
 * Sets a lead-in the presets do not cover, in whole seconds.
 *
 * Deliberately [CustomDurationScreen]'s screen with a different unit rather than a second pattern:
 * the two are the same job — a bounded whole-number stepper with a separate confirm — and a race
 * manager who has dialled a Custom race duration should find nothing new to learn here. Confirming
 * stays a control of its own, so a mis-tap changes a number rather than arming a race against the
 * wrong lead.
 *
 * Whole seconds because box warnings are stated in whole seconds, and both ends are bounded because
 * an accidentally large lead is harder to spot on a round screen than an accidentally small one —
 * the `+` stops at [BOX_ALERT_MAX_SECONDS] rather than running on the way the Custom duration's does.
 *
 * @param initialSeconds Where the stepper opens. Coerced into range, so a caller passing a restored
 *                       value it did not validate cannot open outside the bounds.
 * @param onConfirm      Called with the chosen whole-second lead when the user taps Set.
 */
@Composable
fun LeadInDurationScreen(
    initialSeconds: Int,
    onConfirm: (Int) -> Unit,
) {
    var seconds by remember {
        mutableStateOf(initialSeconds.coerceIn(BOX_ALERT_MIN_SECONDS, BOX_ALERT_MAX_SECONDS))
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
                text = "Box alert",
                style = MaterialTheme.typography.caption1,
                color = Color.White.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LeadStepButton(
                    glyph = "−", // true minus sign, not a hyphen: it reads as a pair with +
                    enabled = seconds > BOX_ALERT_MIN_SECONDS,
                    onClick = { seconds-- },
                )
                LeadReadout(
                    seconds = seconds,
                    modifier = Modifier.weight(1f),
                )
                LeadStepButton(
                    glyph = "+",
                    enabled = seconds < BOX_ALERT_MAX_SECONDS,
                    onClick = { seconds++ },
                )
            }

            // What the alert above actually produces. The stepper asks for the box's setting, so
            // without this the race manager would have to add the prep stage in their head to know
            // what they are about to watch count down — and this screen exists precisely so a value
            // is confirmed rather than guessed.
            Text(
                text = "${leadInSecondsFor(seconds)} s lead",
                style = MaterialTheme.typography.caption2,
                color = Color.White.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = { onConfirm(seconds) },
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
 * The alert being dialled — `N s`, the same unit the presets use.
 *
 * Seconds throughout rather than switching to `M:SS` past a minute: 60 s is one preset among three
 * and reading it as "1:00" beside "15 s" and "Off" would make the race manager convert in their head
 * to compare, at the one moment they are not looking at the watch for long. It is also the unit the
 * box's own alert is quoted in.
 *
 * Sized to fill the space the two step buttons leave rather than to a fixed point size: this is the
 * value the whole screen exists to set, it is being read at arm's length in sun, and the widest it
 * ever renders is four characters (`120 s`). It steps down once past three digits so the number can
 * never collide with the buttons flanking it.
 */
@Composable
private fun LeadReadout(seconds: Int, modifier: Modifier = Modifier) {
    val label = "$seconds s"
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Text(
            text = label,
            fontSize = if (label.length <= 4) 44.sp else 38.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

/**
 * One second of lead.
 *
 * Disabled rather than clamping silently at either bound, matching [CustomDurationScreen]: a control
 * that accepts the tap and does nothing reads as a broken button, and the disabled colours stay
 * legible so the sailor can see *which* end they have reached.
 */
@Composable
private fun LeadStepButton(glyph: String, enabled: Boolean, onClick: () -> Unit) {
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
