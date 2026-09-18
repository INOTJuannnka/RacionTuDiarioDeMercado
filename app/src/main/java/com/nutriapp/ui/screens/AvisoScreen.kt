package com.nutriapp.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.nutriapp.ui.components.PrimaryButton

/**
 * Pantalla 09 · Aviso - Recomendación de acompañamiento profesional antes
 * de fijar metas de peso o rutinas intensas. Aparece como modal/bottom sheet
 * o pantalla completa según el flujo de onboarding.
 */
@Composable
fun AvisoScreen(
    title: String = "Ración acompaña, no reemplaza",
    onAccept: () -> Unit = {},
    onViewPolicy: () -> Unit = {}
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 28.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.tertiary),
                contentAlignment = Alignment.Center
            ) {
                Text("🌿", style = MaterialTheme.typography.headlineMedium)
            }

            Spacer(Modifier.height(20.dp))

            Text(
                title,
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(16.dp))

            val body = buildAnnotatedString {
                append("La información nutricional es una guía general. ")
                withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)) {
                    append("Antes de fijar metas de peso, déficit calórico o rutinas intensas, ")
                }
                append("habla con un médico, nutricionista o profesional certificado.")
            }
            Text(
                body,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(28.dp))

            PrimaryButton(text = "Entendido, continuar", onClick = onAccept)

            Spacer(Modifier.height(16.dp))

            val policyText = buildAnnotatedString {
                withStyle(SpanStyle(textDecoration = TextDecoration.Underline, color = MaterialTheme.colorScheme.onSurface)) {
                    append("Ver política de uso y fuentes de datos")
                }
            }
            ClickableText(
                text = policyText,
                style = MaterialTheme.typography.bodyMedium,
                onClick = { onViewPolicy() }
            )
        }
    }
}