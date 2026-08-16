package com.racetimer.wear.ui

import androidx.compose.runtime.Composable
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Colors
import androidx.wear.compose.material.Typography
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.racetimer.shared.BG_NORMAL_ARGB
import com.racetimer.shared.ERROR_ARGB
import com.racetimer.shared.ON_ACCENT_ARGB
import com.racetimer.shared.PRIMARY_ARGB
import com.racetimer.shared.PRIMARY_VARIANT_ARGB
import com.racetimer.shared.SECONDARY_ARGB
import com.racetimer.shared.SECONDARY_VARIANT_ARGB
import com.racetimer.shared.SURFACE_ARGB

// Every value here comes from `shared/Palette.kt` or `shared/MessageContrast.kt`, so the phone
// module reads the same palette rather than a matching copy of it (#198). Colour literals under
// `wear/src/main/kotlin` are asserted absent by `ModuleBoundaryTest`, and each shared constant is
// pinned to its pre-move watch value by `PaletteTest` — the move is a relocation, not a retune.
//
// The four countdown state backgrounds are not part of this theme: `TimerScreen` paints them
// itself from `backgroundArgbFor`, so `background` below is only what shows before a race starts.
private val RaceTimerColors = Colors(
    primary = Color(PRIMARY_ARGB),
    primaryVariant = Color(PRIMARY_VARIANT_ARGB),
    secondary = Color(SECONDARY_ARGB),
    secondaryVariant = Color(SECONDARY_VARIANT_ARGB),
    error = Color(ERROR_ARGB),
    onPrimary = Color(ON_ACCENT_ARGB),
    onSecondary = Color(ON_ACCENT_ARGB),
    onError = Color.Black,
    background = Color(BG_NORMAL_ARGB),
    onBackground = Color.White,
    surface = Color(SURFACE_ARGB),
    onSurface = Color.White,
)

private val RaceTimerTypography = Typography(
    display1 = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 48.sp,
        letterSpacing = (-1).sp,
    ),
    display2 = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 40.sp,
        letterSpacing = (-0.5).sp,
    ),
    body1 = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
    ),
    caption1 = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
    ),
)

@Composable
fun RaceTimerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colors = RaceTimerColors,
        typography = RaceTimerTypography,
        content = content,
    )
}
