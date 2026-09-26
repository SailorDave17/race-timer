package com.racetimer.phone.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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

/** Test tags, so the assertions find controls by identity rather than by display text. */
const val TAG_KEEP_SCREEN_ON = "choice-keep-screen-on"
const val TAG_FULL_BRIGHTNESS = "choice-full-brightness"
const val TAG_CONTINUE = "choice-continue"

/**
 * The one thing the officer is asked before picking a race (#225).
 *
 * Two switches and a button. It stands *in front of* sequence selection rather than beside it,
 * because the trade it settles — battery against legibility — is made once for the conditions of
 * the day, and a setting tucked behind a gear icon is one nobody adjusts on a pitching boat.
 *
 * It is asked again on every cold launch and remembered nowhere. The right answer is a property of
 * the day, not of the officer.
 */
@Composable
fun DisplayChoiceScreen(
    keepScreenOn: Boolean,
    fullBrightness: Boolean,
    onKeepScreenOnChange: (Boolean) -> Unit,
    onFullBrightnessChange: (Boolean) -> Unit,
    onContinue: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(BG_NORMAL_ARGB))
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Screen for today",
            color = Color.White,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = "The console phone stays where you put it. Set it for this boat, this sun, and " +
                "the battery you have.",
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 15.sp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
        )

        ChoiceRow(
            title = "Keep the screen on",
            detail = "The clock never sleeps mid-sequence. Costs battery.",
            checked = keepScreenOn,
            onCheckedChange = onKeepScreenOnChange,
            tag = TAG_KEEP_SCREEN_ON,
        )
        ChoiceRow(
            title = "Full brightness",
            detail = "Readable in direct sun. Costs more battery again.",
            checked = fullBrightness,
            onCheckedChange = onFullBrightnessChange,
            tag = TAG_FULL_BRIGHTNESS,
        )

        Button(
            onClick = onContinue,
            colors = ButtonDefaults.buttonColors(
                // A raised surface rather than a tint, for the same reason the picker gives: the
                // accent colours a filled button would want are the watch's palette literals, which
                // do not live anywhere this module may read until #198 moves them to shared code.
                containerColor = Color.White.copy(alpha = 0.14f),
                contentColor = Color.White,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp)
                .testTag(TAG_CONTINUE),
        ) {
            Text(
                text = "Continue",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(vertical = 12.dp),
            )
        }
    }
}

@Composable
private fun ChoiceRow(
    title: String,
    detail: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    tag: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
            Text(text = detail, color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Color.White.copy(alpha = 0.4f),
                uncheckedThumbColor = Color.White.copy(alpha = 0.6f),
                uncheckedTrackColor = Color.White.copy(alpha = 0.12f),
            ),
            modifier = Modifier.testTag(tag),
        )
    }
}
