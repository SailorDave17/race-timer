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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.racetimer.shared.BG_NORMAL_ARGB
import com.racetimer.shared.RaceSequence
import com.racetimer.shared.formatCountdown

/**
 * Choose the race to run.
 *
 * One tap per sequence and nothing else on screen: this is the only decision the officer makes
 * before the countdown owns the display. There is no Custom entry here — editing and persisting a
 * custom duration is #209 — and no race-manager variant, because their defining behaviour is the
 * count-up after the gun (#206). The list comes from the caller rather than from
 * `BuiltInSequences.all` for exactly that reason.
 *
 * @param sequences The sequences to offer, in order.
 * @param onSelect  Called with the tapped sequence.
 */
@Composable
fun SequencePickerScreen(
    sequences: List<RaceSequence>,
    onSelect: (RaceSequence) -> Unit,
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
                    // A raised surface rather than a tint: the accent colours a chip would use are
                    // the watch's palette literals, which do not live anywhere this module may read
                    // until #198 moves them into shared code.
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
    }
}
