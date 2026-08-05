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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Switch
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.ToggleChip
import androidx.wear.compose.material.ToggleChipDefaults
import com.racetimer.shared.BuiltInSequences
import com.racetimer.shared.RaceSequence

/**
 * Screen that lets the user pick a race sequence before starting.
 *
 * Also carries the one setting the app has (#95). That is not tidy, and the alternative was worse: a
 * dedicated settings screen needs an entry point, and the timer face has none to spare — it already
 * records that three controls do not fit its column. Putting it here costs no new route and no new
 * tap, since this screen is already the pre-start "choices" surface and is already scrollable.
 *
 * @param onSequenceSelected  Called with the chosen [RaceSequence] when the user taps it.
 * @param onCustomSelected    Called when the user taps Custom, which has no duration yet — it needs
 *                            [CustomDurationScreen] before there is a sequence to select.
 * @param audibleInSilentMode Current value of the reroute setting, for the toggle's state.
 * @param onAudibleInSilentModeChange Called with the new value when the sailor flips it. Unlike every
 *                            other control on this screen it does **not** navigate away — changing a
 *                            setting is not choosing a sequence, and popping the screen would make it
 *                            impossible to change one's mind without coming back.
 */
@Composable
fun SequencePickerScreen(
    onSequenceSelected: (RaceSequence) -> Unit,
    onCustomSelected: () -> Unit = {},
    audibleInSilentMode: Boolean = true,
    onAudibleInSilentModeChange: (Boolean) -> Unit = {},
) {
    ScalingLazyColumn(modifier = Modifier.fillMaxWidth()) {
        item {
            SectionCaption(text = "Select Sequence", topPadding = 16.dp)
        }
        items(BuiltInSequences.all) { sequence ->
            SequenceChip(label = sequence.name, onClick = { onSequenceSelected(sequence) })
        }
        // Last of the sequences, and the only entry that does not select anything on tap: every other
        // chip names a sequence that already exists, while this one has to be given a duration first.
        // The trailing "…" is what says so before the tap rather than after it.
        item {
            SequenceChip(label = "Custom…", onClick = onCustomSelected)
        }
        item {
            SectionCaption(text = "Sound", topPadding = 20.dp)
        }
        item {
            // Labelled by what it does for the sailor, not by the mechanism. "Route cues to the media
            // stream" is what the code does; "Play cues on silent" is the promise being made, and the
            // sailor is standing on a boat.
            ToggleChip(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 2.dp),
                checked = audibleInSilentMode,
                onCheckedChange = onAudibleInSilentModeChange,
                label = {
                    Text(text = "Play cues on silent", fontSize = 13.sp, color = Color.White)
                },
                toggleControl = {
                    Switch(
                        checked = audibleInSilentMode,
                        modifier = Modifier.semantics {
                            contentDescription = if (audibleInSilentMode) "On" else "Off"
                        },
                    )
                },
                colors = ToggleChipDefaults.toggleChipColors(
                    checkedStartBackgroundColor = Color(0xFF2A2A50),
                    checkedEndBackgroundColor = Color(0xFF2A2A50),
                    uncheckedStartBackgroundColor = Color(0xFF2A2A50),
                    uncheckedEndBackgroundColor = Color(0xFF2A2A50),
                    checkedContentColor = Color.White,
                    uncheckedContentColor = Color.White,
                ),
            )
        }
    }
}

@Composable
private fun SectionCaption(text: String, topPadding: Dp) {
    Text(
        text = text,
        style = MaterialTheme.typography.caption1,
        color = Color.White.copy(alpha = 0.7f),
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = topPadding, bottom = 8.dp),
    )
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
            backgroundColor = Color(0xFF2A2A50),
            contentColor = Color.White,
        ),
    )
}
