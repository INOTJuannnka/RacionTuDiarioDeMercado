package com.nutriapp.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Serif para títulos grandes (estilo editorial, como "Hola!")
val DisplaySerif = FontFamily.Serif

/**
 * Escala tipográfica M3 completa. Los títulos grandes usan la serif SemiBold de
 * la marca; cuerpo, títulos pequeños y etiquetas mantienen la escala compacta
 * original de la app.
 */
val AppTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = DisplaySerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 34.sp,
        lineHeight = 40.sp
    ),
    displayMedium = TextStyle(
        fontFamily = DisplaySerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 30.sp,
        lineHeight = 36.sp
    ),
    displaySmall = TextStyle(
        fontFamily = DisplaySerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 34.sp
    ),
    headlineLarge = TextStyle(
        fontFamily = DisplaySerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 26.sp,
        lineHeight = 32.sp
    ),
    headlineMedium = TextStyle(
        fontFamily = DisplaySerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp
    ),
    headlineSmall = TextStyle(
        fontFamily = DisplaySerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 16.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 20.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
        letterSpacing = 0.4.sp
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        letterSpacing = 0.5.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 10.sp,
        letterSpacing = 0.6.sp
    )
)