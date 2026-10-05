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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import com.racion.diariomercado.ui.components.AuthHeroBlock
import com.racion.diariomercado.ui.components.LabeledDivider
import com.racion.diariomercado.ui.components.PrimaryButton
import com.racion.diariomercado.ui.theme.NutriAppTheme
import kotlinx.coroutines.delay

/**
 * Sign-in form. Stateless by construction: it renders a [LoginUiState] and reports intent through
 * lambdas, so the same composable is used by the navigation graph, by `@Preview`, and by any
 * future screenshot test without a ViewModel in reach.
 *
 * The Google button follows the same rule: [onGoogleSignIn] is a plain callback and the account
 * picker is launched by whoever holds the trigger (see
 * [rememberGoogleSignInLauncher]), so this file imports nothing from `androidx.credentials`.
 *
 * Note there is no Scaffold here and no `AppBottomBar`. Every other screen in the app renders its
 * own, but the auth screens are a modal flow outside the tabbed shell — showing the bottom bar
 * here would let the user tab away into a destination that assumes a session.
 *
 * ## Why the screen is built out of shared components
 * It used to declare its own `Button`, its own spacing and its own colour picks, which made it the
 * only screen in the app that ignored `AppComponents` — and it is the first thing a new user sees.
 * Every colour, radius and height here now comes from `MaterialTheme` or from a component in
 * `ui/components`, so a change to the theme reaches the login screen like it reaches the rest.
 */
@Composable
fun LoginScreen(
    state: LoginUiState,
    onEmailChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onSignIn: () -> Unit,
    onGoogleSignIn: () -> Unit,
    onSignUpClick: () -> Unit,
    onGoogleMergeConfirmed: () -> Unit,
    onGoogleMergeDismissed: () -> Unit,
    onErrorShown: () -> Unit,
    modifier: Modifier = Modifier
) {
    // The message is cleared on a timer instead of on the next keystroke: clearing it as the user
    // types is unreadable, because the complaint about a 4-character password disappears after the
    // fifth character and the user never learns what was wrong.
    LaunchedEffect(state.errorMessage) {
        if (state.errorMessage != null) {
            delay(ERROR_VISIBLE_MILLIS)
            onErrorShown()
        }
    }

    // One switch for "you cannot start a second sign-in right now". The two flows are separate
    // flags on purpose — [LoginUiState.isLoading] is a network call and [LoginUiState.isGoogleInProgress]
    // includes the stretch where the account sheet is on screen — but from here they disable the
    // same things, and a user cannot tell the difference, so neither should the form.
    val isBusy = state.isLoading || state.isGoogleInProgress

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
            AuthHeroBlock(
                title = "Tu ración, todos los días",
                subtitle = "Escaneá lo que comés y el mercado te cuenta lo que pasa con tus macros."
            )

            Spacer(Modifier.height(24.dp))

            // Google first: it is the path with no form, and putting it above the fold is what
            // keeps the email fields from reading as the only way in.
            GoogleSignInButton(
                onClick = onGoogleSignIn,
                enabled = !isBusy,
                isLoading = state.isGoogleInProgress
            )

            Spacer(Modifier.height(24.dp))

            LabeledDivider(text = "o continuá con tu correo")

            Spacer(Modifier.height(24.dp))

            // Override onSurfaceVariant locally for better label/placeholder contrast on cream background.
            // Default onSurfaceVariant (#8A8272) fails WCAG AA on #F7F2E9 (~3.5:1).
            // Using onSurface (#1C1A16) gives ~12:1 contrast.
            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
                OutlinedTextField(
                    value = state.email,
                    onValueChange = onEmailChange,
                    label = { Text("Correo electrónico") },
                    singleLine = true,
                    enabled = !isBusy,
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
                    enabled = !isBusy,
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

            PrimaryButton(
                text = "Iniciar sesión",
                onClick = onSignIn,
                enabled = !isBusy,
                isLoading = state.isLoading
            )

            Spacer(Modifier.height(12.dp))

            TextButton(
                onClick = onSignUpClick,
                enabled = !isBusy,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) {
                Text("¿No tenés cuenta? Crear cuenta")
            }
        }
    }

    if (state.showGoogleMergeConfirmation) {
        GoogleMergeConfirmationDialog(
            onConfirm = onGoogleMergeConfirmed,
            onDismiss = onGoogleMergeDismissed
        )
    }
}

/**
 * Same merge question as in [ProfileScreen.GoogleMergeConfirmationDialog], replicated here because
 * an anonymous user can also reach the LOGIN screen and hit the same case.
 *
 * The message is identical on purpose: the situation is identical, and a user who sees it in both
 * places should not have to parse two different wordings for the same decision.
 */
@Composable
private fun GoogleMergeConfirmationDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Esta cuenta de Google ya existe") },
        text = {
            Text(
                "Elegiste una cuenta de Google que ya tenés registrada. Para entrar a ella, " +
                    "tu sesión de invitado tiene que cerrarse. Lo que guardaste en este dispositivo " +
                    "—tu nombre, tu foco de deporte y tus metas de calorías— se mueve a esa cuenta. " +
                    "Tu diario no se toca: vive en tablas sin userId y sobrevive a cualquier cambio " +
                    "de sesión."
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Text("Entrar a esa cuenta")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Seguir como invitado")
            }
        }
    )
}

/** How long an error stays on screen before it clears itself. */
private const val ERROR_VISIBLE_MILLIS = 4_000L

@Preview(showBackground = true)
@Composable
private fun LoginScreenPreview() {
    NutriAppTheme {
        LoginScreen(
            state = LoginUiState(),
            onEmailChange = {},
            onPasswordChange = {},
            onSignIn = {},
            onGoogleSignIn = {},
            onSignUpClick = {},
            onGoogleMergeConfirmed = {},
            onGoogleMergeDismissed = {},
            onErrorShown = {}
        )
    }
}

@Preview(showBackground = true, name = "Login · con error")
@Composable
private fun LoginScreenErrorPreview() {
    NutriAppTheme {
        LoginScreen(
            state = LoginUiState(
                email = "julia@ejemplo.com",
                password = "123",
                errorMessage = "La contraseña debe tener al menos 6 caracteres"
            ),
            onEmailChange = {},
            onPasswordChange = {},
            onSignIn = {},
            onGoogleSignIn = {},
            onSignUpClick = {},
            onGoogleMergeConfirmed = {},
            onGoogleMergeDismissed = {},
            onErrorShown = {}
        )
    }
}

@Preview(showBackground = true, name = "Login · Google en curso")
@Composable
private fun LoginScreenGooglePreview() {
    NutriAppTheme {
        LoginScreen(
            state = LoginUiState(isGoogleInProgress = true),
            onEmailChange = {},
            onPasswordChange = {},
            onSignIn = {},
            onGoogleSignIn = {},
            onSignUpClick = {},
            onGoogleMergeConfirmed = {},
            onGoogleMergeDismissed = {},
            onErrorShown = {}
        )
    }
}
