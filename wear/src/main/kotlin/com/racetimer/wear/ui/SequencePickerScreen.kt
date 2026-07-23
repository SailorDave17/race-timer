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

/**
 * Screen that lets the user pick a built-in race sequence before starting.
 *
 * @param onSequenceSelected  Called with the chosen [RaceSequence] when the user taps it.
 */
@Composable
fun SequencePickerScreen(
    onSequenceSelected: (RaceSequence) -> Unit,
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
            SequenceChip(sequence = sequence, onClick = { onSequenceSelected(sequence) })
        }
    }
}

@Composable
private fun SequenceChip(sequence: RaceSequence, onClick: () -> Unit) {
    Chip(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp),
        onClick = onClick,
        label = {
            Text(
                text = sequence.name,
                fontSize = 13.sp,
                color = Color.White,
            )
        },
        colors = ChipDefaults.chipColors(
            backgroundColor = Color(0xFF2A2A50),
            contentColor = Color.White,
        ),
    )
}
