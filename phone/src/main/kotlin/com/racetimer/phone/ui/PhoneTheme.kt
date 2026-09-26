package com.racetimer.phone.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.racetimer.shared.BG_NORMAL_ARGB

/**
 * The phone app's theme.
 *
 * Every colour here is either a shared constant — from `shared/MessageContrast.kt` or, since #198,
 * `shared/Palette.kt` — or one of Compose's own named absolutes. There is no palette literal in this
 * module, by rule (#197 AC 3 and #198 AC 5, asserted for both app modules by `ModuleBoundaryTest`).
 * #198 moved the watch's gold/navy accents into that shared file, so the two form factors now read
 * one palette rather than two matching copies; this theme stayed thin through it and had nothing to
 * unpick.
 *
 * That it names only `BG_NORMAL_ARGB` is not an omission. The phone's chrome is white on the state
 * colour by the reasoning below, so the accents are there to be reached for when a phone screen
 * wants one — not to be adopted for the sake of it.
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
