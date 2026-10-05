package com.racion.diariomercado

import android.app.Application
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FirebaseFirestore
import com.racion.diariomercado.core.AppResult
import com.racion.diariomercado.di.AppContainer
import com.racion.diariomercado.domain.repository.AuthState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Application entry point and owner of the dependency graph.
 *
 * There is no Hilt here on purpose: the object graph is a handful of singletons assembled in
 * [AppContainer], which is cheaper to reason about than an annotation processor and keeps the
 * build fast while the data layer is still being written.
 *
 * This is also where the app **boots its session**, which is the one piece of startup work that has
 * to happen before anything else touches the network. See [bootstrapAnonymousSession].
 */
class RacionApplication : Application() {

    /**
     * The object graph. Created once in [onCreate] and read by the Activity.
     * `internal` visibility would be wrong here: [MainActivity] is in the same module, but keeping
     * the property readable is what makes the container testable.
     */
    lateinit var container: AppContainer
        private set

    /**
     * Application-lifetime scope for work that must outlive any one screen.
     *
     * [SupervisorJob] rather than `Job` deliberately: a failed child must not cancel the scope and
     * take every later bootstrap with it. One failed anonymous sign-in must not permanently disable
     * the retry on the next launch.
     *
     * This is the only place in the app that owns a long-lived scope, and it is not cancelled — the
     * process outlives it by definition, so there is nothing to cancel against. It exists because
     * [bootstrapAnonymousSession] is a `suspend` function and [onCreate] is not.
     */
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()

        // FF-2: bring Firebase up BEFORE anything can ask it for an instance.
        //
        // In practice `FirebaseInitProvider` (auto-registered by the google-services plugin) has
        // already done this by the time onCreate runs, and this call is a no-op that returns the
        // existing default app. It is kept explicit because it documents the ordering requirement
        // and because it is the ONLY thing standing between a missing config and an
        // IllegalStateException thrown later from `FirebaseAuth.getInstance()`.
        //
        // The `null` return is the real failure signal here: it means the resources generated from
        // `google-services.json` are absent, i.e. the plugin is not applied or the file is missing.
        // Logging rather than crashing is deliberate — the anonymous sign-in below reports the same
        // problem through its own AppResult, and the UI has to render it as a message.
        val firebaseApp = FirebaseApp.initializeApp(this)
        if (firebaseApp == null) {
            Log.e(
                TAG,
                "FirebaseApp.initializeApp returned null. The google-services plugin is not " +
                    "applied or app/google-services.json is unreadable. Auth will fail."
            )
        }

        container = AppContainer(this)

        bootstrapAnonymousSession()

        initializeFirestoreIfReady(firebaseApp)
    }

    /**
     * Resolves the process-wide [FirebaseFirestore] instance at the one point where doing so is
     * safe.
     *
     * ## Why this is eagerly resolved here, while the repositories resolve lazily
     * Two ordering constraints pull in opposite directions and this method is where they meet.
     *
     * - `setFirestoreSettings()` must run before *any* other call on the instance, including a
     *   snapshot listener, or it throws `IllegalStateException` at runtime rather than at compile
     *   time. Touching Firestore before settings are applied is the trap this file documents.
     * - But `bootstrapAnonymousSession()` has to come first regardless, because the security rules
     *   are keyed on a `currentUser` that does not exist yet — the first Firestore read would
     *   otherwise be denied and that permission error reads like a rules bug rather than a missing
     *   sign-in.
     *
     * Instantiating here, immediately after both, makes "settings before first use" true by
     * construction instead of by convention: no repository can reach an unconfigured instance,
     * because the only instance exists by the time the UI runs.
     *
     * The repositories still call `getInstance()` lazily inside each method. That is not a second
     * instance — Firebase owns the singleton — it is the same lesson [com.racion.diariomercado.data.firebase.FirebaseAuthRepository]
     * already learned: the container must not depend on initialisation *ordering*. The eager call
     * here fixes the ordering; the lazy call keeps the failure attached to the call that needs it.
     *
     * ## Why no `setFirestoreSettings(...)` call
     * Nothing here needs a non-default setting. Offline persistence is already on through
     * `PersistentCacheSettings`, and the deprecated `setPersistenceEnabled()` is ignored on recent
     * SDKs, so calling it would be dead code that looks like configuration. If a real need appears
     * — a smaller cache size, a different host — it belongs here, in this method, before the
     * repositories ever resolve the instance. Do not add it anywhere else.
     *
     * ## Why a `null` `FirebaseApp` is not fatal
     * `FirebaseApp.initializeApp` returning `null` means the generated resources are missing, and
     * `getInstance()` would throw. That is already logged above and surfaces again through every
     * `AppResult`, so throwing here would only replace a renderable message with a crash on
     * startup.
     */
    private fun initializeFirestoreIfReady(firebaseApp: FirebaseApp?) {
        if (firebaseApp == null) {
            Log.e(TAG, "Skipping Firestore init: FirebaseApp is null. Every read will fail to map.")
            return
        }
        runCatching { FirebaseFirestore.getInstance() }
            .onFailure {
                Log.e(TAG, "FirebaseFirestore.getInstance() failed at startup.", it)
            }
    }

    /**
     * Requests the anonymous session the whole app runs on, then leaves the result to be observed.
     *
     * ## Why this is here and not in a screen
     * Anonymous-first means the user gets a usable `uid` with no form, so somebody has to ask for it.
     * It cannot be a screen's job: a `uid` is what the security rules and every
     * `users/{uid}/...` document path are keyed on, so the FIRST Firestore read would race the
     * sign-in and be denied by the rules — and that permission error reads like a rules bug rather
     * than a missing sign-in. Doing it in `onCreate` is what makes "before any Firestore call" true by
     * construction instead of by convention.
     *
     * ## Why the return value is ignored
     * The resulting session is read from `AuthRepository.authState`, which the UI collects. Treating
     * this result as the state would be a second source of truth for the same fact, and the two
     * disagree for a window after every launch. The failure is therefore not thrown away — it is
     * logged, and the flow still emits [AuthState.Unauthenticated], which the profile screen
     * renders honestly as "no session" instead of hanging on a spinner.
     *
     * ## Why it is called unconditionally
     * `signInAnonymously()` is idempotent with respect to an existing session: when a user is
     * already signed in it resolves with THAT user rather than creating a second account, so there
     * is no `currentUser == null` pre-check to keep in sync. Calling it on every launch also
     * self-heals the case where a previous attempt failed — a permanent sign-out on a network error
     * would otherwise need its own retry path.
     *
     * Deliberately NOT awaited: blocking `onCreate` on a network round trip is an ANR, and the UI is
     * built from the authState flow rather than from a blocking result.
     */
    private fun bootstrapAnonymousSession() {
        applicationScope.launch {
            val result = container.authRepository.signInAnonymously()
            if (result is AppResult.Failure) {
                Log.w(TAG, "Anonymous sign-in failed: ${result.error}")
            }
        }
    }

    private companion object {
        const val TAG = "RacionApplication"
    }
}