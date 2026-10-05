package com.racion.diariomercado.data.firebase

import com.google.android.gms.tasks.Task
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Bridges a Firebase [Task] to a `suspend` function.
 *
 * This is a local stand-in for `kotlinx.coroutines.tasks.await`. It is the piece Gemini's version
 * silently assumed: that extension is published in `kotlinx-coroutines-play-services`, a separate
 * artifact from `kotlinx-coroutines-android`, and it is not on this module's classpath. Importing
 * it would not compile.
 *
 * ## Why it lives in its own file
 * It was `private` to [FirebaseAuthRepository] and used by eight call sites there. The Firestore
 * repositories need the exact same bridge, and copy-pasting a `suspendCancellableCoroutine` block
 * into three files means three places to fix the cancellation semantics. One definition, shared.
 *
 * `addOnCompleteListener` fires on the main thread once the task settles, which is why the
 * continuation is resumed there and the caller resumes on its own dispatcher. If the coroutine is
 * already cancelled when the task settles, [kotlinx.coroutines.CancellableContinuation.resume]
 * reports a benign `IllegalStateException` about resuming after cancellation rather than corrupting
 * state, so no extra guard is needed on the resume path.
 *
 * Cancellation cannot be pushed into the task: a Firebase write already handed to the network layer
 * is not retractable, so the honest `invokeOnCancellation` block is empty. The task still completes
 * and its result is discarded — which is exactly right for a sign-in, because the *session* is what
 * persists, not the return value, and [com.racion.diariomercado.domain.repository.AuthRepository.authState]
 * is the source of truth for it.
 */
internal suspend fun <T> Task<T>.awaitTask(): T = suspendCancellableCoroutine { continuation ->
    addOnCompleteListener { task ->
        val error = task.exception
        if (error != null) {
            continuation.resumeWithException(error)
        } else {
            continuation.resume(task.result)
        }
    }
    // The task cannot be cancelled, so there is nothing to undo here. See the KDoc.
    continuation.invokeOnCancellation { }
}
