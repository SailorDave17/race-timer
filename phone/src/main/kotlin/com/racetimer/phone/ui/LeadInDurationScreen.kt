package com.racetimer.phone.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.racetimer.shared.BG_NORMAL_ARGB
import com.racetimer.shared.BOX_ALERT_MAX_SECONDS
import com.racetimer.shared.BOX_ALERT_MIN_SECONDS
import com.racetimer.shared.DISABLED_BUTTON_ARGB
import com.racetimer.shared.ON_ACCENT_ARGB
import com.racetimer.shared.PRIMARY_ARGB
import com.racetimer.shared.leadInSecondsFor

/** The Set button, so a test can confirm the alert without matching on copy. */
const val TAG_SET_BOX_ALERT = "set_box_alert"

/**
 * Sets a box alert the presets do not cover, in whole seconds (#207).
 *
 * Deliberately [CustomDurationScreen]'s screen with a different unit rather than a second pattern,
 * which is the watch's decision and transfers intact: the two are the same job — a bounded
 * whole-number stepper with a separate confirm — and an officer who has dialled a Custom race
 * duration should find nothing new to learn here. Confirming stays a control of its own, so a
 * mis-tap changes a number rather than arming a race against the wrong lead.
 *
 * Whole seconds because box alerts are stated in whole seconds, and both ends are bounded because
 * an accidentally large lead is harder to spot than an accidentally small one — the `+` stops at
 * [BOX_ALERT_MAX_SECONDS] rather than running on the way the Custom duration's does. The bounds are
 * `LeadIn.kt`'s, not restated.
 *
 * @param initialSeconds Where the stepper opens. Coerced into range, so a caller passing a stored
 *                       value it did not validate cannot open outside the bounds.
 * @param onConfirm      Called with the chosen whole-second alert when Set is tapped.
 */
@Composable
fun LeadInDurationScreen(
    initialSeconds: Int,
    onConfirm: (Int) -> Unit,
) {
    var seconds by remember(initialSeconds) {
        mutableIntStateOf(initialSeconds.coerceIn(BOX_ALERT_MIN_SECONDS, BOX_ALERT_MAX_SECONDS))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(BG_NORMAL_ARGB))
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "Box alert",
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 18.sp,
            textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.height(24.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AlertStepButton(
                glyph = "−", // true minus sign, not a hyphen: it reads as a pair with +
                enabled = seconds > BOX_ALERT_MIN_SECONDS,
                onClick = { seconds-- },
            )
            Text(
                // Seconds throughout rather than switching to M:SS past a minute: 60 s is one
                // preset among three, and it is the unit the box's own alert is quoted in.
                text = "$seconds s",
                color = Color.White,
                fontSize = 56.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            AlertStepButton(
                glyph = "+",
                enabled = seconds < BOX_ALERT_MAX_SECONDS,
                onClick = { seconds++ },
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // What the alert above actually produces. The stepper asks for the box's setting, so
        // without this the officer would have to add the prep stage in their head to know what
        // they are about to watch count down — and this screen exists precisely so a value is
        // confirmed rather than guessed.
        Text(
            text = "${leadInSecondsFor(seconds)} s lead",
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 18.sp,
            textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = { onConfirm(seconds) },
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(PRIMARY_ARGB),
                contentColor = Color(ON_ACCENT_ARGB),
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .testTag(TAG_SET_BOX_ALERT),
        ) {
            Text(text = "Set", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/**
 * One second of alert, disabled at either bound rather than clamping silently.
 *
 * Matching [CustomDurationScreen]: a control that accepts the tap and does nothing reads as a
 * broken button, and the disabled colours stay legible so the officer can see *which* end they
 * have reached.
 */
@Composable
private fun AlertStepButton(glyph: String, enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = Color(PRIMARY_ARGB),
            contentColor = Color(ON_ACCENT_ARGB),
            disabledContainerColor = Color(DISABLED_BUTTON_ARGB),
            disabledContentColor = Color.White.copy(alpha = 0.5f),
        ),
        modifier = Modifier.size(88.dp),
    ) {
        Text(text = glyph, fontSize = 34.sp, fontWeight = FontWeight.Bold)
    }
}
