package com.racion.diariomercado.ui.auth

import com.racion.diariomercado.core.AppError
import com.racion.diariomercado.core.AppResult
import com.racion.diariomercado.domain.repository.AuthErrorMarkers
import com.racion.diariomercado.domain.repository.AuthRepository
import com.racion.diariomercado.domain.repository.AuthState
import com.racion.diariomercado.domain.repository.SessionDataReassigner
import com.racion.diariomercado.ui.screens.auth.ProfileViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The three ways an anonymous session can be reclaimed, and the one thing that distinguishes them.
 *
 * ## Why this test file exists at all
 * The claim flow has three outcomes with very different consequences, and only one of them is free:
 *
 * | Path | Firebase call | uid | Local rows |
 * | --- | --- | --- | --- |
 * | New email account | `promoteToEmailAccount` | **unchanged** | nothing to do |
 * | Existing email account | `signIn` | **changes** | must be re-keyed |
 * | Google | decided inside the repository | either | either |
 *
 * Getting the middle row wrong loses a user's goals silently. So the assertions below are about
 * WHICH repository method ran and WHETHER the re-key ran with it, not about whether a spinner
 * turned.
 *
 * The re-key's own behaviour — the primary-key collision, the `WHERE` clause, the transaction — is
 * pinned by `SessionDataReassignerTest` against a real Room database. This file only has to prove
 * the ViewModel calls it in the right circumstances.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProfileViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // -- The free path: a brand-new account keeps the same uid --------------------------------

    @Test
    fun claimingWithANewAccountPromotesWithoutReassigningAnything() = runTest(dispatcher) {
        val repository = FakeAuthRepository()
        val reassigner = FakeSessionDataReassigner()
        val viewModel = ProfileViewModel(repository, reassigner)
        advanceUntilIdle()

        viewModel.updateEmail("nuevo@ejemplo.com")
        viewModel.updatePassword("secreto123")
        viewModel.onClaimSubmit()
        advanceUntilIdle()

        assertEquals(1, repository.promoteCalls)
        assertEquals("nuevo@ejemplo.com" to "secreto123", repository.lastPromotion)
        // The load-bearing assertion. `promoteToEmailAccount` links the credential to the SAME
        // FirebaseUser, so the uid never changes and there is no row to re-key. A ViewModel that
        // called the re-assigner here would be doing a pointless write — and worse, it would look
        // correct, so the bug would survive.
        assertEquals(
            "promotion keeps the uid, so nothing may be re-keyed",
            0,
            reassigner.calls
        )
        assertFalse(viewModel.uiState.value.isClaimFormVisible)
        assertEquals("nuevo@ejemplo.com", viewModel.uiState.value.claimedEmail)
    }

    // -- The paid path: an existing account changes the uid -----------------------------------

    @Test
    fun claimingWithAnExistingAccountSignsInAndRekeysTheLocalRowsToTheNewUid() = runTest(dispatcher) {
        val repository = FakeAuthRepository(anonymousUid = ANON_UID, accountUid = EXISTING_UID)
        val reassigner = FakeSessionDataReassigner()
        val viewModel = ProfileViewModel(repository, reassigner)
        advanceUntilIdle()

        viewModel.updateEmail("mia@ejemplo.com")
        viewModel.updatePassword("secreto123")
        viewModel.onClaimIntoExistingAccount()
        advanceUntilIdle()

        assertEquals(1, repository.signInCalls)
        assertEquals(0, repository.promoteCalls)
        assertEquals(
            "the anonymous uid must be captured BEFORE the sign-in replaced it",
            ANON_UID to EXISTING_UID,
            reassigner.lastPair
        )
        assertNull(viewModel.uiState.value.errorMessage)
        assertFalse(viewModel.uiState.value.isClaimFormVisible)
    }

    @Test
    fun theAnonymousUidIsReadBeforeSignInAndTheAccountUidAfterIt() = runTest(dispatcher) {
        // `FakeAuthRepository.signIn` swaps the uid, exactly as Firebase does. If the ViewModel read
        // the uid after the call it would re-key ANON_UID -> ANON_UID and move zero rows, which is
        // exactly the silent failure this whole feature exists to prevent.
        val repository = FakeAuthRepository(anonymousUid = ANON_UID, accountUid = EXISTING_UID)
        val reassigner = FakeSessionDataReassigner()
        val viewModel = ProfileViewModel(repository, reassigner)
        advanceUntilIdle()

        viewModel.updateEmail("mia@ejemplo.com")
        viewModel.updatePassword("secreto123")
        viewModel.onClaimIntoExistingAccount()
        advanceUntilIdle()

        assertEquals(ANON_UID, reassigner.lastPair?.first)
        assertEquals(EXISTING_UID, reassigner.lastPair?.second)
        assertFalse("a no-op re-key is the bug, not an acceptable outcome", reassigner.wasNoOp)
    }

    @Test
    fun aFailedSignInRekeysNothing() = runTest(dispatcher) {
        val repository = FakeAuthRepository(
            anonymousUid = ANON_UID,
            signInResult = AppResult.Failure(
                AppError.Server(code = null, message = AuthErrorMarkers.WRONG_CREDENTIALS)
            )
        )
        val reassigner = FakeSessionDataReassigner()
        val viewModel = ProfileViewModel(repository, reassigner)
        advanceUntilIdle()

        viewModel.updateEmail("mia@ejemplo.com")
        viewModel.updatePassword("malaclave")
        // Open the form first: the assertion below is that a failure KEEPS it open, which is only
        // meaningful if it was ever opened. Submitting without opening it would pass a broken
        // implementation that closed the form on every outcome.
        viewModel.onShowClaimForm()
        advanceUntilIdle()
        viewModel.onClaimIntoExistingAccount()
        advanceUntilIdle()

        assertEquals(0, reassigner.calls)
        assertNotNull(viewModel.uiState.value.errorMessage)
        assertFalse(viewModel.uiState.value.isLoading)
        // The claim form stays open: the user just mistyped, and closing the form would make them
        // start over from the beginning.
        assertTrue(viewModel.uiState.value.isClaimFormVisible)
    }

    @Test
    fun aRekeyFailureAfterASuccessfulSignInIsReportedAndNotSwallowed() = runTest(dispatcher) {
        // The account IS claimed at this point — Firebase accepted it and the session changed. The
        // rows are still under the old uid. Reporting success here would leave the user believing
        // everything moved, and the goals would silently revert to defaults.
        val repository = FakeAuthRepository(anonymousUid = ANON_UID, accountUid = EXISTING_UID)
        val reassigner = FakeSessionDataReassigner(
            result = AppResult.Failure(AppError.Unknown(cause = null))
        )
        val viewModel = ProfileViewModel(repository, reassigner)
        advanceUntilIdle()

        viewModel.updateEmail("mia@ejemplo.com")
        viewModel.updatePassword("secreto123")
        viewModel.onClaimIntoExistingAccount()
        advanceUntilIdle()

        assertEquals(1, reassigner.calls)
        assertNotNull(
            "a failed re-key must not be reported as a successful claim",
            viewModel.uiState.value.errorMessage
        )
        assertNull(
            "claimedEmail must stay unset so the screen does not claim it went fine",
            viewModel.uiState.value.claimedEmail
        )
        assertFalse(viewModel.uiState.value.isLoading)
    }

    // -- Google ---------------------------------------------------------------------------------

    @Test
    fun aGoogleAccountThatLinksKeepsTheUidAndRekeysNothing() = runTest(dispatcher) {
        val repository = FakeAuthRepository(anonymousUid = ANON_UID, accountUid = EXISTING_UID)
        val reassigner = FakeSessionDataReassigner()
        val viewModel = ProfileViewModel(repository, reassigner)
        advanceUntilIdle()
        viewModel.onShowClaimForm()
        advanceUntilIdle()

        viewModel.onGoogleSignInRequested()
        assertTrue(
            "the flag must go up on the tap, before the account sheet is even open",
            viewModel.uiState.value.isGoogleInProgress
        )
        viewModel.onGoogleSignIn("id-token")
        advanceUntilIdle()

        assertEquals(1, repository.googleSignInCalls)
        assertEquals("id-token", repository.lastGoogleIdToken)
        // `linkWithCredential` returns the SAME uid, so there is nothing to move. Re-keying anyway
        // would be a pointless write that looks correct in every test — and the session has to be
        // COMPLETED for the uid to be readable afterwards, so the comparison is what protects it.
        assertEquals(
            "linking keeps the uid, so nothing may be re-keyed",
            0,
            reassigner.calls
        )
        assertFalse(viewModel.uiState.value.isGoogleInProgress)
        assertFalse(viewModel.uiState.value.showGoogleMergeConfirmation)
        assertFalse(viewModel.uiState.value.isClaimFormVisible)
    }

    @Test
    fun aGoogleAccountThatAlreadyExistsAsksBeforeSwitchingSessions() = runTest(dispatcher) {
        val repository = FakeAuthRepository(
            anonymousUid = ANON_UID,
            accountUid = EXISTING_UID,
            googleSignInResult = AppResult.Failure(
                AppError.Server(code = null, message = AuthErrorMarkers.GOOGLE_ACCOUNT_EXISTS)
            )
        )
        val reassigner = FakeSessionDataReassigner()
        val viewModel = ProfileViewModel(repository, reassigner)
        advanceUntilIdle()
        viewModel.onShowClaimForm()
        advanceUntilIdle()

        viewModel.onGoogleSignInRequested()
        viewModel.onGoogleSignIn("id-token")
        advanceUntilIdle()

        assertTrue(
            "the account exists, so the only route in is signing into it — which ends the guest " +
                "session. That needs the user to say yes first.",
            viewModel.uiState.value.showGoogleMergeConfirmation
        )
        // The load-bearing assertion. Nothing may have happened yet: no session switch, no re-key,
        // and above all NO error message, because "esa cuenta ya existe" is not a failure the user
        // should retry — it is a question they have to answer.
        assertEquals(0, repository.googleReplaceCalls)
        assertEquals(0, reassigner.calls)
        assertNull(viewModel.uiState.value.errorMessage)
        assertFalse(
            "the request itself is finished; only the decision is pending",
            viewModel.uiState.value.isGoogleInProgress
        )
        assertTrue(
            "the claim form must survive, so backing out of the dialog does not lose the email",
            viewModel.uiState.value.isClaimFormVisible
        )
    }

    @Test
    fun confirmingTheMergeSwitchesSessionsAndRekeysTheLocalRows() = runTest(dispatcher) {
        val repository = FakeAuthRepository(
            anonymousUid = ANON_UID,
            accountUid = EXISTING_UID,
            googleReplaceUid = EXISTING_UID,
            googleSignInResult = AppResult.Failure(
                AppError.Server(code = null, message = AuthErrorMarkers.GOOGLE_ACCOUNT_EXISTS)
            )
        )
        val reassigner = FakeSessionDataReassigner()
        val viewModel = ProfileViewModel(repository, reassigner)
        advanceUntilIdle()
        viewModel.onShowClaimForm()
        advanceUntilIdle()

        viewModel.onGoogleSignInRequested()
        viewModel.onGoogleSignIn("id-token")
        advanceUntilIdle()
        viewModel.onGoogleMergeConfirmed()
        advanceUntilIdle()

        assertEquals(1, repository.googleReplaceCalls)
        // The token has to survive the round trip through the dialog. Re-reading it from the
        // launcher would force the user to pick the account twice.
        assertEquals("id-token", repository.lastGoogleReplaceIdToken)
        assertEquals(1, reassigner.calls)
        assertEquals(ANON_UID, reassigner.lastPair?.first)
        assertEquals(EXISTING_UID, reassigner.lastPair?.second)
        assertFalse(viewModel.uiState.value.showGoogleMergeConfirmation)
        assertFalse(viewModel.uiState.value.isClaimFormVisible)
    }

    @Test
    fun dismissingTheMergeLeavesTheGuestSessionExactlyAsItWas() = runTest(dispatcher) {
        val repository = FakeAuthRepository(
            anonymousUid = ANON_UID,
            accountUid = EXISTING_UID,
            googleSignInResult = AppResult.Failure(
                AppError.Server(code = null, message = AuthErrorMarkers.GOOGLE_ACCOUNT_EXISTS)
            )
        )
        val reassigner = FakeSessionDataReassigner()
        val viewModel = ProfileViewModel(repository, reassigner)
        advanceUntilIdle()
        viewModel.onShowClaimForm()
        advanceUntilIdle()

        viewModel.onGoogleSignInRequested()
        viewModel.onGoogleSignIn("id-token")
        advanceUntilIdle()
        viewModel.onGoogleMergeDismissed()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.showGoogleMergeConfirmation)
        assertEquals(
            "saying no must not end the guest session",
            0,
            repository.googleReplaceCalls
        )
        assertEquals(0, reassigner.calls)
        assertTrue(viewModel.uiState.value.isClaimFormVisible)
    }

    @Test
    fun aFailedMergeAfterConfirmationIsReportedAndNotSwallowed() = runTest(dispatcher) {
        val repository = FakeAuthRepository(
            anonymousUid = ANON_UID,
            accountUid = EXISTING_UID,
            googleSignInResult = AppResult.Failure(
                AppError.Server(code = null, message = AuthErrorMarkers.GOOGLE_ACCOUNT_EXISTS)
            ),
            googleReplaceResult = AppResult.Failure(AppError.Network)
        )
        val reassigner = FakeSessionDataReassigner()
        val viewModel = ProfileViewModel(repository, reassigner)
        advanceUntilIdle()
        viewModel.onShowClaimForm()
        advanceUntilIdle()

        viewModel.onGoogleSignInRequested()
        viewModel.onGoogleSignIn("id-token")
        advanceUntilIdle()
        viewModel.onGoogleMergeConfirmed()
        advanceUntilIdle()

        // The session switch failed, so the uid never moved and there is nothing to re-key. The
        // user has to be told, or the dialog just vanishes and looks like the app ignored them.
        assertNotNull(viewModel.uiState.value.errorMessage)
        assertEquals(0, reassigner.calls)
        assertFalse(viewModel.uiState.value.showGoogleMergeConfirmation)
        assertTrue(viewModel.uiState.value.isClaimFormVisible)
    }

    @Test
    fun dismissingTheAccountSheetIsSilentAndLeavesTheFormOpen() = runTest(dispatcher) {
        val repository = FakeAuthRepository()
        val viewModel = ProfileViewModel(repository, FakeSessionDataReassigner())
        advanceUntilIdle()
        viewModel.onShowClaimForm()
        advanceUntilIdle()

        viewModel.onGoogleSignInRequested()
        viewModel.onGoogleSignInCancelled()
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.errorMessage)
        assertFalse(viewModel.uiState.value.isGoogleInProgress)
        assertTrue("cancelling a sheet is not abandoning the claim", viewModel.uiState.value.isClaimFormVisible)
        assertEquals(0, repository.googleSignInCalls)
    }

    @Test
    fun aDeviceWithoutGooglePointsAtTheEmailFormWithoutTouchingTheRepository() =
        runTest(dispatcher) {
            val repository = FakeAuthRepository()
            val viewModel = ProfileViewModel(repository, FakeSessionDataReassigner())
            advanceUntilIdle()

            viewModel.onGoogleSignInRequested()
            viewModel.onGoogleSignInProviderUnavailable()
            advanceUntilIdle()

            val message = viewModel.uiState.value.errorMessage
            assertNotNull(message)
            assertTrue(message.orEmpty().contains("correo", ignoreCase = true))
            assertEquals(0, repository.googleSignInCalls)
            assertFalse(viewModel.uiState.value.isGoogleInProgress)
        }

    // -- Guards ---------------------------------------------------------------------------------

    @Test
    fun claimingIsRefusedWithoutAnAnonymousSessionAndSpendsNoRequest() = runTest(dispatcher) {
        // Reachable if the user taps claim and the session dies underneath, and it is the case where
        // re-keying would be actively harmful: with no anonymous uid there is nothing to move, and
        // guessing would move SOMEONE ELSE's rows.
        val repository = FakeAuthRepository(
            signInAnonymouslyResult = AppResult.Failure(AppError.Network)
        )
        val reassigner = FakeSessionDataReassigner()
        val viewModel = ProfileViewModel(repository, reassigner)
        advanceUntilIdle()

        viewModel.updateEmail("mia@ejemplo.com")
        viewModel.updatePassword("secreto123")
        viewModel.onClaimIntoExistingAccount()
        advanceUntilIdle()

        assertEquals(0, repository.signInCalls)
        assertEquals(0, reassigner.calls)
        assertNotNull(viewModel.uiState.value.errorMessage)
        assertFalse(viewModel.uiState.value.isLoading)
    }

    @Test
    fun anInvalidFormNeverReachesEitherRepository() = runTest(dispatcher) {
        val repository = FakeAuthRepository()
        val reassigner = FakeSessionDataReassigner()
        val viewModel = ProfileViewModel(repository, reassigner)
        advanceUntilIdle()

        viewModel.updateEmail("no-es-un-correo")
        viewModel.updatePassword("123")
        viewModel.onClaimSubmit()
        viewModel.onClaimIntoExistingAccount()
        advanceUntilIdle()

        assertEquals(0, repository.promoteCalls)
        assertEquals(0, repository.signInCalls)
        assertEquals(0, reassigner.calls)
        assertFalse(viewModel.uiState.value.isLoading)
    }

    @Test
    fun aDoubleTapCannotFireTwoClaims() = runTest(dispatcher) {
        // Two promotions of the same address is the fastest route to Firebase's rate limiter, and
        // the second one fails with a message about "too many attempts" that has nothing to do with
        // what the user did.
        val repository = FakeAuthRepository()
        val viewModel = ProfileViewModel(repository, FakeSessionDataReassigner())
        advanceUntilIdle()

        viewModel.updateEmail("mia@ejemplo.com")
        viewModel.updatePassword("secreto123")
        viewModel.onClaimSubmit()
        viewModel.onClaimSubmit()
        advanceUntilIdle()

        assertEquals(1, repository.promoteCalls)
    }

    // -- Fakes ----------------------------------------------------------------------------------

    private companion object {
        const val ANON_UID = "anon-uid-1"
        const val EXISTING_UID = "existing-uid-2"
    }

    /**
     * In-memory [AuthRepository] whose uid actually MOVES on [signIn], the way Firebase's does.
     *
     * That behaviour is the point of the fake: a stub that left `currentUid` alone would let a
     * ViewModel that read the uid after the sign-in pass its test, and that ViewModel would be the
     * one that silently loses the user's goals in production.
     */
    private class FakeAuthRepository(
        private val anonymousUid: String? = ANON_UID,
        private val accountUid: String? = EXISTING_UID,
        private val signInAnonymouslyResult: AppResult<Unit> = AppResult.Success(Unit),
        private val signInResult: AppResult<Unit> = AppResult.Success(Unit),
        private val promoteResult: AppResult<Unit> = AppResult.Success(Unit),
        private val googleSignInResult: AppResult<Unit> = AppResult.Success(Unit),
        private val googleReplaceResult: AppResult<Unit> = AppResult.Success(Unit),
        private val googleReplaceUid: String? = EXISTING_UID
    ) : AuthRepository {

        private val state = MutableStateFlow<AuthState>(AuthState.Unauthenticated)

        var uid: String? = null
            private set

        var signInCalls = 0
            private set
        var promoteCalls = 0
            private set
        var lastPromotion: Pair<String, String>? = null
            private set
        var googleSignInCalls = 0
            private set
        var lastGoogleIdToken: String? = null
            private set

        /** Counts the DESTRUCTIVE switch, which must never happen without an explicit yes. */
        var googleReplaceCalls = 0
            private set

        var lastGoogleReplaceIdToken: String? = null
            private set

        override val authState: Flow<AuthState> = state

        override val currentUid: String? get() = uid

        override suspend fun signInAnonymously(): AppResult<Unit> {
            // The uid is only minted on success, so a failed bootstrap leaves the fake exactly as a
            // real uninitialised Firebase would: no session, no uid, and therefore nothing the
            // claim path could safely re-key.
            if (signInAnonymouslyResult is AppResult.Success) {
                uid = anonymousUid
                state.value = AuthState.Anonymous
            }
            return signInAnonymouslyResult
        }

        override suspend fun promoteToEmailAccount(email: String, password: String): AppResult<Unit> {
            promoteCalls++
            lastPromotion = email to password
            if (promoteResult is AppResult.Success) {
                // SAME uid. This is what makes the promotion path free, and it is why the ViewModel
                // must not re-key after it.
                state.value = AuthState.Authenticated
            }
            return promoteResult
        }

        override suspend fun signIn(email: String, password: String): AppResult<Unit> {
            signInCalls++
            if (signInResult is AppResult.Success) {
                // The uid CHANGES, and the old one is gone. Reading it after this call yields the
                // new value, which is the bug this fake is built to expose.
                uid = accountUid
                state.value = AuthState.Authenticated
            }
            return signInResult
        }

        override suspend fun signInWithGoogle(idToken: String): AppResult<Unit> {
            googleSignInCalls++
            lastGoogleIdToken = idToken
            // The uid is deliberately NOT touched: this is the link path, and linking keeps the
            // same uid. The fake would be lying about the one fact the ViewModel reads to decide
            // whether to re-key.
            if (googleSignInResult is AppResult.Success) {
                state.value = AuthState.Authenticated
            }
            return googleSignInResult
        }

        override suspend fun signInWithGoogleReplacingSession(idToken: String): AppResult<Unit> {
            googleReplaceCalls++
            lastGoogleReplaceIdToken = idToken
            if (googleReplaceResult is AppResult.Success) {
                // This is the destructive switch: the guest uid is gone and a different one is
                // current. Mirrored here so the re-key assertions have something real to check.
                uid = googleReplaceUid
                state.value = AuthState.Authenticated
            }
            return googleReplaceResult
        }

        override suspend fun signUp(email: String, password: String): AppResult<Unit> =
            AppResult.Success(Unit)

        override suspend fun signOut(): AppResult<Unit> {
            uid = null
            state.value = AuthState.Unauthenticated
            return AppResult.Success(Unit)
        }
    }

    /** Records what it was asked to re-key and whether that would have been a no-op. */
    private class FakeSessionDataReassigner(
        private val result: AppResult<Unit> = AppResult.Success(Unit)
    ) : SessionDataReassigner {

        var calls = 0
            private set
        var lastPair: Pair<String, String>? = null
            private set

        val wasNoOp: Boolean get() = lastPair?.first == lastPair?.second

        override suspend fun reassign(
            fromUserId: String,
            toUserId: String
        ): AppResult<Unit> {
            calls++
            lastPair = fromUserId to toUserId
            return result
        }
    }
}
