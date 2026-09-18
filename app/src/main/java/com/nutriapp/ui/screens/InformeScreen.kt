package com.nutriapp.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.nutriapp.ui.components.*
import com.nutriapp.ui.theme.*

data class DayCalories(val label: String, val kcal: Int, val isHighlighted: Boolean = false)

/**
 * Pantalla 07 · Informe - Resumen semanal rediseñado: promedio diario,
 * mejor día, racha activa, distribución de macros (donut) y calorías por día (barras).
 */
@Composable
fun InformeScreen(
    weekRange: String = "19–25 AGO",
    avgKcal: Int = 1671,
    bestDay: String = "Vie",
    streakDays: Int = 5,
    macroCarbsPct: Int = 45,
    macroProteinPct: Int = 30,
    macroFatPct: Int = 25,
    days: List<DayCalories> = defaultDays(),
    goalKcal: Int = 1900,
    onNavigate: (NavDestination) -> Unit = {}
) {
    Scaffold(
        containerColor = CreamBackground,
        bottomBar = { AppBottomBar(selected = NavDestination.Inicio, onSelect = onNavigate) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Resumen semanal", style = MaterialTheme.typography.headlineLarge, color = TextPrimary)
                Text(weekRange, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
            }

            Spacer(Modifier.height(16.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SummaryPill("$avgKcal", "PROMEDIO/DÍA", CardDark, TextOnDark, Modifier.weight(1f))
                SummaryPill(bestDay, "MEJOR DÍA", OrangeAccentLight, OrangeAccent, Modifier.weight(1f))
                SummaryPill("$streakDays", "RACHA\nACTIVA", YellowAccentLight, YellowAccent, Modifier.weight(1f))
            }

            Spacer(Modifier.height(24.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                DonutChart(
                    carbsPct = macroCarbsPct,
                    proteinPct = macroProteinPct,
                    fatPct = macroFatPct,
                    centerValue = "$avgKcal",
                    centerLabel = "kcal/día",
                    modifier = Modifier.size(140.dp)
                )
                Spacer(Modifier.width(20.dp))
                Column {
                    LegendRow("Carbos $macroCarbsPct%", OrangeAccent)
                    Spacer(Modifier.height(8.dp))
                    LegendRow("Proteína $macroProteinPct%", GreenAccent)
                    Spacer(Modifier.height(8.dp))
                    LegendRow("Grasas $macroFatPct%", YellowAccent)
                }
            }

            Spacer(Modifier.height(28.dp))

            SectionLabel("CALORÍAS POR DÍA")
            Spacer(Modifier.height(12.dp))
            WeeklyBarChart(days = days, goalKcal = goalKcal, modifier = Modifier.fillMaxWidth().height(160.dp))
        }
    }
}

@Composable
private fun SummaryPill(value: String, label: String, bg: Color, fg: Color, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .padding(vertical = 14.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(value, style = MaterialTheme.typography.headlineMedium, color = fg)
        Spacer(Modifier.height(4.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = fg,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}

@Composable
private fun LegendRow(text: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
    }
}

/** Donut de macros dibujado a mano con Canvas. */
@Composable
private fun DonutChart(
    carbsPct: Int,
    proteinPct: Int,
    fatPct: Int,
    centerValue: String,
    centerLabel: String,
    modifier: Modifier = Modifier
) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeWidth = size.minDimension * 0.16f
            val diameter = size.minDimension - strokeWidth
            val topLeft = androidx.compose.ui.geometry.Offset(
                (size.width - diameter) / 2f,
                (size.height - diameter) / 2f
            )
            val arcSize = Size(diameter, diameter)
            var startAngle = -90f

            val segments = listOf(
                carbsPct to OrangeAccent,
                proteinPct to GreenAccent,
                fatPct to YellowAccent
            )
            segments.forEach { (pct, color) ->
                val sweep = 360f * (pct / 100f)
                drawArc(
                    color = color,
                    startAngle = startAngle,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Butt)
                )
                startAngle += sweep
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(centerValue, style = MaterialTheme.typography.titleLarge, color = TextPrimary)
            Text(centerLabel, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
        }
    }
}

/** Gráfico de barras de calorías por día con línea de meta punteada. */
@Composable
private fun WeeklyBarChart(days: List<DayCalories>, goalKcal: Int, modifier: Modifier = Modifier) {
    val maxKcal = (days.maxOfOrNull { it.kcal } ?: goalKcal).coerceAtLeast(goalKcal)
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        days.forEach { day ->
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val heightFraction = day.kcal / maxKcal.toFloat()
                Box(
                    Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(0.85f),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(0.6f)
                            .fillMaxHeight(heightFraction.coerceIn(0.05f, 1f))
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (day.isHighlighted) OrangeAccent else ChipInactiveBg)
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(day.label, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
            }
        }
    }
}

private fun defaultDays() = listOf(
    DayCalories("L", 1500),
    DayCalories("M", 1600),
    DayCalories("X", 1550),
    DayCalories("J", 1700),
    DayCalories("V", 1900, isHighlighted = true),
    DayCalories("S", 1650),
    DayCalories("D", 1480)
)
