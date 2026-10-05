package com.racion.diariomercado.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.outlined.List
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.racion.diariomercado.domain.model.FoodProduct
import com.racion.diariomercado.ui.components.*
import com.racion.diariomercado.ui.preview.PreviewData
import kotlin.math.roundToInt

/**
 * Pantalla 02 · Agregar - Buscar y elegir comidas del mercado local,
 * sincronizado con la API de "Mercado Fresco".
 *
 * There is exactly ONE way into the confirm flow from here: tapping a result. The floating
 * action button that used to sit next to it navigated to the same confirm screen with no
 * product attached, so it was a dead end the user could only leave by going back. It has been
 * removed rather than re-pointed, because "add food" without a food is not an action.
 */
@Composable
fun AgregarScreen(
    viewModel: AgregarViewModel,
    onFoodClick: (FoodProduct) -> Unit = {},
    onNavigate: (NavDestination) -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        bottomBar = { AppBottomBar(selected = NavDestination.Diario, onSelect = onNavigate) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            Text(
                "¿Qué comiste?",
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Busca arepa, ajiaco, buñuelo...") },
                leadingIcon = {
                    Icon(Icons.Outlined.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                },
                trailingIcon = {
                    if (query.isNotBlank()) {
                        IconButton(onClick = { query = ""; viewModel.onClearQuery() }) {
                            Icon(
                                Icons.Filled.Clear,
                                contentDescription = "Limpiar búsqueda",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        Box(
                            Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.inverseSurface),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Outlined.List,
                                contentDescription = "Filtros",
                                tint = MaterialTheme.colorScheme.inverseOnSurface
                            )
                        }
                    }
                },
                singleLine = true,
                shape = MaterialTheme.shapes.large,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                ),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(12.dp))

            // NOTE: this chip is a status indicator, not a button — it stays non-interactive.
            // TODO(ST-2): manual entry has no home on this screen. The parameter that used to
            // carry it was removed rather than left dangling, because `AppNavigation` pointed it
            // at ESCANEAR while ESCANEAR's own "Ingresar código manualmente" row points back
            // here — wiring it would trap the user in a two-screen ping-pong. It comes back as
            // a real affordance (a separate screen or a bottom sheet on this one), never as a
            // navigation hop to the scanner. Do not hijack the status chip above for it.
            AssistChip(
                onClick = {},
                label = { Text("Sincronizado con Mercado Fresco API") },
                leadingIcon = {
                    Box(
                        Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.secondary)
                    )
                },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    labelColor = MaterialTheme.colorScheme.secondary
                ),
                border = null
            )

            Spacer(Modifier.height(16.dp))

            // Search button - explicit submit to respect OFF rate limits (OFF-4)
            PrimaryButton(
                text = "Buscar",
                onClick = { viewModel.onSearch(query) },
                enabled = query.isNotBlank()
            )

            Spacer(Modifier.height(16.dp))

            val currentState = uiState
            when (currentState) {
                is AgregarUiState.Idle -> {
                    Text(
                        "Escribí un alimento y tocá \"Buscar\"",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                is AgregarUiState.Searching -> {
                    Column(
                        Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(32.dp)
                        )
                        Text(
                            "Buscando...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                is AgregarUiState.Results -> {
                    if (currentState.products.isEmpty()) {
                        Text(
                            "No se encontraron resultados para \"$query\"",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            items(currentState.products) { food ->
                                FoodResultRow(food = food, onClick = { onFoodClick(food) })
                            }
                        }
                    }
                }
                is AgregarUiState.Error -> {
                    Column(
                        Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            currentState.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = { viewModel.onSearch(query) }) {
                            Text("Reintentar")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FoodResultRow(food: FoodProduct, onClick: () -> Unit) {
    // Per-serving when the producer declares a serving, per 100 g otherwise. Reporting the
    // wrong basis is how a "35 kcal" apple ends up logged as a full meal.
    val servingGrams = food.servingGrams
    val shownKcal = if (servingGrams != null) {
        food.nutritionFor(servingGrams).kcal
    } else {
        food.nutritionPer100g.kcal
    }
    val unitLabel = if (servingGrams != null) "kcal / porción" else "kcal / 100 g"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.tertiaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Text(food.emoji, fontSize = 20.sp)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(food.name, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(
                originLabel(food).uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text("$shownKcal", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(unitLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** "Típico · Región andina" style subtitle: brand first, categories as the fallback. */
internal fun originLabel(product: FoodProduct): String =
    product.brand?.takeIf { it.isNotBlank() }
        ?: product.categories.joinToString(" · ").takeIf { it.isNotBlank() }
        ?: "Sin origen declarado"
