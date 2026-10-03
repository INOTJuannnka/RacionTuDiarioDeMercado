package com.racion.diariomercado.ui.screens.auth

import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.credentials.CustomCredential
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.racion.diariomercado.R
import kotlinx.coroutines.launch

/**
 * What happened when the user tapped "Continuar con Google", as a stable marker.
 *
 * It is an enum of markers and not a sealed result carrying user copy because the sentences live
 * in [LoginViewModel], the layer that renders them. A screen is free to re-word a message without
 * this file changing, and — more importantly — nothing here is a Spanish string that another
 * layer would have to match on. That is the same separation the codebase already uses for
 * [com.racion.diariomercado.domain.repository.AuthErrorMarkers].
 *
 * [CANCELLED] exists as a first-class outcome precisely because it is NOT an error: reporting a
 * dismissal as a failure is how a cancel button becomes a support ticket.
 */
enum class GoogleSignInOutcome {
    /** Play Services is missing, outdated, or the device has no usable Google account. */
    PROVIDER_UNAVAILABLE,

    /** The user dismissed the account sheet. Nothing went wrong. */
    CANCELLED,

    /** Anything else. The cause is for the log, never for the screen. */
    FAILED
}

/**
 * Returns a lambda that runs the Google account flow and reports what came back.
 *
 * ## Why this lives in the Presentation layer
 * Credential Manager's `getCredential` launches a system UI, so it needs a `Context` — an
 * `Activity` context in practice, so the sheet opens in the same task stack. A repository that
 * held one would be a leak, and [com.racion.diariomercado.data.firebase.FirebaseAuthRepository] is
 * explicitly built to take **no** constructor argument so it never depends on that timing. So the
 * picker runs here and the result crosses into the domain layer as a plain `String` ID token,
 * which is what keeps Domain free of both `androidx.credentials` and Firebase credential types.
 *
 * ## Why [GetSignInWithGoogleOption] and not [com.google.android.libraries.identity.googleid.GetGoogleIdOption]
 * `GetSignInWithGoogleOption` is the flow googleid 1.1.x ships the branded bottom sheet for, and it
 * handles the "this device has no authorized Google account yet" fallback internally. The older
 * `GetGoogleIdOption` makes the caller drive that two-step dance by hand — try with
 * `setFilterByAuthorizedAccounts(true)`, catch `NoCredentialException`, try again with `false` —
 * and getting it wrong means a second-time user sees an empty account list.
 *
 * It also cannot be combined with other credential options: the Play Services controller rejects
 * any request whose option count is not exactly 1 with [androidx.credentials.exceptions.GetCredentialUnsupportedException].
 * This function therefore adds exactly one, and any future second option needs a second request.
 *
 * ## Both credential types are accepted on purpose
 * The returned [CustomCredential] carries either
 * [GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL] or
 * [GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_SIWG_CREDENTIAL] depending on which option produced
 * it. Checking only one would make the extraction fail the day someone swaps the option, and the
 * failure would read as "Google is broken" rather than "a string comparison went stale".
 *
 * ## Known limitation: configuration changes
 * The request runs in [rememberCoroutineScope], which is cancelled when this composable leaves the
 * composition — so a rotation while the sheet is open loses the result. Credential Manager's sheet
 * is a dialog the user cannot rotate away from, which is why this is acceptable today rather than
 * hoisted onto the Activity scope. FF-7 replaces this seam anyway; if the flow ever outlives the
 * composition, the scope is the thing to move, not the repository.
 */
