package com.racion.diariomercado.data.sync

import com.google.firebase.firestore.FieldValue
import com.racion.diariomercado.core.AppError
import com.racion.diariomercado.core.AppResult
import com.racion.diariomercado.data.firebase.noSessionFailure

/**
 * Firestore implementation of [SyncTransport].
 *
 * ## Document structure
 * - Entry: `users/{uid}/days/{dayKey}/entries/{entryId}` (full replace, merge=true for idempotence)
 * - Day totals: `users/{uid}/days/{dayKey}` (increment/decrement via FieldValue.increment)
 *
 * ## Idempotence
 * - `upsertEntry`: Uses `SetOptions.merge()` so repeating the same `entryId` is a no-op that
 *   leaves the same document state.
 * - `deleteEntry`: Deletes the entry document (idempotent on Firestore) and decrements day totals
 *   only if the day document exists. Repeating a delete for an already-absent entry succeeds
 *   without changing totals.
 *
 * ## Error handling
 * All Firestore exceptions are caught and translated via [toFirestoreAppError()], never thrown.
 * The caller receives `AppResult.Failure` with the mapped `AppError`.
 */
class FirestoreSyncTransport(
    private val operations: FirestoreOperations,
    private val currentUid: () -> String?
) : SyncTransport {

    override suspend fun upsertEntry(payload: SyncEntryPayload): AppResult<Unit> {
        val uid = currentUid() ?: return noSessionFailure("entry upsert")
        val entryPath = "users/$uid/days/${payload.dayKey}/entries/${payload.entryId}"
        val data = buildEntryDocument(payload)

        return operations.runBatch { batch ->
            batch.set(entryPath, data, merge = true)
        }
    }

    override suspend fun deleteEntry(payload: SyncEntryPayload): AppResult<Unit> {
        val uid = currentUid() ?: return noSessionFailure("entry delete")
        val dayPath = "users/$uid/days/${payload.dayKey}"
        val entryPath = "users/$uid/days/${payload.dayKey}/entries/${payload.entryId}"

        return operations.runTransaction { transaction ->
            // Read day document to get current totals
            val dayData = transaction.get(dayPath)
            if (dayData != null) {
                // Decrement each nutrition field by the payload's values
                val updates = mapOf(
                    "kcal" to FieldValue.increment(-payload.kcal.toLong()),
                    "carbsG" to FieldValue.increment(-payload.carbsG),
                    "proteinG" to FieldValue.increment(-payload.proteinG),
                    "fatG" to FieldValue.increment(-payload.fatG),
                    "sugarsG" to FieldValue.increment(-payload.sugarsG),
                    "fiberG" to FieldValue.increment(-payload.fiberG),
                    "sodiumG" to FieldValue.increment(-payload.sodiumG)
                )
                transaction.update(dayPath, updates)
            }
            // Delete entry document (idempotent: deleting non-existent doc succeeds)
            transaction.delete(entryPath)
        }
    }

    /**
     * Builds the full entry document from payload.
     * All fields are included for a complete replace; merge=true makes this idempotent.
     */
    private fun buildEntryDocument(payload: SyncEntryPayload): Map<String, Any> = mapOf(
        "entryId" to payload.entryId,
        "productBarcode" to payload.productBarcode,
        "productName" to payload.productName,
        "servings" to payload.servings,
        "mealSlot" to payload.mealSlot.name,
        "loggedAtEpochMillis" to payload.loggedAtEpochMillis,
        "dayKey" to payload.dayKey,
        "kcal" to payload.kcal,
        "carbsG" to payload.carbsG,
        "proteinG" to payload.proteinG,
        "fatG" to payload.fatG,
        "sugarsG" to payload.sugarsG,
        "fiberG" to payload.fiberG,
        "sodiumG" to payload.sodiumG
    )
}