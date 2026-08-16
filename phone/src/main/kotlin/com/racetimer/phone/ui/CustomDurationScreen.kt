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
import com.racetimer.shared.BuiltInSequences
import com.racetimer.shared.DISABLED_BUTTON_ARGB
import com.racetimer.shared.ON_ACCENT_ARGB
import com.racetimer.shared.PRIMARY_ARGB
import com.racetimer.shared.formatCountdown

/** Where the stepper opens when nothing has been chosen or restored. The watch's default (#104). */
const val DEFAULT_CUSTOM_MINUTES = 5

/** The Set button, so a test can confirm the duration without matching on copy. */
const val TAG_SET_DURATION = "set_custom_duration"

/**
 * Sets the length of a Custom race, in whole minutes (#209).
 *
 * A stepper rather than a list, for the watch's reason: a custom race has no maximum, so a picker
 * would have to invent one to have something to scroll. Steps are one minute because the sequence is
 * *defined* in whole minutes — one long blast on each — so there is nothing finer to choose.
 *
 * The layout is this module's, not the watch's. A console phone is propped at arm's length and
 * watched, where a wrist is glanced at, so the duration is set large and the controls are wide rather
 * than compact — the same reasoning #199 and #225 used to give the phone display rules of its own
 * instead of inheriting the watch's. Confirming stays a separate control from stepping, which *is*
 * the watch's decision and transfers intact: a mis-tap should change a number, never arm a race of
 * the wrong length.
 *
 * (That sentence named the watch's shared display table until `ModuleBoundaryTest` refused the file
 * for saying so. The guard is right and the wording was wrong: its subject is a *textual reference*
 * — the criterion is that this module never **names** those rules — so prose that names them is
 * indistinguishable from code that reaches for them. cairn
 * `a-guard-that-reads-source-must-survive-its-own-docs`.)
 *
 * The accents come from `shared/Palette.kt`. This is the first phone screen to use one — #198 moved
 * them out of the watch and #197's rule (no colour literal in this module, asserted by
 * `ModuleBoundaryTest`) is what stops a matching copy appearing here instead.
 *
 * @param initialMinutes Where the stepper opens. Clamped to the minimum, so a caller passing a
 *                       restored value it did not validate cannot open below 1:00.
 * @param onConfirm      Called with the chosen whole-minute duration when Set is tapped.
 */
@Composable
fun CustomDurationScreen(
    initialMinutes: Int = DEFAULT_CUSTOM_MINUTES,
    onConfirm: (Int) -> Unit,
) {
    var minutes by remember(initialMinutes) {
        mutableIntStateOf(initialMinutes.coerceAtLeast(BuiltInSequences.CUSTOM_MIN_MINUTES))
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
            text = "Custom sequence",
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
            StepButton(
                glyph = "−", // true minus sign, not a hyphen: it reads as a pair with +
                enabled = minutes > BuiltInSequences.CUSTOM_MIN_MINUTES,
                onClick = { minutes-- },
            )
            Text(
                // The same MM:SS shape the picker and the readout use, from the same formatter, so
                // the length shown while dialling is the length shown once the race is chosen.
                text = formatCountdown(minutes * 60_000L),
                color = Color.White,
                fontSize = 56.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            StepButton(
                glyph = "+",
                enabled = true,
                onClick = { minutes++ },
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = { onConfirm(minutes) },
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(PRIMARY_ARGB),
                contentColor = Color(ON_ACCENT_ARGB),
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .testTag(TAG_SET_DURATION),
        ) {
            Text(text = "Set", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/**
 * One step of the duration, disabled at the floor rather than hidden.
 *
 * Hiding it would move the readout as the minimum is reached, and a control that jumps under the
 * thumb on a boat is worse than one that declines to act.
 */
@Composable
private fun StepButton(glyph: String, enabled: Boolean, onClick: () -> Unit) {
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
