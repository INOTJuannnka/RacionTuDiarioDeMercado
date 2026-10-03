package com.racion.diariomercado.ui.screens.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.racion.diariomercado.domain.repository.AuthState
import com.racion.diariomercado.ui.components.PrimaryButton
import com.racion.diariomercado.ui.components.SectionLabel
import com.racion.diariomercado.ui.theme.NutriAppTheme
import kotlinx.coroutines.delay

/**
 * The account section of the profile screen, and the ONLY destination in the app that branches on
 * the session kind. See `docs/SPRINT-1.md` D5 and the KDoc on [ProfileViewModel] for why.
 *
 * Stateless, like [LoginScreen] and [RegisterScreen]: it renders a [ProfileUiState] and reports
 * intent through lambdas, so `@Preview` and any future screenshot test work with no ViewModel in
 * reach.
 *
 * ## The three branches
 * - [AuthState.Unauthenticated] — nothing to lose, so one plain button.
 * - [AuthState.Anonymous] — the only branch with a destructive action. "Cerrar sesión" opens an
 *   [AlertDialog] naming what is lost instead of calling through, because
 *   [com.racion.diariomercado.domain.repository.AuthRepository.signOut] here destroys the uid and
 *   every Firestore document under it, irreversibly and server-side.
 * - [AuthState.Authenticated] — an ordinary sign-out, with no warning.
 *
 * The asymmetry between the last two is deliberate. Warning about data loss where there is none
 * teaches users to dismiss dialogs without reading them, and that habit is what makes the anonymous
 * warning useless — the warning only works if it is the exceptional case.
 */
@Composable
fun ProfileScreen(
    state: ProfileUiState,
    onEmailChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onShowClaimForm: () -> Unit,
    onDismissClaimForm: () -> Unit,
    onClaimSubmit: () -> Unit,
    onSignInClick: () -> Unit,
    onRetrySignIn: () -> Unit,
    onSignOutClick: () -> Unit,
    onSignOutConfirmed: () -> Unit,
    onSignOutDismissed: () -> Unit,
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

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(horizontal = 24.dp, vertical = 32.dp)
    ) {
        Text(
            text = "Mi cuenta",
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(24.dp))

        AccountCard(
            state = state,
            onEmailChange = onEmailChange,
            onPasswordChange = onPasswordChange,
            onShowClaimForm = onShowClaimForm,
            onDismissClaimForm = onDismissClaimForm,
            onClaimSubmit = onClaimSubmit,
            onSignInClick = onSignInClick,
            onRetrySignIn = onRetrySignIn,
            onSignOutClick = onSignOutClick
        )

        if (state.errorMessage != null) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = state.errorMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        Spacer(Modifier.height(32.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(24.dp))
        SectionLabel("ESTA SEMANA")
    }

    // Rendered outside the Column so it floats over the scroll content rather than pushing it, and
    // gated on the ViewModel flag rather than a local `remember`: the invariant "this dialog is only
    // ever reachable from AuthState.Anonymous" has to be assertable from a test. See the KDoc on
    // ProfileUiState.showDestructiveSignOut.
    if (state.showDestructiveSignOut) {
        DestructiveSignOutDialog(
            onConfirm = onSignOutConfirmed,
            onDismiss = onSignOutDismissed
        )
    }
}

/**
 * The three-way branch, in one place.
 *
 * Split out of [ProfileScreen] only so the `@Preview`s below can drive one state at a time; it is not
 * a separate destination and has no navigation of its own.
 *
 * The `when` has no `else`: [AuthState] is a `sealed interface` with exactly three members, so a
 * fourth state becomes a compile error here rather than a screen that silently renders nothing.
 */
@Composable
private fun AccountCard(
    state: ProfileUiState,
    onEmailChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onShowClaimForm: () -> Unit,
    onDismissClaimForm: () -> Unit,
    onClaimSubmit: () -> Unit,
    onSignInClick: () -> Unit,
    onRetrySignIn: () -> Unit,
    onSignOutClick: () -> Unit
) {
    when (state.authState) {
        AuthState.Unauthenticated -> UnauthenticatedContent(
            canRetry = state.errorMessage != null,
            onSignInClick = onSignInClick,
            onRetrySignIn = onRetrySignIn
        )

        AuthState.Anonymous -> AnonymousContent(
            state = state,
            onEmailChange = onEmailChange,
            onPasswordChange = onPasswordChange,
            onShowClaimForm = onShowClaimForm,
            onDismissClaimForm = onDismissClaimForm,
            onClaimSubmit = onClaimSubmit,
            onSignOutClick = onSignOutClick
        )

        AuthState.Authenticated -> AuthenticatedContent(
            email = state.claimedEmail,
            isLoading = state.isLoading,
            onSignOutClick = onSignOutClick
        )
    }
}

