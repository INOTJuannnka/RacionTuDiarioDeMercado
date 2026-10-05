package com.racion.diariomercado.data.sync

import com.google.firebase.firestore.FieldValue
import com.racion.diariomercado.core.AppError
import com.racion.diariomercado.core.AppResult
import com.racion.diariomercado.data.firebase.toFirestoreAppError
import com.racion.diariomercado.domain.model.MealSlot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests for [FirestoreSyncTransport] against a fake [FirestoreOperations].
 *
 * ## Why a fake instead of the emulator or Mockito
 * The transport's contract is pure mapping: payload → document fields and error translation.
 * A hand-written fake lets us assert the exact document paths, field values, and error
 * mapping without a single network round trip or mocking framework coupling.
 *
 * ## Idempotence is the load-bearing property
 * [SyncTransport] requires both operations to be idempotent per [entryId]. The test suite pins
 * this by repeating calls and asserting the fake received the same write twice with no side
 * effect — the second call must not fail or create a duplicate.
 */
@RunWith(RobolectricTestRunner::class)
class FirestoreSyncTransportTest {

    private lateinit var transport: FirestoreSyncTransport
    private lateinit var fakeOperations: FakeFirestoreOperations
    private val testUid = "test-uid-123"

    @Before
    fun setUp() {
        fakeOperations = FakeFirestoreOperations()
        transport = FirestoreSyncTransport(
            operations = fakeOperations,
            currentUid = { testUid }
        )
    }

    private fun createPayload(
        entryId: String = "entry-1",
        dayKey: String = "2026-03-02"
    ): SyncEntryPayload = SyncEntryPayload(
        entryId = entryId,
        productBarcode = "7791234567890",
        productName = "Yogur natural",
        servings = 2,
        mealSlot = MealSlot.ALMUERZO,
        loggedAtEpochMillis = 1_772_419_200_000L,
        dayKey = dayKey,
        kcal = 200,
        carbsG = 30.0,
        proteinG = 10.0,
        fatG = 5.0,
        sugarsG = 4.0,
        fiberG = 2.0,
        sodiumG = 300.0
    )

    @Test
    fun `upsertEntry writes entry document with all fields`() = runTest {
        val payload = createPayload()

        val result = transport.upsertEntry(payload)

        assertEquals(AppResult.Success(Unit), result)

        // Verify the write was recorded
        assertEquals(1, fakeOperations.batchWrites.size)
        val write = fakeOperations.batchWrites.single()
        assertEquals("users/$testUid/days/2026-03-02/entries/entry-1", write.path)
        assertTrue(write.merge)

        val data = write.data
        assertEquals("entry-1", data["entryId"])
        assertEquals("7791234567890", data["productBarcode"])
        assertEquals("Yogur natural", data["productName"])
        assertEquals(2, data["servings"])
        assertEquals("ALMUERZO", data["mealSlot"])
        assertEquals(1_772_419_200_000L, data["loggedAtEpochMillis"])
        assertEquals("2026-03-02", data["dayKey"])
        assertEquals(200, data["kcal"])
        assertEquals(30.0, data["carbsG"])
        assertEquals(10.0, data["proteinG"])
        assertEquals(5.0, data["fatG"])
        assertEquals(4.0, data["sugarsG"])
        assertEquals(2.0, data["fiberG"])
        assertEquals(300.0, data["sodiumG"])
    }

    @Test
    fun `upsertEntry is idempotent - repeat call succeeds with same data`() = runTest {
        val payload = createPayload()

        transport.upsertEntry(payload)
        val result2 = transport.upsertEntry(payload)

        assertEquals(AppResult.Success(Unit), result2)
        // Second call should also be recorded (idempotent full replace)
        assertEquals(2, fakeOperations.batchWrites.size)
    }

    @Test
    fun `upsertEntry returns failure when no session`() = runTest {
        val transportNoSession = FirestoreSyncTransport(
            operations = fakeOperations,
            currentUid = { null }
        )
        val payload = createPayload()

        val result = transportNoSession.upsertEntry(payload)

        assertTrue(result is AppResult.Failure)
        (result as AppResult.Failure).let {
            assertTrue(it.error is AppError.Server)
            (it.error as AppError.Server).let {
                assertNull(it.code)
                assertEquals("No Firebase session: cannot write entry upsert without an authenticated uid.", it.message)
            }
        }
    }

@Test
    fun `deleteEntry removes entry document and decrements day totals`() = runTest {
        val payload = createPayload()

        // Set up existing day totals
        fakeOperations.dayDocuments["users/$testUid/days/2026-03-02"] = mapOf(
            "kcal" to 500,
            "carbsG" to 60.0,
            "proteinG" to 20.0,
            "fatG" to 15.0,
            "sugarsG" to 10.0,
            "fiberG" to 5.0,
            "sodiumG" to 600.0
        )

        val result = transport.deleteEntry(payload)

        assertEquals(AppResult.Success(Unit), result)

        // Verify entry document was deleted
        val deleteOps = fakeOperations.transactionDeletes.filter { it == "users/$testUid/days/2026-03-02/entries/entry-1" }
        assertEquals("entry delete should be recorded", 1, deleteOps.size)

        // Verify day document was updated with decremented values
        val dayUpdates = fakeOperations.transactionUpdates.filter { it.path == "users/$testUid/days/2026-03-02" }
        assertEquals("day update should be recorded", 1, dayUpdates.size)

        val updateData = dayUpdates.single().data
        // All nutrition fields should be present with FieldValue.increment(-value)
        assertTrue("kcal key missing", updateData.containsKey("kcal"))
        assertTrue("carbsG key missing", updateData.containsKey("carbsG"))
        assertTrue("proteinG key missing", updateData.containsKey("proteinG"))
        assertTrue("fatG key missing", updateData.containsKey("fatG"))
        assertTrue("sugarsG key missing", updateData.containsKey("sugarsG"))
        assertTrue("fiberG key missing", updateData.containsKey("fiberG"))
        assertTrue("sodiumG key missing", updateData.containsKey("sodiumG"))

        // Verify all values are FieldValue instances (increment operations)
        updateData.values.forEach { assertTrue(it is FieldValue) }
    }

