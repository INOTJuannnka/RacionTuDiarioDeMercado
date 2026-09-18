package com.nutriapp.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nutriapp.ui.components.*

data class ScannedItem(val name: String, val timeAgo: String, val kcal: Int)

/**
 * Pantalla 03 · Escáner - Lector de código de barras del producto.
 * `isScanning` controla la línea animada del láser (anímala externamente con
 * una transición infinita si se desea).
 */
@Composable
fun EscanerScreen(
    recentScans: List<ScannedItem> = listOf(ScannedItem("Avena Alpina 200ml", "Hace 2 días", 140)),
    onManualEntry: () -> Unit = {},
    onScanResult: () -> Unit = {},
    onNavigate: (NavDestination) -> Unit = {}
) {
    // El visor de cámara es negro fijo en ambos temas (cromo de cámara, no una
    // superficie de contenido), por eso los textos sobre él usan literales claros.
    Scaffold(
        containerColor = Color.Black,
        bottomBar = { AppBottomBar(selected = NavDestination.Escanear, onSelect = onNavigate) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Column(Modifier.padding(horizontal = 24.dp, vertical = 20.dp)) {
                Text(
                    "Escanea el producto",
                    style = MaterialTheme.typography.headlineMedium,
                    color = Color(0xFFF7F2E9)
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Apunta la cámara al código de barras del empaque",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFFB8B2A2)
                )
            }

            // Área de cámara con marco de escaneo
            Box(
                modifier = Modifier
                    .padding(horizontal = 24.dp)
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(MaterialTheme.shapes.extraLarge)
                    .background(Color(0xFF0D0D0C))
                    .clickable(onClick = onScanResult),
                contentAlignment = Alignment.Center
            ) {
                ScanFrame(modifier = Modifier.fillMaxSize().padding(24.dp))
            }

            Spacer(Modifier.height(20.dp))

            Text(
                "Ingresar código manualmente",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onManualEntry)
            )

            Spacer(Modifier.height(20.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(20.dp)
            ) {
                Box(
                    Modifier
                        .width(40.dp)
                        .height(4.dp)
                        .align(Alignment.CenterHorizontally)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.outlineVariant)
                )
                Spacer(Modifier.height(16.dp))
                SectionLabel("ESCANEADO RECIENTEMENTE")
                Spacer(Modifier.height(12.dp))
                recentScans.forEach { item ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Outlined.Delete,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                item.name,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                item.timeAgo.uppercase(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            "${item.kcal}",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}

/** Marco de escaneo con esquinas amarillas y línea láser central. */
@Composable
private fun ScanFrame(modifier: Modifier = Modifier) {
    // Los colores se leen fuera del bloque draw (DrawScope no es composable).
    val cornerColor = MaterialTheme.colorScheme.tertiary
    val laserColor = MaterialTheme.colorScheme.primary
    Canvas(modifier = modifier) {
        val cornerLen = size.width * 0.12f
        val stroke = 6f

        // Esquina superior izquierda
        drawLine(cornerColor, Offset(0f, cornerLen), Offset(0f, 0f), stroke, cap = StrokeCap.Round)
        drawLine(cornerColor, Offset(0f, 0f), Offset(cornerLen, 0f), stroke, cap = StrokeCap.Round)
        // Esquina superior derecha
        drawLine(cornerColor, Offset(size.width - cornerLen, 0f), Offset(size.width, 0f), stroke, cap = StrokeCap.Round)
        drawLine(cornerColor, Offset(size.width, 0f), Offset(size.width, cornerLen), stroke, cap = StrokeCap.Round)
        // Esquina inferior izquierda
        drawLine(cornerColor, Offset(0f, size.height - cornerLen), Offset(0f, size.height), stroke, cap = StrokeCap.Round)
        drawLine(cornerColor, Offset(0f, size.height), Offset(cornerLen, size.height), stroke, cap = StrokeCap.Round)
        // Esquina inferior derecha
        drawLine(cornerColor, Offset(size.width - cornerLen, size.height), Offset(size.width, size.height), stroke, cap = StrokeCap.Round)
        drawLine(cornerColor, Offset(size.width, size.height), Offset(size.width, size.height - cornerLen), stroke, cap = StrokeCap.Round)

        // Línea láser central (estática; anímala con un valor externo si lo necesitas)
        drawLine(
            laserColor,
            Offset(0f, size.height / 2f),
            Offset(size.width, size.height / 2f),
            strokeWidth = 4f
        )
    }
}