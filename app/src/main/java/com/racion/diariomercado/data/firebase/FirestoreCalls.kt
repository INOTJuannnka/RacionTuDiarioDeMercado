package com.racion.diariomercado.data.firebase

import com.racion.diariomercado.core.AppError
import com.racion.diariomercado.core.AppResult

/**
 * The two things every Firestore repository needs and that must not be re-invented per repository.
 *
 * ## Why these are shared
 * Both helpers encode a contract that lives on [AppResult], not on any one repository, so a
 * per-repository copy would be a fourth and fifth place to get it subtly wrong.
 */

/**
 * Folds a Firestore write into the [AppResult] the domain contract mandates.
 *
 * ## Why `inline` and a non-`suspend` block
 * The block returns the **awaited** value, not a `Task`. Declaring the block without `suspend` is
 * only possible because this function is `inline`, which lets the `awaitTask()` call inside each
 * block sit in the caller's own suspend context. Marking it `suspend` would work identically and
 * read more honestly, but then the `try` could not wrap the suspension point without a
 * `coroutineScope` — and the whole point is that **the network call is inside the `try`**. A helper
 * that returned a `Task` and awaited it outside would let a failure escape the `catch`, which is
 * the one thing `AppResult`'s "no method may throw" contract forbids.
 *
 * ## Why `Exception` and not `Throwable`
 * A raw exception must never escape a repository. `Error` is deliberately not caught: the stubs
 * these replaced threw `NotImplementedError`, and a `catch (e: Exception)` would not have caught
 * them. Nothing in a real implementation throws an `Error`.
 */
internal inline fun <T> runFirestoreWrite(block: () -> T): AppResult<Unit> = try {
    block()
    AppResult.Success(Unit)
} catch (e: Exception) {
    AppResult.Failure(e.toFirestoreAppError())
}

/**
 * The failure returned when there is no session to write under.
 *
 * ## Why this is a [AppError.Server] with a null code, and why that is a known compromise
 * The taxonomy in `core/AppResult.kt` has no `Unauthenticated` bucket. The alternatives were to
 * borrow [AppError.Network] — actively misleading, because the common cause is a rejected security
 * rule or a failed anonymous sign-in, not a dead connection — or to add a variant to a shared
 * sealed interface, which would break every exhaustive `when (error)` in the app and reach well
 * past this lane.
 *
 * [AppError.Server] is chosen because it is the only bucket that carries a human-readable message
 * with no code attached, so nothing is invented and the real cause survives into the logs.
 *
 * The proper fix — an `AppError.Unauthenticated` — is tracked as a follow-up, not smuggled in here.
 */
internal fun noSessionFailure(writing: String): AppResult<Nothing> = AppResult.Failure(
    AppError.Server(
        code = null,
        message = "No Firebase session: cannot write $writing without an authenticated uid."
    )
)
