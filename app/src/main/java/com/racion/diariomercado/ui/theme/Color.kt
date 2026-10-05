package com.racion.diariomercado.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// Esquema claro (M3, seed naranja #E05A2B) — superficies crema, acentos saturados.
// Los contenedores son tintes suaves de cada matiz; textOnContainer usa el tinte oscuro.
private val LightScheme = lightColorScheme(
    primary = Color(0xFFE05A2B),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFF7D9C9),
    onPrimaryContainer = Color(0xFF5B1C06),
    secondary = Color(0xFF2F6B4F),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDCEAE1),
    onSecondaryContainer = Color(0xFF0E2E1E),
    tertiary = Color(0xFFE3A93E),
    onTertiary = Color(0xFF3B2F00),
    tertiaryContainer = Color(0xFFF6E7C8),
    onTertiaryContainer = Color(0xFF3F2E00),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    background = Color(0xFFF7F2E9),
    onBackground = Color(0xFF1C1A16),
    surface = Color(0xFFF7F2E9),
    onSurface = Color(0xFF1C1A16),
    surfaceVariant = Color(0xFFEFE8D8),
    onSurfaceVariant = Color(0xFF8A8272),
    outline = Color(0xFF857A69),
    outlineVariant = Color(0xFFE7DFCF),
    scrim = Color(0xFF000000),
    inverseSurface = Color(0xFF1C1A16),
    inverseOnSurface = Color(0xFFF7F2E9),
    inversePrimary = Color(0xFFFFB59C),
    surfaceDim = Color(0xFFDFD6C5),
    surfaceBright = Color(0xFFF9F4EB),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFBF7EF),
    surfaceContainer = Color(0xFFF5EEE2),
    surfaceContainerHigh = Color(0xFFF1E9DA),
    surfaceContainerHighest = Color(0xFFEFE8D8)
)

// Esquema oscuro — fondo crema oscuro, acentos aclarados, contenedores en tinte
// marrón oscuro. inverseSurface es crema: las "tarjetas oscuras" se invierten
// a crema en modo oscuro (semántica M3 de inverse).
private val DarkScheme = darkColorScheme(
    primary = Color(0xFFFFB59C),
    onPrimary = Color(0xFF5B1C06),
    primaryContainer = Color(0xFF7A2E12),
    onPrimaryContainer = Color(0xFFFFDBCB),
    secondary = Color(0xFFA6C9B5),
    onSecondary = Color(0xFF133526),
    secondaryContainer = Color(0xFF2C4A3A),
    onSecondaryContainer = Color(0xFFDCEAE1),
    tertiary = Color(0xFFFFD98A),
    onTertiary = Color(0xFF3B2F00),
    tertiaryContainer = Color(0xFF554413),
    onTertiaryContainer = Color(0xFFF6E7C8),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF1C1A16),
    onBackground = Color(0xFFF7F2E9),
    surface = Color(0xFF1C1A16),
    onSurface = Color(0xFFF7F2E9),
    surfaceVariant = Color(0xFF4B453B),
    onSurfaceVariant = Color(0xFFCCC4B4),
    outline = Color(0xFF968F80),
    outlineVariant = Color(0xFF4B453B),
    scrim = Color(0xFF000000),
    inverseSurface = Color(0xFFF7F2E9),
    inverseOnSurface = Color(0xFF1C1A16),
    inversePrimary = Color(0xFFE05A2B),
    surfaceDim = Color(0xFF191713),
    surfaceBright = Color(0xFF403B30),
    surfaceContainerLowest = Color(0xFF13110E),
    surfaceContainerLow = Color(0xFF211F1A),
    surfaceContainer = Color(0xFF252219),
    surfaceContainerHigh = Color(0xFF2F2B22),
    surfaceContainerHighest = Color(0xFF3A352B)
)

/** Esquema M3 claro de la marca; roles completos según Material Theme Builder. */
val LightAppColorScheme = LightScheme

/** Esquema M3 oscuro de la marca; roles completos según Material Theme Builder. */
val DarkAppColorScheme = DarkScheme

/**
 * The slab behind the auth screens' headline, kept OUT of the M3 scheme on purpose.
 *
 * ## Why this is not a colour role
 * `AuthHeroBlock` used `inverseSurface` + `inverseOnSurface`, which is the textbook pairing for a
 * dark card — and it renders a dark card in light mode. The problem is what "inverse" means: M3
 * defines those roles as *inverted relative to the current scheme*, so in dark mode the same call
 * produces a **cream** card. A login screen whose identity block flips to cream on a dark phone
 * reads as a different app, which is the opposite of what an anchor is for.
 *
 * There is no way to express "dark in both modes" as a scheme role, so these live outside the
 * scheme and are selected explicitly by [NutriAppTheme].
 *
 * ## Why two values and not one fixed hex
 * A single constant cannot satisfy both backgrounds. Against the light cream `#F7F2E9` the slab
 * has to be unambiguously dark; against the dark `#1C1A16` background it has to be unambiguously
 * LIGHTER, or the card becomes invisible and the headline looks like unstyled text. So dark mode
 * uses a lifted dark rather than reusing the light-mode value.
 *
 * Light mode is byte-for-byte what it was before this change (`#1C1A16` + cream text): the
 * complaint was about dark mode, and fixing it must not alter the mode that already looked right.
 *
 * @property surface the slab fill.
 * @property onSurface the headline and subtitle colour on top of [surface].
 */
data class AuthHeroColors(val surface: Color, val onSurface: Color)

/** Dark slab on the light scheme: `#1C1A16` with cream text. Unchanged from the M3 inverse roles. */
val LightAuthHeroColors = AuthHeroColors(
    surface = Color(0xFF1C1A16),
    onSurface = Color(0xFFF7F2E9)
)

/**
 * Dark slab on the dark scheme: a lifted `#332E26` so the card still reads as a distinct surface
 * against the `#1C1A16` background. Cream text, same as light mode, so the pair stays legible.
 */
val DarkAuthHeroColors = AuthHeroColors(
    surface = Color(0xFF332E26),
    onSurface = Color(0xFFF7F2E9)
)