@Composable
fun rememberGoogleSignInLauncher(
    onIdToken: (String) -> Unit,
    onOutcome: (GoogleSignInOutcome) -> Unit
): () -> Unit {
    val context = LocalContext.current
    // Recreated only when the Activity changes. `CredentialManager.create` is cheap but there is
    // no reason to hand the provider a new instance on every recomposition.
    val credentialManager = remember(context) { CredentialManager.create(context) }
    // Generated by the google-services plugin from the `client_type: 3` entry in
    // google-services.json. It is NOT the Android API key and it is not hardcoded anywhere.
    val webClientId = stringResource(R.string.default_web_client_id)
    val scope = rememberCoroutineScope()

    // The two callbacks are captured by a lambda that may outlive this recomposition, so they are
    // read through `rememberUpdatedState` rather than captured by value.
    val currentOnIdToken by rememberUpdatedState(onIdToken)
    val currentOnOutcome by rememberUpdatedState(onOutcome)

    return remember(credentialManager, webClientId) {
        {
            scope.launch {
                val result = runCatching {
                    val request = GetCredentialRequest.Builder()
                        .addCredentialOption(GetSignInWithGoogleOption.Builder(webClientId).build())
                        .build()
                    credentialManager.getCredential(context, request)
                }

                val response = result.exceptionOrNull()?.let { failure ->
                    currentOnOutcome(failure.toOutcome())
                    return@launch
                } ?: result.getOrThrow()

                val credential = response.credential
                val idToken = when {
                    credential !is CustomCredential -> null
                    credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL &&
                        credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_SIWG_CREDENTIAL ->
                        null
                    // `createFrom` throws on a malformed bundle, and this whole block is inside the
                    // caller's coroutine, so an exception here would take the screen down. Fold it
                    // into FAILED rather than letting it escape.
                    else -> runCatching {
                        GoogleIdTokenCredential.createFrom(credential.data).idToken
                    }.getOrNull()
                }

                if (idToken.isNullOrBlank()) {
                    currentOnOutcome(GoogleSignInOutcome.FAILED)
                } else {
                    currentOnIdToken(idToken)
                }
            }
        }
    }
}

/**
 * Maps a Credential Manager failure onto a marker.
 *
 * The three branches are genuinely different situations and the user can act on each one, which
 * is why they are not collapsed into a single "Google failed": a dismissal needs no message at all,
 * a missing provider is a device or Play Services problem, and anything else is worth a retry.
 */
private fun Throwable.toOutcome(): GoogleSignInOutcome = when (this) {
    is GetCredentialCancellationException -> GoogleSignInOutcome.CANCELLED
    // "No authorized account and no way to add one" and "Play Services is not usable" are the same
    // thing from the login screen's point of view: this device cannot do Google sign-in right now.
    is NoCredentialException,
    is GetCredentialProviderConfigurationException -> GoogleSignInOutcome.PROVIDER_UNAVAILABLE
    is GetCredentialException -> GoogleSignInOutcome.FAILED
    else -> GoogleSignInOutcome.FAILED
}

/**
 * The Google-branded sign-in button.
 *
 * ## Why it is NOT tinted
 * Google's button guidelines forbid using the app's colour on a "Sign in with Google" button: the
 * mark has to sit on white (or on the surface, in dark mode) with a neutral outline, or the
 * branding is wrong. So this uses [androidx.compose.material3.colorScheme.surfaceContainerLowest]
 * and [androidx.compose.material3.colorScheme.outline] rather than the orange
 * [androidx.compose.material3.colorScheme.primary] the app's own button uses — which is also why
 * the two buttons read as peers instead of one of them looking like the real action.
 *
 * The height, the [MaterialTheme.shapes.large] radius and the label typography are copied from
 * `PrimaryButton` on purpose: the two buttons sit side by side vertically and anything that
 * differs between them reads as a layout mistake.
 */
@Composable
fun GoogleSignInButton(
    onClick: () -> Unit,
    enabled: Boolean,
    isLoading: Boolean,
    modifier: Modifier = Modifier
) {
    OutlinedButton(
        onClick = onClick,
        // Disabled while loading, and the spinner replaces the label: without the disable, a double
        // tap opens two account sheets, and the second one lands on top of the first with nothing
        // to explain it.
        enabled = enabled && !isLoading,
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp),
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
            contentColor = MaterialTheme.colorScheme.onSurface
        ),
        contentPadding = PaddingValues(horizontal = 24.dp)
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary
            )
        } else {
            // `contentDescription = null`: the button already carries the words "Continuar con
            // Google", so announcing the logo as well would make TalkBack read the label twice.
            Image(
                painter = painterResource(R.drawable.ic_google_g),
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = "Continuar con Google",
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center
            )
        }
    }
}
