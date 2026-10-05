package com.racion.diariomercado.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.racion.diariomercado.domain.model.DiaryEntry
import com.racion.diariomercado.domain.model.Nutrition
import com.racion.diariomercado.ui.components.*
import com.racion.diariomercado.ui.preview.PreviewData
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/**
 * Pantalla 01 · Inicio - Resumen del día.
 * Muestra kcal consumidas vs meta, macros y la lista de comidas del día con filtro local.
 */
@Composable
fun InicioScreen(
    viewModel: InicioViewModel,
    onAddMeal: () -> Unit = {},
    onOpenReport: () -> Unit = {},
    onNavigate: (NavDestination) -> Unit = {}
) {
    val uiState = viewModel.uiState.collectAsStateWithLifecycle()
    val filteredEntries = viewModel.filteredEntries.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }

    val consumed: Nutrition
    val goalKcal: Int
    val dateLabel: String
    val meals: List<DiaryEntry>

    when (val state = uiState.value) {
        is InicioUiState.Content -> {
            consumed = state.summary.consumed
            goalKcal = state.summary.goalKcal
            dateLabel = state.summary.dateLabel
            meals = state.summary.entries
        }
        is InicioUiState.Loading -> {
            consumed = PreviewData.consumed
            goalKcal = PreviewData.goals.kcalPerDay
            dateLabel = PreviewData.todaySummary.dateLabel
            meals = PreviewData.meals
        }
        is InicioUiState.Error -> {
            consumed = PreviewData.consumed
            goalKcal = PreviewData.goals.kcalPerDay
            dateLabel = PreviewData.todaySummary.dateLabel
            meals = PreviewData.meals
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        bottomBar = { AppBottomBar(selected = NavDestination.Inicio, onSelect = onNavigate) },
        floatingActionButton = { OrangeFab(onClick = onAddMeal) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp),
            contentPadding = PaddingValues(top = 16.dp, bottom = 24.dp)
        ) {
            item {
                // Header con nombre y fecha
                Text(
                    text = "¡Hola! 👋",
                    style = MaterialTheme.typography.displayLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = dateLabel.uppercase(),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(16.dp))

                // Search bar
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Buscar en tu diario...") },
                    leadingIcon = {
                        Icon(Icons.Outlined.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    },
                    trailingIcon = {
                        if (query.isNotBlank()) {
                            IconButton(onClick = { query = ""; viewModel.onClearSearch() }) {
                                Icon(Icons.Filled.Clear, contentDescription = "Limpiar búsqueda", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    },
                    singleLine = true,
                    shape = MaterialTheme.shapes.large,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { viewModel.onSearchQueryChange(query) }),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(24.dp))
            }

            item {
                // Kcal summary card
                Column(
                    modifier = Modifier.fillMaxWidth().clickable(onClick = { /* TODO: open report */ }),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "${consumed.kcal}",
                        style = MaterialTheme.typography.displayLarge.copy(fontSize = 48.sp),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "de $goalKcal kcal",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(16.dp))

                    // Progress bar
                    Column(modifier = Modifier.fillMaxWidth()) {
                        val progress = (consumed.kcal.toFloat() / goalKcal.toFloat()).coerceIn(0f..1f)
                        LinearProgressIndicator(
                            progress = progress,
                            modifier = Modifier.fillMaxWidth().height(8.dp),
                            color = if (progress >= 1f) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = if (progress >= 1f) "¡Meta superada! 🎉" else "${goalKcal - consumed.kcal} kcal restantes",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (progress >= 1f) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.End,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                Spacer(Modifier.height(24.dp))
            }

            item {
                // Macros row
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    MacroStat(label = "CARBOS", value = "${consumed.carbsG.roundToInt()}g", color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                    MacroStat(label = "PROTEÍNA", value = "${consumed.proteinG.roundToInt()}g", color = MaterialTheme.colorScheme.secondary, modifier = Modifier.weight(1f))
                    MacroStat(label = "GRASAS", value = "${consumed.fatG.roundToInt()}g", color = MaterialTheme.colorScheme.tertiary, modifier = Modifier.weight(1f))
                }
                Spacer(Modifier.height(28.dp))
            }

            item {
                // Section header with entry count
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Lo de hoy",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "${filteredEntries.value.size} comidas",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(12.dp))
            }

            // Filtered meals list
            items(filteredEntries.value) { meal ->
                MealRow(
                    emoji = meal.product.emoji,
                    name = meal.product.name,
                    meta = mealRowMeta(meal),
                    kcal = meal.totalNutrition.kcal
                )
                Spacer(Modifier.height(16.dp))
            }

            // Empty state
            if (filteredEntries.value.isEmpty()) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Outlined.Restaurant,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = "No hay comidas registradas hoy",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "Toca el botón + para agregar tu primera comida",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}

/**
 * Second line of a meal row: "ALMUERZO · 12:45 PM".
 *
 * Built from the domain model instead of stored as a pre-formatted string so the meal slot and
 * the timestamp stay queryable/sortable in Firestore.
 */
private val mealTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a")

internal fun mealRowMeta(entry: DiaryEntry): String {
    val time = Instant.ofEpochMilli(entry.loggedAtEpochMillis)
        .atZone(ZoneId.systemDefault())
        .format(mealTimeFormatter)
    return "${entry.mealSlot.label.uppercase()} · $time"
}