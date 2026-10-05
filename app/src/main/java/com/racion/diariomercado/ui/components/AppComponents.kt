package com.racion.diariomercado.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.racion.diariomercado.ui.theme.AuthHeroTheme

/** Los 4 destinos de la barra inferior usados en toda la app. */
enum class NavDestination(val label: String, val icon: ImageVector) {
    Inicio("Inicio", Icons.Outlined.Home),
    Diario("Diario", Icons.Outlined.MenuBook),
    Escanear("Escanear", Icons.Outlined.QrCodeScanner),
    Perfil("Perfil", Icons.Outlined.Person)
}

@Composable
fun AppBottomBar(
    selected: NavDestination,
    onSelect: (NavDestination) -> Unit,
    modifier: Modifier = Modifier
) {
    NavigationBar(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp
    ) {
        NavDestination.values().forEach { dest ->
            NavigationBarItem(
                selected = dest == selected,
                onClick = { onSelect(dest) },
                icon = {
                    Icon(
                        imageVector = dest.icon,
                        contentDescription = dest.label
                    )
                },
                label = { Text(dest.label, style = MaterialTheme.typography.labelSmall) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    indicatorColor = Color.Transparent
                )
            )
        }
    }
}

/** Botón de acción flotante naranja circular, ej. "+" para agregar comida. */
@Composable
fun OrangeFab(onClick: () -> Unit, modifier: Modifier = Modifier) {
    FloatingActionButton(
        onClick = onClick,
        modifier = modifier.size(56.dp),
        shape = CircleShape,
        containerColor = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary
    ) {
        Icon(Icons.Filled.Add, contentDescription = "Agregar")
    }
}

/** Chip de macro con barra de color, usado en el resumen del día. */
@Composable
fun MacroStat(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(color)
        )
        Spacer(Modifier.height(8.dp))
        Text(value, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Pastilla seleccionable tipo tab (Almuerzo / Frituras / Sopas...). */
@Composable
fun PillTab(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.inverseSurface
                else MaterialTheme.colorScheme.surfaceContainerHighest
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(
            text,
            style = MaterialTheme.typography.titleMedium,
            color = if (selected) MaterialTheme.colorScheme.inverseOnSurface
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** Botón rectangular tipo pastilla usado para Desayuno / Almuerzo / Snack. */
@Composable
fun OptionPill(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(24.dp))
            .background(if (selected) MaterialTheme.colorScheme.inverseSurface else Color.Transparent)
            .border(
                width = 1.dp,
                color = if (selected) MaterialTheme.colorScheme.inverseSurface
                else MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(24.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            style = MaterialTheme.typography.titleMedium,
            color = if (selected) MaterialTheme.colorScheme.inverseOnSurface
            else MaterialTheme.colorScheme.onSurface
        )
    }
}

/**
 * Botón primario naranja de ancho completo.
 *
 * [isLoading] and [enabled] were added with the login screen's Google button and both default to
 * the previous behaviour, so the six existing call sites compile untouched.
 *
 * The spinner REPLACES the label instead of sitting beside it: a button that keeps saying "Continuar"
 * while it is already working invites the second tap, and that tap is a second sign-in attempt
 * against the provider's per-account rate limit. The `enabled` argument stays available for the
 * caller to disable the button for a reason that is not loading.
 */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isLoading: Boolean = false
) {
    Button(
        onClick = onClick,
        // Disabled while loading, on top of whatever the caller asked for. Without the
        // `!isLoading` term a caller that forgot it would open a second account sheet.
        enabled = enabled && !isLoading,
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp),
        shape = MaterialTheme.shapes.large,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary
        )
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary
            )
        } else {
            Text(text, style = MaterialTheme.typography.titleLarge)
        }
    }
}

/**
 * Una línea con el texto centrado: separa dos modos de ingreso sin pasar por una etiqueta de
 * sección en mayúsculas, que aquí se leería como un encabezado.
 *
 * Both halves are the same `outlineVariant` at the same weight on purpose: a divider whose line is
 * lighter than its text (or vice versa) makes the text look disabled, and "Ingresá con Google" is
 * an invitation, not something greyed out.
 */
@Composable
fun LabeledDivider(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.outlineVariant
        )
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 12.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.outlineVariant
        )
    }
}

/**
 * Bloque de cabecera con la identidad de la app, para las pantallas fuera de la barra inferior.
 *
 * ## Why it no longer uses the `inverseSurface` roles
 * It used to paint `inverseSurface` with `inverseOnSurface` text, which is the textbook pairing
 * for a dark card and rendered exactly that in light mode. It also rendered a **cream** card in
 * dark mode, because M3 defines "inverse" as *inverted relative to the current scheme*. An anchor
 * that changes colour with the system is not an anchor: on a dark phone the login screen stopped
 * looking like the rest of the app.
 *
 * The colours now come from [AuthHeroTheme], which is explicitly per-scheme and never inverts.
 * Light mode is unchanged (`#1C1A16` + cream); dark mode uses a lifted dark so the slab still
 * reads as a distinct surface against the dark background. See [AuthHeroColors] for why one fixed
 * value cannot serve both.
 *
 * This component declares no literal colour, which keeps the structural guarantee that the theme
 * owns every hex in the app except the Google brand logo.
 */
@Composable
fun AuthHeroBlock(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier
) {
    val hero = AuthHeroTheme.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.extraLarge)
            .background(hero.surface)
            .padding(24.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            color = hero.onSurface
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            // Dimmed rather than a second hardcoded colour: the same hue at lower alpha stays
            // legible on both slabs without needing a per-scheme value for it.
            color = hero.onSurface.copy(alpha = 0.72f)
        )
    }
}

/** Etiqueta de sección en mayúsculas pequeñas (ej. "LO DE HOY"). */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
    )
}

/** Fila de item de comida (usada en Inicio y en el diario). */
@Composable
fun MealRow(
    emoji: String,
    name: String,
    meta: String,
    kcal: Int,
    iconBg: Color? = null,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(iconBg ?: MaterialTheme.colorScheme.tertiaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Text(emoji, fontSize = 18.sp)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(meta, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("$kcal", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}

/**
 * Tarjeta oscura con métrica grande, usada en cabeceras de "Confirmar" y otras.
 * Usa roles inverse del tema: oscura en modo claro y crema en modo oscuro.
 */
@Composable
fun DarkStatCard(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    content: @Composable (ColumnScope.() -> Unit)? = null
) {
    Column(
        modifier = modifier
            .clip(MaterialTheme.shapes.extraLarge)
            .background(MaterialTheme.colorScheme.inverseSurface)
            .padding(20.dp)
    ) {
        Text(value, style = MaterialTheme.typography.displayLarge, color = MaterialTheme.colorScheme.inverseOnSurface)
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.7f)
        )
        content?.let {
            Spacer(Modifier.height(12.dp))
            it()
        }
    }
}