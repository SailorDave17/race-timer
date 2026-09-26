package com.racetimer.phone.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.racetimer.shared.BG_NORMAL_ARGB
import com.racetimer.shared.BOX_ALERT_NONE
import com.racetimer.shared.BOX_ALERT_PRESET_SECONDS
import com.racetimer.shared.ON_ACCENT_ARGB
import com.racetimer.shared.PRIMARY_ARGB
import com.racetimer.shared.leadInSecondsFor

/** The Custom entry, so a test can reach it without matching on copy. */
const val TAG_BOX_ALERT_CUSTOM = "box_alert_custom"

/** The entry for one preset alert, by its value — `box_alert_60` for the Rule 26 row. */
fun boxAlertTag(seconds: Int): String = "box_alert_$seconds"

/**
 * Choose the signal box's alert setting, then start (#207).
 *
 * The watch's screen on a console: it **asks for the box's alert, not a total lead**, because the
 * alert is the fact the race manager actually holds — it is printed on the mode chart on the back
 * of their unit — whereas a total is something they would have to compute from it. The app adds its
 * own prep stage on top; see `LeadIn.kt`, which owns every number here.
 *
 * Two taps to arm a race rather than one, deliberately, and for the watch's reason: a single
 * control showing the current setting that starts on one tap makes "the alert is not what you think
 * it is" an invisible state, and the cost of that is a race started against a box the phone is not
 * aligned with. Every entry names **both** numbers — the alert it is naming and the lead that alert
 * produces — because showing only one makes the other a surprise, and the showing is the whole
 * guard.
 *
 * The layout is this module's, not the watch's, as [CustomDurationScreen]'s is: a console phone is
 * propped at arm's length and watched, so the entries are the full-width rows [SequencePickerScreen]
 * uses rather than a scaling column of chips. What transfers intact is the *decision* structure.
 *
 * @param lastUsedSeconds  The alert this screen opens marked. A club runs one box, so the common
 *                         case is a confirm: its setting is already the highlighted row.
 * @param onAlertSelected  Called with a whole-second alert window. Starts the race; there is no
 *                         intermediate confirm, because the row that was tapped was the confirm.
 * @param onCustomSelected Called when the officer taps Custom, which has no value yet — it needs
 *                         [LeadInDurationScreen] before there is an alert to start with.
 */
@Composable
fun LeadInPickerScreen(
    lastUsedSeconds: Int,
    onAlertSelected: (Int) -> Unit,
    onCustomSelected: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(BG_NORMAL_ARGB))
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Box alert",
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 18.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
        )

        BOX_ALERT_PRESET_SECONDS.forEach { seconds ->
            AlertRow(
                // "Off" rather than "0 s": the race manager is answering a question about their
                // box, and a box either sounds an alert or it does not.
                label = if (seconds == BOX_ALERT_NONE) "Off" else "$seconds s",
                detail = "${leadInSecondsFor(seconds)} s lead",
                current = seconds == lastUsedSeconds,
                tag = boxAlertTag(seconds),
                onClick = { onAlertSelected(seconds) },
            )
        }

        // Last, and the only entry that does not start anything on tap: every other row names an
        // alert that already exists, while this one has to be dialled first. The trailing "…" says
        // so before the tap rather than after it, matching the sequence picker's Custom row.
        //
        // When the last-used alert is not one of the presets it *is* this row, so it carries the
        // number — otherwise "opens on the last-used value" would silently lose a dialled-in alert.
        val custom = lastUsedSeconds !in BOX_ALERT_PRESET_SECONDS
        AlertRow(
            label = if (custom) "Custom… ($lastUsedSeconds s)" else "Custom…",
            detail = if (custom) "${leadInSecondsFor(lastUsedSeconds)} s lead" else "Dial the box's alert",
            current = custom,
            tag = TAG_BOX_ALERT_CUSTOM,
            onClick = onCustomSelected,
        )
    }
}

/**
 * One box-alert setting, and the lead it produces.
 *
 * [current] marks the last-used value in the app's Start gold rather than by a tick or a border,
 * for the watch's reason: it is the row the race manager is expected to tap, and on a console in
 * sun a colour carries further than a glyph. The `selected` semantic is what a test reads; the
 * colour is what an officer does.
 */
@Composable
private fun AlertRow(
    label: String,
    detail: String,
    current: Boolean,
    tag: String,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (current) Color(PRIMARY_ARGB) else Color.White.copy(alpha = 0.14f),
            contentColor = if (current) Color(ON_ACCENT_ARGB) else Color.White,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .testTag(tag)
            .semantics { selected = current },
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(vertical = 12.dp),
        ) {
            Text(
                text = label,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Text(
                // What the alert above actually produces — the number about to count down.
                text = detail,
                // Against gold the secondary line darkens rather than lightens, or the "how long
                // will this actually run" number is the least legible thing on a sunlit screen.
                color = if (current) Color(ON_ACCENT_ARGB).copy(alpha = 0.75f) else Color.White.copy(alpha = 0.7f),
                fontSize = 15.sp,
            )
        }
    }
}