/**
 * [AuthState.Unauthenticated]: no session, so nothing to lose and nothing to claim.
 *
 * The button navigates to the login destination rather than signing in from here — there is no form
 * on this screen, and `Routes.LOGIN` already exists. It takes a separate [onSignInClick] callback
 * rather than reusing `onSignOutClick` precisely so the two can never be confused: routing a
 * navigation intent through the sign-out callback is how a profile screen ends up opening a "delete
 * everything" dialog when the user asked to log in.
 *
 * [canRetry] is what separates the two reasons this branch is on screen. With no session and no
 * error, the anonymous sign-in simply has not resolved yet or the user genuinely has to sign in —
 * offering "Reintentar" then is noise. With an error, it is a provider that is switched off, and the
 * one useful thing to offer is a second attempt for after somebody fixed the console.
 */
@Composable
private fun UnauthenticatedContent(
    canRetry: Boolean,
    onSignInClick: () -> Unit,
    onRetrySignIn: () -> Unit
) {
    Text(
        text = "No has iniciado sesión.",
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onSurface
    )
    Spacer(Modifier.height(8.dp))
    Text(
        text = "Entrá con tu correo para ver tu diario en cualquier dispositivo.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(24.dp))
    PrimaryButton(text = "Iniciar sesión", onClick = onSignInClick)

    if (canRetry) {
        Spacer(Modifier.height(8.dp))
        // A Row with centred arrangement rather than `Modifier.align(CenterHorizontally)`: this is a
        // standalone composable, not a child of a Column, so ColumnScope.align does not resolve here.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center
        ) {
            TextButton(onClick = onRetrySignIn) {
                Text("Reintentar")
            }
        }
    }
}

/**
 * [AuthState.Anonymous]: a real uid with real data and no permanent credential.
 *
 * This is the only branch that offers "Reclamá tu cuenta", and the only one whose sign-out warns.
 */
@Composable
private fun AnonymousContent(
    state: ProfileUiState,
    onEmailChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onShowClaimForm: () -> Unit,
    onDismissClaimForm: () -> Unit,
    onClaimSubmit: () -> Unit,
    onSignOutClick: () -> Unit
) {
    Text(
        text = "Sesión de invitado",
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onSurface
    )
    Spacer(Modifier.height(8.dp))
    Text(
        // Says what the state IS, not what is wrong with it: the user did nothing incorrect, they
        // simply have not claimed the account yet, and an error-coloured treatment would say
        // otherwise.
        text = "Tu diario ya se está guardando. Reclamá la cuenta con un correo para no perderlo " +
            "si reinstalás la app.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(24.dp))

    if (state.isClaimFormVisible) {
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
        Spacer(Modifier.height(12.dp))
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
                imeAction = ImeAction.Done
            ),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(20.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Weight rather than a fixed width: the two buttons share the row, so their combined
            // width survives a large system font scale, which a hard-coded 120.dp would not.
            OutlinedButton(
                onClick = onDismissClaimForm,
                enabled = !state.isLoading,
                modifier = Modifier.weight(1f)
            ) {
                Text("Cancelar")
            }
            Button(
                onClick = onClaimSubmit,
                // Disabled while loading, and that is what stops a double tap from firing two
                // promotions — which is exactly how an account gets rate-limited.
                enabled = !state.isLoading,
                modifier = Modifier.weight(1f)
            ) {
                if (state.isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.height(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text("Reclamar")
                }
            }
        }
    } else {
        PrimaryButton(text = "Reclamar tu cuenta", onClick = onShowClaimForm)
        // No spinner on this one: it only opens a form, there is no request in flight yet. The
        // spinner belongs on the submit button, where the network call actually happens.
    }

    Spacer(Modifier.height(24.dp))
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Spacer(Modifier.height(16.dp))

    OutlinedButton(
        onClick = onSignOutClick,
        enabled = !state.isLoading,
        // The error colour is not decoration: it marks the one button on this screen that destroys
        // something, and it has to be distinguishable from "Reclamar" at a glance, before the user
        // has read any text.
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = MaterialTheme.colorScheme.error
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        // The label states the consequence rather than the action. "Cerrar sesión" here reads like
        // the one on the Authenticated branch, which is free; the user has to be able to tell them
        // apart from the label alone.
        Text("Cerrar sesión y borrar mis datos")
    }
}

/**
 * [AuthState.Authenticated]: a permanent account. Signing out destroys nothing.
 *
 * [email] is `null` when the app was cold-started with a restored session rather than claimed on
 * this device — see the KDoc on [ProfileUiState.claimedEmail]. The title renders either way and no
 * placeholder is invented, because a fake address reads as a real one and sends the user looking for
 * an account that is not theirs.
 */
@Composable
private fun AuthenticatedContent(
    email: String?,
    isLoading: Boolean,
    onSignOutClick: () -> Unit
) {
    Text(
        text = if (email != null) AUTHENTICATED_TITLE else AUTHENTICATED_TITLE_NO_EMAIL,
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onSurface
    )
    if (email != null) {
        Spacer(Modifier.height(8.dp))
        Text(
            text = email,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
    }
    Spacer(Modifier.height(24.dp))
    // A normal sign-out, with NO warning dialog: the account survives and can be signed into again.
    OutlinedButton(
        onClick = onSignOutClick,
        enabled = !isLoading,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text("Cerrar sesión")
    }
}

/**
 * The confirmation that stands between the user and an irreversible deletion.
 *
 * ## Why it has to be this specific
 * A generic "¿Cerrar sesión?" makes a destructive action look like a reversible one, and the user has
 * no way to find out otherwise: [com.racion.diariomercado.domain.repository.AuthRepository.signOut]
 * on an anonymous session destroys the uid and every Firestore document under it, permanently and
 * server-side. Nothing in the app can undo it, there is no backup path, and there is no second
 * chance — the account is simply gone, along with the diary it holds.
 *
 * So the dialog does three things a generic one does not:
 * - it states the consequence in the user's terms ("tu diario y toda tu información se borran"), not
 *   in the implementation's ("the uid is destroyed");
 * - it says the loss is permanent and unrecoverable, so there is no expectation of an undo;
 * - the confirm button repeats the consequence ("Borrar y salir") instead of a neutral "Aceptar", so
 *   the tap that commits is never ambiguous about what it commits.
 *
 * Cancelling is the visually quieter of the two, because it is the outcome nobody is harmed by.
 */
@Composable
private fun DestructiveSignOutDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Vas a perder tu diario") },
        text = {
            Text(
                "Tu sesión de invitado no tiene correo ni contraseña asociados, así que no podemos " +
                    "recuperarla. Si cerrás sesión ahora, tu identificador de usuario y todo lo que " +
                    "guardaste —tu diario, tus comidas, tus metas— se borran de forma permanente. " +
                    "No hay forma de recuperarlos."
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError
                )
            ) {
                Text("Borrar y salir")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar")
            }
        }
    )
}

