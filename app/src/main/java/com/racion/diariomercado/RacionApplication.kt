package com.racion.diariomercado

import android.app.Application
import android.util.Log
import com.google.firebase.FirebaseApp
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

        // TODO(FF-5): create the Firestore instance here and configure it ONCE.
        //
        // TRAP (FF-5): FirebaseFirestore.setFirestoreSettings() MUST run before ANY other call on
        // that Firestore instance, including a snapshot listener or a get() — otherwise it throws
        // IllegalStateException at runtime, not at compile time. That is why it belongs here and not
        // lazily inside a repository. The anonymous sign-in above has to come first regardless,
        // because the security rules are keyed on a currentUser that does not exist yet.
        //
        // Do NOT call the deprecated setPersistenceEnabled(): offline persistence is already ON by
        // default through PersistentCacheSettings, and the old call is ignored on recent SDKs.
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