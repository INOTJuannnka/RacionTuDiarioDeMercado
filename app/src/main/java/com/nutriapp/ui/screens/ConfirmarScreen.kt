package com.nutriapp.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nutriapp.ui.components.*

/**
 * Pantalla 04 · Confirmar - Ajustar porción y elegir horario antes de
 * agregar el alimento escaneado al diario.
 */
@Composable
fun ConfirmarScreen(
    tag: String = "Típico · Región andina",
    foodName: String = "Arepa de choclo con queso",
    description: String = "Masa de maíz tierno asada, rellena con queso campesino. Común en el desayuno de plazas de mercado.",
    baseKcalPerUnit: Int = 265,
    gramsPerUnit: Int = 90,
    carbsG: Int = 28,
    proteinG: Int = 9,
    fatG: Int = 11,
    mealTimes: List<String> = listOf("Desayuno", "Almuerzo", "Snack"),
    onAddToDiary: (units: Int, mealTime: String) -> Unit = { _, _ -> },
    onNavigate: (NavDestination) -> Unit = {}
) {
    var units by remember { mutableStateOf(1) }
    var selectedTime by remember { mutableStateOf(mealTimes.first()) }

    val totalKcal = baseKcalPerUnit * units
    val totalCarbs = carbsG * units
    val totalProtein = proteinG * units
    val totalFat = fatG * units

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        bottomBar = { AppBottomBar(selected = NavDestination.Inicio, onSelect = onNavigate) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            AssistChip(
                onClick = {},
                label = { Text(tag.uppercase()) },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    labelColor = MaterialTheme.colorScheme.primary
                ),
                border = null
            )
            Spacer(Modifier.height(12.dp))
            Text(foodName, style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(10.dp))
            Text(description, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)

            Spacer(Modifier.height(20.dp))

            // Selector de unidades
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.large)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                StepperButton(symbol = "–", onClick = { if (units > 1) units-- })
                Column(
                    Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "$units unidad${if (units > 1) "es" else ""}",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        "≈ ${gramsPerUnit * units} g",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                StepperButton(symbol = "+", onClick = { units++ })
            }

            Spacer(Modifier.height(20.dp))

            DarkStatCard(
                value = "$totalKcal",
                label = "CALORÍAS TOTALES",
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    MiniStat("${totalCarbs}g", "Carbos")
                    MiniStat("${totalProtein}g", "Proteína")
                    MiniStat("${totalFat}g", "Grasas")
                }
            }

            Spacer(Modifier.height(20.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                mealTimes.forEach { time ->
                    OptionPill(
                        text = time,
                        selected = time == selectedTime,
                        onClick = { selectedTime = time }
                    )
                }
            }

            Spacer(Modifier.weight(1f))

            PrimaryButton(text = "Agregar al diario", onClick = { onAddToDiary(units, selectedTime) })
        }
    }
}

@Composable
private fun StepperButton(symbol: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.inverseSurface)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(symbol, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.inverseOnSurface)
    }
}

@Composable
private fun MiniStat(value: String, label: String) {
    Column {
        Text(
            value,
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.inverseOnSurface
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.7f)
        )
    }
}