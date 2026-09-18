package com.nutriapp.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val AppColorScheme = lightColorScheme(
    primary = OrangeAccent,
    onPrimary = TextOnDark,
    secondary = GreenAccent,
    onSecondary = TextOnDark,
    tertiary = YellowAccent,
    background = CreamBackground,
    onBackground = TextPrimary,
    surface = CreamBackground,
    onSurface = TextPrimary,
    surfaceVariant = ChipInactiveBg,
    onSurfaceVariant = TextSecondary,
    outline = Divider
)

@Composable
fun NutriAppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AppColorScheme,
        typography = AppTypography,
        content = content
    )
}
