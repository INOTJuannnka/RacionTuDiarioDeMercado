package com.nutriapp.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.nutriapp.ui.components.*

/**
 * Pantalla 06 · Metas - Control de objetivos: peso objetivo, meta calórica
 * diaria, días activos por semana y distribución de macros.
 */
@Composable
fun MetasScreen(
    weightGoalKg: Float = 63f,
    weightMinKg: Float = 55f,
    weightMaxKg: Float = 80f,
    currentWeightKg: Float = 68f,
    kcalGoal: Int = 1900,
    kcalMin: Int = 1400,
    kcalMax: Int = 2600,
    activeDays: List<String> = listOf("L", "M", "X", "J", "V", "S", "D"),
    selectedDays: Set<String> = setOf("L", "M", "X", "J", "V"),
    macroCarbs: Int = 45,
    macroProtein: Int = 25,
    macroFat: Int = 30,
    onSave: () -> Unit = {},
    onNavigate: (NavDestination) -> Unit = {}
) {
    var weight by remember { mutableStateOf(weightGoalKg) }
    var kcal by remember { mutableStateOf(kcalGoal.toFloat()) }
    var days by remember { mutableStateOf(selectedDays) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        bottomBar = { AppBottomBar(selected = NavDestination.Perfil, onSelect = onNavigate) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            Text("Tus metas", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(20.dp))

            GoalSliderBlock(
                label = "PESO OBJETIVO",
                valueLabel = "${"%.1f".format(weight)} kg",
                value = weight,
                min = weightMinKg,
                max = weightMaxKg,
                onValueChange = { weight = it },
                minLabel = "${weightMinKg.toInt()} kg",
                maxLabel = "actual: ${currentWeightKg.toInt()} kg     ${weightMaxKg.toInt()} kg"
            )

            Spacer(Modifier.height(24.dp))

            GoalSliderBlock(
                label = "META CALÓRICA DIARIA",
                valueLabel = "${kcal.toInt()} kcal",
                value = kcal,
                min = kcalMin.toFloat(),
                max = kcalMax.toFloat(),
                onValueChange = { kcal = it },
                minLabel = "$kcalMin",
                maxLabel = "$kcalMax"
            )

            Spacer(Modifier.height(24.dp))

            SectionLabel("DÍAS ACTIVOS POR SEMANA")
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                activeDays.forEach { day ->
                    val isSelected = day in days
                    Box(
                        Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(
                                if (isSelected) MaterialTheme.colorScheme.secondary
                                else MaterialTheme.colorScheme.surfaceContainerHighest
                            )
                            .clickable {
                                days = if (isSelected) days - day else days + day
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            day,
                            style = MaterialTheme.typography.titleMedium,
                            color = if (isSelected) MaterialTheme.colorScheme.onSecondary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            SectionLabel("DISTRIBUCIÓN DE MACROS")
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MacroPercentCard(
                    "$macroCarbs%",
                    "CARBOS",
                    MaterialTheme.colorScheme.primaryContainer,
                    MaterialTheme.colorScheme.primary,
                    Modifier.weight(1f)
                )
                MacroPercentCard(
                    "$macroProtein%",
                    "PROTEÍNA",
                    MaterialTheme.colorScheme.secondaryContainer,
                    MaterialTheme.colorScheme.secondary,
                    Modifier.weight(1f)
                )
                MacroPercentCard(
                    "$macroFat%",
                    "GRASAS",
                    MaterialTheme.colorScheme.tertiaryContainer,
                    MaterialTheme.colorScheme.tertiary,
                    Modifier.weight(1f)
                )
            }

            Spacer(Modifier.height(28.dp))

            PrimaryButton(text = "Guardar metas", onClick = onSave)
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun GoalSliderBlock(
    label: String,
    valueLabel: String,
    value: Float,
    min: Float,
    max: Float,
    onValueChange: (Float) -> Unit,
    minLabel: String,
    maxLabel: String
) {
    Column {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            SectionLabel(label)
            Text(
                valueLabel,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = min..max,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.outlineVariant
            )
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(minLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(maxLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun MacroPercentCard(
    value: String,
    label: String,
    bg: Color,
    fg: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(MaterialTheme.shapes.large)
            .background(bg)
            .padding(vertical = 16.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(value, style = MaterialTheme.typography.headlineMedium, color = fg)
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = fg)
    }
}