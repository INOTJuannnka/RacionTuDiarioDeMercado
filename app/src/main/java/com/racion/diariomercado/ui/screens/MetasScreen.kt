package com.racion.diariomercado.ui.screens

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
import com.racion.diariomercado.domain.model.DayOfWeek
import com.racion.diariomercado.domain.model.MacroSplit
import com.racion.diariomercado.domain.model.NutritionGoals
import com.racion.diariomercado.domain.repository.AuthState
import com.racion.diariomercado.ui.components.*
import com.racion.diariomercado.ui.preview.PreviewData
import kotlin.math.roundToInt

/**
 * Pantalla 06 · Metas - Control de objetivos: peso objetivo, meta calórica
 * diaria, días activos por semana y distribución de macros.
 *
 * The slider bounds now come from [NutritionGoals.WEIGHT_RANGE_KG] / [NutritionGoals.KCAL_RANGE]
 * instead of being re-declared as parameters, so the UI cannot offer a weight the domain would
 * reject. The edited values are held locally and handed back whole, in one object, on save —
 * never field by field.
 *
 * This composable is also what the "Perfil" tab renders. It now observes [AuthState] to show
 * the correct account affordance: sign-in for unauthenticated, claim for anonymous, profile for
 * authenticated.
 */
@Composable
fun MetasScreen(
    goals: NutritionGoals = PreviewData.goals,
    onSave: (NutritionGoals) -> Unit = {},
    authState: AuthState = AuthState.Unauthenticated,
    onOpenLogin: (() -> Unit)? = null,
    onClaimAccount: (() -> Unit)? = null,
    onSignOut: (() -> Unit)? = null,
    onNavigate: (NavDestination) -> Unit = {}
) {
    var weight by remember(goals) { mutableStateOf(goals.targetWeightKg) }
    var kcal by remember(goals) { mutableStateOf(goals.kcalPerDay.toFloat()) }
    var days by remember(goals) { mutableStateOf(goals.activeDays) }
    var macroSplit by remember(goals) { mutableStateOf(goals.macroSplit) }

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
                min = NutritionGoals.WEIGHT_RANGE_KG.start,
                max = NutritionGoals.WEIGHT_RANGE_KG.endInclusive,
                onValueChange = { weight = it },
                minLabel = "${NutritionGoals.WEIGHT_RANGE_KG.start.toInt()} kg",
                maxLabel = "actual: ${goals.currentWeightKg.toInt()} kg     ${NutritionGoals.WEIGHT_RANGE_KG.endInclusive.toInt()} kg"
            )

            Spacer(Modifier.height(24.dp))

            GoalSliderBlock(
                label = "META CALÓRICA DIARIA",
                valueLabel = "${kcal.toInt()} kcal",
                value = kcal,
                min = NutritionGoals.KCAL_RANGE.first.toFloat(),
                max = NutritionGoals.KCAL_RANGE.last.toFloat(),
                onValueChange = { kcal = it },
                minLabel = "${NutritionGoals.KCAL_RANGE.first}",
                maxLabel = "${NutritionGoals.KCAL_RANGE.last}"
            )

            Spacer(Modifier.height(24.dp))

            SectionLabel("DÍAS ACTIVOS POR SEMANA")
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DayOfWeek.entries.forEach { day ->
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
                            day.short,
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
            MacroSliderBlock(
                label = "CARBOS",
                value = macroSplit.carbsPct,
                color = MaterialTheme.colorScheme.primary,
                onValueChange = { newCarbs ->
                    macroSplit = redistributeMacros(macroSplit.copy(carbsPct = newCarbs), MacroType.Carbs)
                }
            )
            Spacer(Modifier.height(12.dp))
            MacroSliderBlock(
                label = "PROTEÍNA",
                value = macroSplit.proteinPct,
                color = MaterialTheme.colorScheme.secondary,
                onValueChange = { newProtein ->
                    macroSplit = redistributeMacros(macroSplit.copy(proteinPct = newProtein), MacroType.Protein)
                }
            )
            Spacer(Modifier.height(12.dp))
            MacroSliderBlock(
                label = "GRASAS",
                value = macroSplit.fatPct,
                color = MaterialTheme.colorScheme.tertiary,
                onValueChange = { newFat ->
                    macroSplit = redistributeMacros(macroSplit.copy(fatPct = newFat), MacroType.Fat)
                }
            )

            Spacer(Modifier.height(28.dp))

            PrimaryButton(
                text = "Guardar metas",
                onClick = {
                    onSave(
                        goals.copy(
                            targetWeightKg = weight,
                            kcalPerDay = kcal.toInt(),
                            activeDays = days,
                            macroSplit = macroSplit
                        )
                    )
                }
            )
            Spacer(Modifier.height(12.dp))

            // Account section — branches on AuthState like ProfileScreen does
            when (authState) {
                AuthState.Unauthenticated -> {
                    if (onOpenLogin != null) {
                        SectionLabel("CUENTA")
                        Spacer(Modifier.height(4.dp))
                        TextButton(
                            onClick = onOpenLogin,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Cuenta · Iniciar sesión")
                        }
                    }
                }
                AuthState.Anonymous -> {
                    SectionLabel("CUENTA")
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Tu diario se está guardando en este dispositivo como invitado.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    if (onClaimAccount != null) {
                        PrimaryButton(
                            text = "Reclamar tu cuenta",
                            onClick = onClaimAccount
                        )
                    }
                    if (onSignOut != null) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = onSignOut,
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Cerrar sesión y borrar mis datos")
                        }
                    }
                }
                AuthState.Authenticated -> {
                    SectionLabel("CUENTA")
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Sesión iniciada",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(12.dp))
                    if (onSignOut != null) {
                        OutlinedButton(
                            onClick = onSignOut,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Cerrar sesión")
                        }
                    }
                }
            }
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
private fun MacroSliderBlock(
    label: String,
    value: Int,
    color: Color,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            SectionLabel(label)
            Text(
                "$value%",
                style = MaterialTheme.typography.titleLarge,
                color = color
            )
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.roundToInt()) },
            valueRange = 0f..100f,
            steps = 100,
            colors = SliderDefaults.colors(
                thumbColor = color,
                activeTrackColor = color,
                inactiveTrackColor = MaterialTheme.colorScheme.outlineVariant
            )
        )
    }
}