/** Title paired with a known [ProfileUiState.claimedEmail]. */
private const val AUTHENTICATED_TITLE = "Tu cuenta"

/** Title for a restored session whose email this device never saw. See [ProfileUiState.claimedEmail]. */
private const val AUTHENTICATED_TITLE_NO_EMAIL = "Sesión iniciada"

/** Mirrors the login and register screens' timer; all three must clear on the same beat. */
private const val ERROR_VISIBLE_MILLIS = 4_000L

// ---------------------------------------------------------------------------------------------
// Previews — one per AuthState, plus the destructive dialog, because each branch is a different
// screen and only one of them is ever visible at a time.
// ---------------------------------------------------------------------------------------------

@Preview(showBackground = true, name = "Perfil · sin sesion")
@Composable
private fun ProfileScreenUnauthenticatedPreview() {
    NutriAppTheme {
        ProfileScreenPreview(state = ProfileUiState(authState = AuthState.Unauthenticated))
    }
}

@Preview(showBackground = true, name = "Perfil · invitado")
@Composable
private fun ProfileScreenAnonymousPreview() {
    NutriAppTheme {
        ProfileScreenPreview(state = ProfileUiState(authState = AuthState.Anonymous))
    }
}

@Preview(showBackground = true, name = "Perfil · invitado reclamando")
@Composable
private fun ProfileScreenClaimFormPreview() {
    NutriAppTheme {
        ProfileScreenPreview(
            state = ProfileUiState(
                authState = AuthState.Anonymous,
                isClaimFormVisible = true,
                email = "julia@ejemplo.com",
                errorMessage = "Ese correo ya pertenece a otra cuenta. Iniciá sesión con ella " +
                    "para recuperar tu diario."
            )
        )
    }
}

@Preview(showBackground = true, name = "Perfil · con correo")
@Composable
private fun ProfileScreenAuthenticatedPreview() {
    NutriAppTheme {
        ProfileScreenPreview(
            state = ProfileUiState(
                authState = AuthState.Authenticated,
                claimedEmail = "julia@ejemplo.com"
            )
        )
    }
}

/**
 * One wiring point for the `@Preview`s above.
 *
 * Every callback is a no-op, which is the point of the screen being stateless: a preview exercises
 * the rendering branch without a ViewModel, an `AuthRepository` or a Firebase instance in reach.
 */
@Composable
private fun ProfileScreenPreview(state: ProfileUiState) {
    ProfileScreen(
        state = state,
        onEmailChange = {},
        onPasswordChange = {},
        onShowClaimForm = {},
        onDismissClaimForm = {},
        onClaimSubmit = {},
        onSignInClick = {},
        onRetrySignIn = {},
        onSignOutClick = {},
        onSignOutConfirmed = {},
        onSignOutDismissed = {},
        onErrorShown = {}
    )
}

/** The one dialog in the app that guards data loss. Worth previewing on its own. */
@Preview(showBackground = true, name = "Dialogo · borrar datos")
@Composable
private fun DestructiveSignOutDialogPreview() {
    NutriAppTheme {
        DestructiveSignOutDialog(onConfirm = {}, onDismiss = {})
    }
}