    @Test
    fun `deleteEntry is idempotent - deleting non-existent entry succeeds`() = runTest {
        val payload = createPayload()

        // No day document exists
        fakeOperations.dayDocuments.clear()

        val result = transport.deleteEntry(payload)

        assertEquals(AppResult.Success(Unit), result)

        // Should still attempt to delete entry (idempotent)
        val deleteOps = fakeOperations.transactionDeletes.filter { it == "users/$testUid/days/2026-03-02/entries/entry-1" }
        assertEquals(1, deleteOps.size)

        // Should NOT update day totals since document doesn't exist
        val dayUpdates = fakeOperations.transactionUpdates.filter { it.path == "users/$testUid/days/2026-03-02" }
        assertEquals(0, dayUpdates.size)
    }

    @Test
    fun `deleteEntry returns failure when no session`() = runTest {
        val transportNoSession = FirestoreSyncTransport(
            operations = fakeOperations,
            currentUid = { null }
        )
        val payload = createPayload()

        val result = transportNoSession.deleteEntry(payload)

        assertTrue(result is AppResult.Failure)
        (result as AppResult.Failure).let {
            assertTrue(it.error is AppError.Server)
            (it.error as AppError.Server).let {
                assertNull(it.code)
                assertEquals("No Firebase session: cannot write entry delete without an authenticated uid.", it.message)
            }
        }
    }

    @Test
    fun `currentUid is read per call not at construction`() = runTest {
        var uidCallCount = 0
        val transport2 = FirestoreSyncTransport(
            operations = fakeOperations,
            currentUid = {
                uidCallCount++
                "uid-$uidCallCount"
            }
        )

        val payload1 = createPayload(entryId = "entry-1")
        val payload2 = createPayload(entryId = "entry-2")

        transport2.upsertEntry(payload1)
        transport2.upsertEntry(payload2)

        assertEquals(2, uidCallCount)
    }

    // ─── Fake FirestoreOperations ────────────────────────────────────

    /**
     * Fake implementation of [FirestoreOperations] that records all operations for verification.
     */
    private class FakeFirestoreOperations : FirestoreOperations {

        data class BatchWrite(val path: String, val data: Map<String, Any>, val merge: Boolean)
        data class TransactionUpdate(val path: String, val data: Map<String, Any>)

        val batchWrites = mutableListOf<BatchWrite>()
        val transactionUpdates = mutableListOf<TransactionUpdate>()
        val transactionDeletes = mutableListOf<String>()
        val dayDocuments = mutableMapOf<String, Map<String, Any>>()
        var failNextBatch: Exception? = null
        var failNextTransaction: Exception? = null

        override suspend fun runBatch(block: (FirestoreOperations.Batch) -> Unit): AppResult<Unit> {
            if (failNextBatch != null) {
                val ex = failNextBatch!!
                failNextBatch = null
                return AppResult.Failure(ex.toFirestoreAppError())
            }
            val batch = FakeBatch()
            block(batch)
            batchWrites.addAll(batch.writes)
            return AppResult.Success(Unit)
        }

        override suspend fun runTransaction(block: (FirestoreOperations.Transaction) -> Unit): AppResult<Unit> {
            if (failNextTransaction != null) {
                val ex = failNextTransaction!!
                failNextTransaction = null
                return AppResult.Failure(ex.toFirestoreAppError())
            }
            val transaction = FakeTransaction(dayDocuments)
            block(transaction)
            transactionUpdates.addAll(transaction.updates)
            transactionDeletes.addAll(transaction.deletes)
            return AppResult.Success(Unit)
        }

        private class FakeBatch : FirestoreOperations.Batch {
            val writes = mutableListOf<BatchWrite>()
            override fun set(path: String, data: Map<String, Any>, merge: Boolean) {
                writes += BatchWrite(path, data, merge)
            }
            override fun delete(path: String) {
                // Not used in current implementation
            }
        }

        private class FakeTransaction(
            private val dayDocuments: MutableMap<String, Map<String, Any>>
        ) : FirestoreOperations.Transaction {
            val updates = mutableListOf<TransactionUpdate>()
            val deletes = mutableListOf<String>()
            override fun get(path: String): Map<String, Any>? = dayDocuments[path]
            override fun update(path: String, data: Map<String, Any>) {
                updates += TransactionUpdate(path, data)
            }
            override fun delete(path: String) {
                deletes += path
            }
        }
    }
}