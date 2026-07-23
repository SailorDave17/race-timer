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

private val RaceTimerColors = Colors(
    primary = Color(0xFFFFD700),          // Gold — primary action buttons
    primaryVariant = Color(0xFFB8860B),    // Dark gold
    secondary = Color(0xFF64B5F6),        // Light blue — secondary actions
    secondaryVariant = Color(0xFF1565C0),
    error = Color(0xFFCF6679),
    onPrimary = Color(0xFF1A1A2E),
    onSecondary = Color(0xFF1A1A2E),
    onError = Color.Black,
    background = Color(0xFF1A1A2E),
    onBackground = Color.White,
    surface = Color(0xFF2A2A40),
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
