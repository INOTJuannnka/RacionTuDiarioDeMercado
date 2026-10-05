package com.racion.diariomercado.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf

/**
 * The dark slab used by the auth screens' headline. See [AuthHeroColors] for why it is not an M3
 * colour role.
 *
 * The default is [LightAuthHeroColors] so that reading it outside [NutriAppTheme] compiles and
 * previews. It is not a safe value to rely on: any screen rendered outside the theme will paint
 * the light-mode slab, which is a theme wiring bug, not a styling choice.
 */
val LocalAuthHeroColors = compositionLocalOf { LightAuthHeroColors }

/**
 * The hero colours for the currently selected scheme.
 *
 * Kept as its own accessor so callers never branch on the scheme themselves — that branch is the
 * thing this type exists to make impossible to get subtly wrong in the other direction.
 */
object AuthHeroTheme {
    val current: AuthHeroColors
        @Composable
        @ReadOnlyComposable
        get() = LocalAuthHeroColors.current
}

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
    // Provided alongside the scheme and not derived from it: `AuthHeroColors` deliberately does not
    // invert, so it has to be selected explicitly. See the KDoc on [AuthHeroColors].
    val authHeroColors = if (darkTheme) DarkAuthHeroColors else LightAuthHeroColors
    CompositionLocalProvider(LocalAuthHeroColors provides authHeroColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = AppTypography,
            shapes = AppShapes,
            content = content
        )
    }
}
