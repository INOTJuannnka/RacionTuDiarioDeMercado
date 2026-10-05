package com.racion.diariomercado.data.sync

import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.SetOptions
import com.racion.diariomercado.core.AppResult
import com.racion.diariomercado.data.firebase.toFirestoreAppError
import kotlinx.coroutines.tasks.await

/**
 * Operations required by [FirestoreSyncTransport], extracted to an interface so tests can provide
 * a fake implementation without depending on the Firebase SDK.
 *
 * The interface mirrors the minimal Firestore API surface the transport needs:
 * - Batch writes with merge for idempotent upserts
 * - Transactional read-modify-write for atomic delete-with-decrement
 */
interface FirestoreOperations {

    /**
     * Executes a batch write operation.
     */
    suspend fun runBatch(block: (Batch) -> Unit): AppResult<Unit>

    /**
     * Executes a transaction with read-modify-write semantics.
     */
    suspend fun runTransaction(block: (Transaction) -> Unit): AppResult<Unit>

    /**
     * Represents a batched write operation.
     */
    interface Batch {
        /** Queues a document set with merge semantics (idempotent full replace). */
        fun set(path: String, data: Map<String, Any>, merge: Boolean)

        /** Queues a document delete. */
        fun delete(path: String)
    }

    /**
     * Represents a transaction with read-modify-write capability.
     */
    interface Transaction {
        /**
         * Reads a document within the transaction.
         * Returns null if the document doesn't exist.
         */
        fun get(path: String): Map<String, Any>?

        /** Queues a document update within the transaction. */
        fun update(path: String, data: Map<String, Any>)

        /** Queues a document delete within the transaction. */
        fun delete(path: String)
    }

    companion object {
        /** Creates a production implementation backed by the real FirebaseFirestore. */
        fun create(firestoreProvider: () -> com.google.firebase.firestore.FirebaseFirestore): FirestoreOperations =
            RealFirestoreOperations(firestoreProvider)
    }
}

/**
 * Production implementation of [FirestoreOperations] using the real FirebaseFirestore SDK.
 */
private class RealFirestoreOperations(
    private val firestoreProvider: () -> com.google.firebase.firestore.FirebaseFirestore
) : FirestoreOperations {

    override suspend fun runBatch(block: (FirestoreOperations.Batch) -> Unit): AppResult<Unit> {
        val db = firestoreProvider()
        val batch = db.batch()
        val batchWrapper = RealBatch(db, batch)
        block(batchWrapper)
        return runCatching { batch.commit().await() }.fold(
            onSuccess = { AppResult.Success(Unit) },
            onFailure = { AppResult.Failure(it.toFirestoreAppError()) }
        )
    }

    override suspend fun runTransaction(block: (FirestoreOperations.Transaction) -> Unit): AppResult<Unit> {
        val db = firestoreProvider()
        return runCatching {
            db.runTransaction { tx ->
                val txWrapper = RealTransaction(db, tx)
                block(txWrapper)
            }.await()
        }.fold(
            onSuccess = { AppResult.Success(Unit) },
            onFailure = { AppResult.Failure(it.toFirestoreAppError()) }
        )
    }

    private class RealBatch(
        private val db: com.google.firebase.firestore.FirebaseFirestore,
        private val batch: com.google.firebase.firestore.WriteBatch
    ) : FirestoreOperations.Batch {
        override fun set(path: String, data: Map<String, Any>, merge: Boolean) {
            val ref = db.document(path)
            batch.set(ref, data, SetOptions.merge())
        }

        override fun delete(path: String) {
            val ref = db.document(path)
            batch.delete(ref)
        }
    }

    private class RealTransaction(
        private val db: com.google.firebase.firestore.FirebaseFirestore,
        private val tx: com.google.firebase.firestore.Transaction
    ) : FirestoreOperations.Transaction {
        override fun get(path: String): Map<String, Any>? {
            val ref = db.document(path)
            val snapshot = tx.get(ref)
            return if (snapshot.exists()) snapshot.data else null
        }

        override fun update(path: String, data: Map<String, Any>) {
            val ref = db.document(path)
            tx.update(ref, data)
        }

        override fun delete(path: String) {
            val ref = db.document(path)
            tx.delete(ref)
        }
    }
}