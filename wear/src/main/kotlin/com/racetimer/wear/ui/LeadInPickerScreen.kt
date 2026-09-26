package com.racetimer.wear.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.racetimer.shared.BOX_ALERT_NONE
import com.racetimer.shared.BOX_ALERT_PRESET_SECONDS
import com.racetimer.shared.leadInSecondsFor
import com.racetimer.shared.LIST_ROW_ARGB
import com.racetimer.shared.ON_ACCENT_ARGB
import com.racetimer.shared.PRIMARY_ARGB

/**
 * Choose the signal box's alert setting, then start.
 *
 * **Asks for the box's alert, not a total lead.** The alert is the fact the race manager actually
 * holds — it is printed on the mode chart on the back of their unit — whereas a total is something
 * they would have to compute from it. The app adds its own prep stage on top; see `LeadIn.kt`.
 *
 * Two taps to arm a race rather than one, deliberately. The faster alternative — a single control
 * showing the current setting that starts on one tap — makes "the alert is not what you think it is"
 * an invisible state, and the cost of that is a race started against a box the watch is not aligned
 * with. Every entry names **both** numbers: the alert it is naming, and the lead that alert
 * produces. Showing only one makes the other a surprise, and the showing is the whole guard.
 *
 * Opening on the last-used value keeps the common case to a confirm: a club that always runs the
 * same box finds its setting already marked and taps it.
 *
 * @param lastUsedSeconds  The alert window this screen opens on — marked, and scrolled to.
 * @param onAlertSelected  Called with a whole-second alert window. Starts the race; there is no
 *                         intermediate confirm, because the chip that was tapped was the confirm.
 * @param onCustomSelected Called when the user taps Custom, which has no value yet — it needs
 *                         [LeadInDurationScreen] before there is an alert to start with.
 */
@Composable
fun LeadInPickerScreen(
    lastUsedSeconds: Int,
    onAlertSelected: (Int) -> Unit,
    onCustomSelected: () -> Unit = {},
) {
    // The header occupies index 0, so a preset at position i centres at i + 1. A last-used value
    // that is not a preset is the Custom row, which is last.
    val presetIndex = BOX_ALERT_PRESET_SECONDS.indexOf(lastUsedSeconds)
    val initialIndex = if (presetIndex >= 0) presetIndex + 1 else BOX_ALERT_PRESET_SECONDS.size + 1

    ScalingLazyColumn(
        modifier = Modifier.fillMaxWidth(),
        state = rememberScalingLazyListState(initialCenterItemIndex = initialIndex),
    ) {
        item {
            Text(
                text = "Box alert",
                style = MaterialTheme.typography.caption1,
                color = Color.White.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp, bottom = 8.dp),
            )
        }
        items(BOX_ALERT_PRESET_SECONDS) { seconds ->
            AlertChip(
                // "Off" rather than "0 s": the race manager is answering a question about their box,
                // and a box either sounds an alert or it does not.
                label = if (seconds == BOX_ALERT_NONE) "Off" else "$seconds s",
                detail = "${leadInSecondsFor(seconds)} s lead",
                current = seconds == lastUsedSeconds,
                onClick = { onAlertSelected(seconds) },
            )
        }
        // Last, and the only entry that does not start anything on tap: every other chip names an
        // alert that already exists, while this one has to be dialled first. The trailing "…" says
        // so before the tap rather than after it, matching SequencePickerScreen's Custom row.
        //
        // When the last-used alert is not one of the presets it *is* this row, so it carries the
        // number — otherwise "opens on the last-used value" would silently lose a dialled-in alert.
        item {
            val custom = lastUsedSeconds !in BOX_ALERT_PRESET_SECONDS
            AlertChip(
                label = if (custom) "Custom… ($lastUsedSeconds s)" else "Custom…",
                detail = if (custom) "${leadInSecondsFor(lastUsedSeconds)} s lead" else null,
                current = custom,
                onClick = onCustomSelected,
            )
        }
    }
}

/**
 * One box-alert setting, and the lead it produces.
 *
 * **Centred, not start-aligned.** Wear's [Chip] lays its label out from the start edge, which is
 * right for a list of names of different lengths — [SequencePickerScreen]'s sequences read as a
 * left-hand column you scan down. These are not names, they are *values*, and short ones: an "Off"
 * or a "15 s" pinned to the left edge of a full-width pill sits away from where the eye lands on a
 * round screen, and away from the centre line the readout and every other control in this app use.
 *
 * [current] marks the last-used value in the app's Start gold rather than by a tick or a border: it
 * is the entry the race manager is expected to tap, and on a round screen in sun a colour carries
 * further than a glyph.
 */
@Composable
private fun AlertChip(
    label: String,
    detail: String?,
    current: Boolean,
    onClick: () -> Unit,
) {
    Chip(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 3.dp)
            // Taller than ChipDefaults.Height, which sizes for a 15 sp label: these are values read
            // at arm's length on a committee boat, so the type is set for that and the pill grows to
            // hold it rather than the type shrinking to fit the pill.
            .height(64.dp),
        onClick = onClick,
        label = {
            Text(
                text = label,
                fontSize = 22.sp,
                fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
                color = if (current) Color(ON_ACCENT_ARGB) else Color.White,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        secondaryLabel = detail?.let {
            {
                Text(
                    text = it,
                    fontSize = 14.sp,
                    // Against gold the secondary line has to darken rather than lighten, or the
                    // "how long will this actually run" number is the least legible thing on a
                    // sunlit screen.
                    color = if (current) {
                        Color(ON_ACCENT_ARGB).copy(alpha = 0.75f)
                    } else {
                        Color.White.copy(alpha = 0.7f)
                    },
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        colors = ChipDefaults.chipColors(
            backgroundColor = if (current) Color(PRIMARY_ARGB) else Color(LIST_ROW_ARGB),
            contentColor = if (current) Color(ON_ACCENT_ARGB) else Color.White,
        ),
    )
}
