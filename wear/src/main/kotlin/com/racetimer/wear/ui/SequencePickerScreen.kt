package com.racetimer.wear.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.racetimer.shared.BuiltInSequences
import com.racetimer.shared.RaceSequence
import com.racetimer.shared.LIST_ROW_ARGB

/**
 * Screen that lets the user pick a race sequence before starting.
 *
 * It briefly also carried a "Play cues on silent" toggle (#95), removed on owner directive
 * 2026-08-06: *the race timer must never not have audible cues.* The app is back to having no
 * settings at all, which is the right shape for it — a control whose only useful position is the
 * default is a way of asking the sailor to be responsible for something the app should simply do.
 *
 * @param onSequenceSelected  Called with the chosen [RaceSequence] when the user taps it.
 * @param onCustomSelected    Called when the user taps Custom, which has no duration yet — it needs
 *                            [CustomDurationScreen] before there is a sequence to select.
 */
@Composable
fun SequencePickerScreen(
    onSequenceSelected: (RaceSequence) -> Unit,
    onCustomSelected: () -> Unit = {},
) {
    ScalingLazyColumn(modifier = Modifier.fillMaxWidth()) {
        item {
            Text(
                text = "Select Sequence",
                style = MaterialTheme.typography.caption1,
                color = Color.White.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp, bottom = 8.dp),
            )
        }
        items(BuiltInSequences.all) { sequence ->
            SequenceChip(label = sequence.name, onClick = { onSequenceSelected(sequence) })
        }
        // Last, and the only entry that does not select anything on tap: every other chip names a
        // sequence that already exists, while this one has to be given a duration first. The
        // trailing "…" is what says so before the tap rather than after it.
        item {
            SequenceChip(label = "Custom…", onClick = onCustomSelected)
        }
    }
}

@Composable
private fun SequenceChip(label: String, onClick: () -> Unit) {
    Chip(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp),
        onClick = onClick,
        label = {
            Text(
                text = label,
                fontSize = 13.sp,
                color = Color.White,
            )
        },
        colors = ChipDefaults.chipColors(
            backgroundColor = Color(LIST_ROW_ARGB),
            contentColor = Color.White,
        ),
    )
}
