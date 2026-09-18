package com.nutriapp.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.nutriapp.ui.components.*
import com.nutriapp.ui.theme.*

data class SportFocus(
    val emoji: String,
    val title: String,
    val description: String,
    val iconBg: Color
)

/**
 * Pantalla 05 · Perfil deportivo - Selección del enfoque de entrenamiento
 * que ajusta las metas de calorías y macros.
 */
@Composable
fun PerfilDeportivoScreen(
    focuses: List<SportFocus> = defaultFocuses(),
    initialSelection: String = "Running",
    onContinue: (SportFocus) -> Unit = {},
    onNavigate: (NavDestination) -> Unit = {}
) {
    var selected by remember { mutableStateOf(initialSelection) }

    Scaffold(
        containerColor = CreamBackground,
        bottomBar = { AppBottomBar(selected = NavDestination.Perfil, onSelect = onNavigate) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            Text("¿Para qué entrenas?", style = MaterialTheme.typography.headlineLarge, color = TextPrimary)
            Spacer(Modifier.height(6.dp))
            Text(
                "Ajustamos tus metas de calorías y macros según tu actividad principal.",
                style = MaterialTheme.typography.bodyLarge,
                color = TextSecondary
            )
            Spacer(Modifier.height(20.dp))

            val rows = focuses.chunked(2)
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                rows.forEach { rowItems ->
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        rowItems.forEach { focus ->
                            FocusCard(
                                focus = focus,
                                selected = focus.title == selected,
                                onClick = { selected = focus.title },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        if (rowItems.size < 2) Spacer(Modifier.weight(1f))
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            PrimaryButton(
                text = "Continuar",
                onClick = { focuses.firstOrNull { it.title == selected }?.let(onContinue) }
            )
        }
    }
}

@Composable
private fun FocusCard(
    focus: SportFocus,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(if (selected) SelectedBg else Color.White)
            .clickable(onClick = onClick)
            .border(
                if (selected) 2.dp else 1.dp,
                if (selected) SelectedBorder else Divider,
                RoundedCornerShape(18.dp)
            )
            .padding(16.dp)
    ) {
        Box(
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(focus.iconBg),
            contentAlignment = Alignment.Center
        ) {
            Text(focus.emoji)
        }
        Spacer(Modifier.height(12.dp))
        Text(focus.title, style = MaterialTheme.typography.titleLarge, color = TextPrimary)
        Spacer(Modifier.height(4.dp))
        Text(focus.description, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
    }
}

private fun defaultFocuses() = listOf(
    SportFocus("⚽", "Fútbol", "Carga de carbos en días de partido", OrangeAccentLight),
    SportFocus("🏃", "Running", "Enfoque en resistencia y recuperación", YellowAccentLight),
    SportFocus("🏋", "Fuerza", "Más proteína, superávit moderado", GreenAccentLight),
    SportFocus("🚴", "Ciclismo", "Energía sostenida, carbos altos", YellowAccentLight),
    SportFocus("🔥", "Pérdida de peso", "Déficit controlado y gradual", OrangeAccentLight),
    SportFocus("🌿", "Mantenimiento", "Balance general, sin objetivo específico", GreenAccentLight)
)
