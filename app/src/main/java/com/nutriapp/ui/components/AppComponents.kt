package com.nutriapp.ui.components

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

/** Los 5 destinos de la barra inferior usados en toda la app. */
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

/** Botón primario naranja de ancho completo. */
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp),
        shape = MaterialTheme.shapes.large,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary
        )
    ) {
        Text(text, style = MaterialTheme.typography.titleLarge)
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