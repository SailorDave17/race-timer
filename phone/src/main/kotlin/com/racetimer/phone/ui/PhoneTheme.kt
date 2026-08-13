package com.racetimer.phone.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.racetimer.shared.BG_NORMAL_ARGB

/**
 * The phone app's theme.
 *
 * Every colour here is either a shared constant from `shared/MessageContrast.kt` or one of Compose's
 * own named absolutes — there is no palette literal in this module, by rule (#197 AC 3, asserted by
 * `ModuleBoundaryTest`). The watch's `Theme.kt` still carries its gold/navy literals; moving those
 * into shared code so both modules read one palette is #198, and this theme is deliberately thin so
 * that story has nothing to unpick here.
 *
 * Dark only. A committee-boat console in daylight wants maximum contrast, not a light scheme, and
 * the running screen paints its own state colour over this anyway.
 */
@Composable
fun PhoneTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Color(BG_NORMAL_ARGB),
            onBackground = Color.White,
            surface = Color(BG_NORMAL_ARGB),
            onSurface = Color.White,
            // White on the state colour, both ways round: the four countdown backgrounds are
            // contrast-tested against white text in `MessageContrastTest`, and a tinted control
            // would be the one element on screen holding a different bar.
            primary = Color.White,
            onPrimary = Color.Black,
        ),
        content = content,
    )
}
