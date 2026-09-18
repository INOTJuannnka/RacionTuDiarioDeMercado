package com.nutriapp.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.List
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.nutriapp.ui.components.*

data class FoodResult(
    val emoji: String,
    val name: String,
    val origin: String,
    val kcal: Int,
    val unitLabel: String = "kcal / ud",
    val iconBg: androidx.compose.ui.graphics.Color? = null
)

/**
 * Pantalla 02 · Agregar - Buscar y elegir comidas del mercado local,
 * sincronizado con la API de "Mercado Fresco".
 */
@Composable
fun AgregarScreen(
    results: List<FoodResult> = defaultResults(),
    categories: List<String> = listOf("Almuerzo", "Frituras", "Sopas", "Bebidas"),
    onFoodClick: (FoodResult) -> Unit = {},
    onAddMeal: () -> Unit = {},
    onNavigate: (NavDestination) -> Unit = {}
) {
    var query by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf(categories.first()) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        bottomBar = { AppBottomBar(selected = NavDestination.Diario, onSelect = onNavigate) },
        floatingActionButton = { OrangeFab(onClick = onAddMeal) }
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
                },
                singleLine = true,
                shape = MaterialTheme.shapes.large,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                ),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(12.dp))

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

            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(categories) { cat ->
                    PillTab(
                        text = cat,
                        selected = cat == selectedCategory,
                        onClick = { selectedCategory = cat }
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                items(results) { food ->
                    FoodResultRow(food = food, onClick = { onFoodClick(food) })
                }
            }
        }
    }
}

@Composable
private fun FoodResultRow(food: FoodResult, onClick: () -> Unit) {
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
                .background(food.iconBg ?: MaterialTheme.colorScheme.tertiaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Text(food.emoji, fontSize = androidx.compose.ui.unit.TextUnit(20f, androidx.compose.ui.unit.TextUnitType.Sp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(food.name, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(
                food.origin.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text("${food.kcal}", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(food.unitLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun defaultResults() = listOf(
    FoodResult("🫓", "Arepa de choclo", "Típico · Región andina", 210),
    FoodResult("🥟", "Empanada de carne", "Típico · Tolima", 230),
    FoodResult("🍛", "Bandeja paisa", "Típico · Antioquia", 860, unitLabel = "kcal / porción"),
    FoodResult("🍠", "Patacón con hogao", "Típico · Pacífico", 310, unitLabel = "kcal / porción")
)