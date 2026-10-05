package com.racion.diariomercado.ui.screens.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.CompositionLocalProvider

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.racion.diariomercado.ui.theme.NutriAppTheme
import kotlinx.coroutines.delay

/**
 * Account creation. Reuses [LoginUiState] instead of declaring a second one on purpose: the two
 * forms share three of four fields, and a separate state class would mean two copies of the
 * "validate, then call the repository" rule in [LoginViewModel] that can drift apart.
 *
 * Stateless, like [LoginScreen].
 */
@Composable
fun RegisterScreen(
    state: LoginUiState,
    onEmailChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onConfirmPasswordChange: (String) -> Unit,
    onRegister: () -> Unit,
    onBackToLogin: () -> Unit,
    onErrorShown: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Same rationale as the login screen: the message must outlive the keystrokes that fix it.
    LaunchedEffect(state.errorMessage) {
        if (state.errorMessage != null) {
            delay(ERROR_VISIBLE_MILLIS)
            onErrorShown()
        }
    }

    Scaffold(modifier = modifier) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 24.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "Crear cuenta",
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Necesitás una cuenta para guardar tu diario",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(32.dp))

            // Override onSurfaceVariant locally for better label/placeholder contrast on cream background.
            // Default onSurfaceVariant (#8A8272) fails WCAG AA on #F7F2E9 (~3.5:1).
            // Using onSurface (#1C1A16) gives ~12:1 contrast.
            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
                OutlinedTextField(
                    value = state.email,
                    onValueChange = onEmailChange,
                    label = { Text("Correo electrónico") },
                    singleLine = true,
                    enabled = !state.isLoading,
                    isError = state.errorMessage != null,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Email,
                        imeAction = ImeAction.Next
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(16.dp))

                OutlinedTextField(
                    value = state.password,
                    onValueChange = onPasswordChange,
                    label = { Text("Contraseña") },
                    singleLine = true,
                    enabled = !state.isLoading,
                    isError = state.errorMessage != null,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Next
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(16.dp))

                OutlinedTextField(
                    value = state.confirmPassword,
                    onValueChange = onConfirmPasswordChange,
                    label = { Text("Confirmá la contraseña") },
                    singleLine = true,
                    enabled = !state.isLoading,
                    isError = state.errorMessage != null,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            if (state.errorMessage != null) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = state.errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Spacer(Modifier.height(28.dp))

            Button(
                onClick = onRegister,
                enabled = !state.isLoading,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                if (state.isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.height(24.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text("Crear cuenta", style = MaterialTheme.typography.titleLarge)
                }
            }

            Spacer(Modifier.height(12.dp))

            TextButton(
                onClick = onBackToLogin,
                enabled = !state.isLoading,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) {
                Text("Ya tengo una cuenta — Iniciar sesión")
            }
        }
    }
}

/** Mirrors the login screen's timer; both must clear on the same beat to feel like one flow. */
private const val ERROR_VISIBLE_MILLIS = 4_000L

@Preview(showBackground = true)
@Composable
private fun RegisterScreenPreview() {
    NutriAppTheme {
        RegisterScreen(
            state = LoginUiState(),
            onEmailChange = {},
            onPasswordChange = {},
            onConfirmPasswordChange = {},
            onRegister = {},
            onBackToLogin = {},
            onErrorShown = {}
        )
    }
}

@Preview(showBackground = true, name = "Registro · contraseñas distintas")
@Composable
private fun RegisterScreenMismatchPreview() {
    NutriAppTheme {
        RegisterScreen(
            state = LoginUiState(
                email = "julia@ejemplo.com",
                password = "secreto123",
                confirmPassword = "secreto124",
                errorMessage = "Las contraseñas no coinciden"
            ),
            onEmailChange = {},
            onPasswordChange = {},
            onConfirmPasswordChange = {},
            onRegister = {},
            onBackToLogin = {},
            onErrorShown = {}
        )
    }
}
