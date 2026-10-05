package com.racion.diariomercado.data.firebase

import com.google.firebase.firestore.FirebaseFirestoreException
import com.racion.diariomercado.core.AppError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins the Firestore status code to [AppError] translation.
 *
 * ## Why this needs Robolectric while the mapping suite does not
 * `FirebaseFirestoreException.Code` builds its `SparseArray` of status objects in a static
 * initialiser. On a bare JVM that throws
 * `RuntimeException: Method get in android.util.SparseArray not mocked`, which surfaces as
 * `ExceptionInInitializerError` — the kind of failure that reads like a Firestore bug and is
 * actually a missing Android runtime. Robolectric supplies the Android class and the static
 * initialiser succeeds, which is the only reason these assertions can run at all.
 *
 * The pure domain mapping lives in `FirestoreMappingTest`, which stays on the fast tier for
 * exactly this reason: do not merge these two classes.
 *
 * ## Why the mapping is worth pinning
 * Every branch here changes what the user is told. `UNAVAILABLE` becoming a generic server fault
 * turns "you are offline" into "something went wrong", which sends people to bug reports instead
 * of turning wifi back on. The reverse mistake is worse: a permission failure, which in production
 * is almost always a security-rules problem, being reported as a retryable network blip.
 */
@RunWith(RobolectricTestRunner::class)
class FirestoreErrorMappingTest {

    @Test
    fun `an unavailable firestore code reads as a network problem not a server fault`() {
        val mapped = firestoreCodeToAppError(FirebaseFirestoreException.Code.UNAVAILABLE, null)

        assertEquals(AppError.Network, mapped)
    }

    @Test
    fun `resource exhaustion reads as rate limited`() {
        val mapped = firestoreCodeToAppError(FirebaseFirestoreException.Code.RESOURCE_EXHAUSTED, null)

        assertEquals(AppError.RateLimited, mapped)
    }

    @Test
    fun `a not found code reads as not found`() {
        val mapped = firestoreCodeToAppError(FirebaseFirestoreException.Code.NOT_FOUND, null)

        assertEquals(AppError.NotFound, mapped)
    }

    /**
     * Permission denied keeps both its numeric code and the SDK message. This is the code path
     * that makes a misconfigured security rule diagnosable from a bug report instead of leaving
     * someone guessing which rule rejected the write.
     */
    @Test
    fun `a permission failure keeps its numeric code and the sdk message`() {
        val mapped = firestoreCodeToAppError(
            FirebaseFirestoreException.Code.PERMISSION_DENIED,
            "Missing or insufficient permissions."
        )

        assertTrue("expected a Server error but was $mapped", mapped is AppError.Server)
        mapped as AppError.Server
        assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED.value(), mapped.code)
        assertEquals("Missing or insufficient permissions.", mapped.message)
    }

    @Test
    fun `an unmapped firestore code still lands on Server rather than being swallowed`() {
        val mapped = firestoreCodeToAppError(FirebaseFirestoreException.Code.INTERNAL, "backend died")

        assertTrue("expected a Server error but was $mapped", mapped is AppError.Server)
        mapped as AppError.Server
        assertEquals(FirebaseFirestoreException.Code.INTERNAL.value(), mapped.code)
        assertEquals("backend died", mapped.message)
    }

    /**
     * Anything that is not a Firestore failure is genuinely unclassified. It must not be forced
     * into one of the specific buckets above, and it must keep its cause so the origin survives.
     */
    @Test
    fun `a non firestore throwable is unknown and keeps its cause`() {
        val cause = IllegalStateException("boom")

        val mapped = cause.toFirestoreAppError()

        assertTrue("expected an Unknown error but was $mapped", mapped is AppError.Unknown)
        assertEquals(cause, (mapped as AppError.Unknown).cause)
    }
}
