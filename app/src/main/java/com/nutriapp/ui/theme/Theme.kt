package com.nutriapp.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

/**
 * Tema raíz de la app. Colores de marca fijos (sin dynamic color): el esquema
 * se elige según el sistema, pero la paleta es idéntica en todos los dispositivos.
 */
@Composable
fun NutriAppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkAppColorScheme else LightAppColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        shapes = AppShapes,
        content = content
    )
}