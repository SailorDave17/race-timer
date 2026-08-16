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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.racetimer.shared.BG_NORMAL_ARGB
import com.racetimer.shared.RaceSequence
import com.racetimer.shared.formatCountdown

/** The Custom entry's label, shared with the tests so the copy lives in one place. */
const val CUSTOM_ENTRY_LABEL = "Custom"

/** The Custom entry, so a test can reach it without matching on copy. */
const val TAG_CUSTOM_ENTRY = "custom_entry"

/**
 * Choose the race to run.
 *
 * One tap per sequence, and one more for a length the club made up: this is the only decision the
 * officer makes before the countdown owns the display. There is still no race-manager variant,
 * because their defining behaviour is the count-up after the gun (#206), which is why the list comes
 * from the caller rather than from `BuiltInSequences.all`.
 *
 * Custom is deliberately **not** in that list. It is not one sequence but a family — the duration is
 * inside the id (`custom_8m`) and `BuiltInSequences.custom` builds the race from it — so it needs a
 * length before it is a sequence at all, and it routes to the stepper instead of selecting anything
 * (#209).
 *
 * @param sequences         The sequences to offer, in order.
 * @param onSelect          Called with the tapped sequence.
 * @param onCustomSelected  Called when Custom is tapped, or null to leave the entry off entirely —
 *                          which is what a caller with nowhere to route it should do, rather than
 *                          showing a dead menu entry.
 */
@Composable
fun SequencePickerScreen(
    sequences: List<RaceSequence>,
    onSelect: (RaceSequence) -> Unit,
    onCustomSelected: (() -> Unit)? = null,
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
            text = "Select sequence",
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 18.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
        )

        sequences.forEach { sequence ->
            Button(
                onClick = { onSelect(sequence) },
                colors = ButtonDefaults.buttonColors(
                    // A raised surface rather than a tint, and it stays one now that #198 has put
                    // the accents within reach: gold here would give every sequence the weight the
                    // Start button carries, on the one screen where nothing is urgent yet.
                    containerColor = Color.White.copy(alpha = 0.14f),
                    contentColor = Color.White,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(vertical = 12.dp),
                ) {
                    Text(
                        text = sequence.name,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        // The sequence's own length, from the sequence — so a sequence whose
                        // definition changes cannot go on advertising the old one here.
                        text = formatCountdown(sequence.totalMs),
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 15.sp,
                    )
                }
            }
        }

        onCustomSelected?.let { openCustom ->
            Button(
                onClick = openCustom,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White.copy(alpha = 0.14f),
                    contentColor = Color.White,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .testTag(TAG_CUSTOM_ENTRY),
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(vertical = 12.dp),
                ) {
                    Text(
                        text = CUSTOM_ENTRY_LABEL,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        // No duration under this one, because it has none until it is set. The
                        // entries above advertise a length; this one advertises that you choose it.
                        text = "Choose the length",
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 15.sp,
                    )
                }
            }
        }
    }
}