/**
 * Which macro the user is actively dragging. Used to redistribute the other two proportionally.
 */
private enum class MacroType { Carbs, Protein, Fat }

/**
 * Redistributes the two non-dragged macros proportionally so the total always equals 100%.
 *
 * Example: user sets Carbs to 60 (was 45). Remaining 40% is split between Protein/Fat
 * keeping their current ratio (25:30 → 18:22).
 */
private fun redistributeMacros(updated: MacroSplit, changed: MacroType): MacroSplit {
    val (fixedCarbs, fixedProtein, fixedFat) = when (changed) {
        MacroType.Carbs -> {
            val remaining = 100 - updated.carbsPct
            val totalOther = updated.proteinPct + updated.fatPct
            if (totalOther == 0) {
                // Edge case: both others are 0, split evenly
                Triple(updated.carbsPct, remaining / 2, remaining - remaining / 2)
            } else {
                val newProtein = (remaining * updated.proteinPct / totalOther).coerceAtMost(remaining)
                val newFat = remaining - newProtein
                Triple(updated.carbsPct, newProtein, newFat)
            }
        }
        MacroType.Protein -> {
            val remaining = 100 - updated.proteinPct
            val totalOther = updated.carbsPct + updated.fatPct
            if (totalOther == 0) {
                Triple(remaining / 2, updated.proteinPct, remaining - remaining / 2)
            } else {
                val newCarbs = (remaining * updated.carbsPct / totalOther).coerceAtMost(remaining)
                val newFat = remaining - newCarbs
                Triple(newCarbs, updated.proteinPct, newFat)
            }
        }
        MacroType.Fat -> {
            val remaining = 100 - updated.fatPct
            val totalOther = updated.carbsPct + updated.proteinPct
            if (totalOther == 0) {
                Triple(remaining / 2, remaining - remaining / 2, updated.fatPct)
            } else {
                val newCarbs = (remaining * updated.carbsPct / totalOther).coerceAtMost(remaining)
                val newProtein = remaining - newCarbs
                Triple(newCarbs, newProtein, updated.fatPct)
            }
        }
    }
    return MacroSplit(fixedCarbs, fixedProtein, fixedFat)
}
