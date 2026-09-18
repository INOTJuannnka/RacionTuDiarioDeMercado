package com.nutriapp.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nutriapp.ui.components.*
import com.nutriapp.ui.theme.*

data class MealEntry(
    val emoji: String,
    val name: String,
    val meta: String,
    val kcal: Int,
    val iconBg: androidx.compose.ui.graphics.Color = YellowAccentLight
)

/**
 * Pantalla 01 · Inicio - Resumen del día.
 * Muestra kcal consumidas vs meta, macros y la lista de comidas del día.
 */
@Composable
fun InicioScreen(
    userName: String = "Hola!",
    dateLabel: String = "Mié 25 Ago",
    kcalConsumed: Int = 1340,
    kcalGoal: Int = 1900,
    carbsG: Int = 142,
    proteinG: Int = 58,
    fatG: Int = 39,
    meals: List<MealEntry> = defaultMeals(),
    onAddMeal: () -> Unit = {},
    onOpenReport: () -> Unit = {},
    onNavigate: (NavDestination) -> Unit = {}
) {
    Scaffold(
        containerColor = CreamBackground,
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
                Text(userName, style = MaterialTheme.typography.displayLarge, color = TextPrimary)
                Text(dateLabel.uppercase(), style = MaterialTheme.typography.labelLarge, color = TextSecondary)
                Spacer(Modifier.height(24.dp))
            }

            item {
                Column(
                    modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenReport),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "$kcalConsumed",
                        style = MaterialTheme.typography.displayLarge.copy(fontSize = 48.sp),
                        color = TextPrimary
                    )
                    Text(
                        "de $kcalGoal kcal",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                }
                Spacer(Modifier.height(24.dp))
            }

            item {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    MacroStat(label = "CARBOS", value = "${carbsG}g", color = OrangeAccent, modifier = Modifier.weight(1f))
                    MacroStat(label = "PROTEÍNA", value = "${proteinG}g", color = GreenAccent, modifier = Modifier.weight(1f))
                    MacroStat(label = "GRASAS", value = "${fatG}g", color = YellowAccent, modifier = Modifier.weight(1f))
                }
                Spacer(Modifier.height(28.dp))
            }

            item {
                Text(
                    "Lo de hoy",
                    style = MaterialTheme.typography.headlineMedium,
                    color = TextPrimary
                )
                Spacer(Modifier.height(12.dp))
            }

            items(meals) { meal ->
                MealRow(
                    emoji = meal.emoji,
                    name = meal.name,
                    meta = meal.meta,
                    kcal = meal.kcal,
                    iconBg = meal.iconBg
                )
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

private fun defaultMeals() = listOf(
    MealEntry("🥐", "Arepa de choclo", "DESAYUNO · 7:20 AM", 210, OrangeAccentLight),
    MealEntry("🍲", "Sancocho de gallina", "ALMUERZO · 12:45 PM", 480, GreenAccentLight),
    MealEntry("🥭", "Jugo de mango biche", "SNACK · 3:10 PM", 130, YellowAccentLight)
)
