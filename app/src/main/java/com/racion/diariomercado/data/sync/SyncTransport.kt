package com.racion.diariomercado.data.sync

import com.racion.diariomercado.core.AppResult
import com.racion.diariomercado.data.local.entity.SyncOutboxEntity
import com.racion.diariomercado.domain.model.MealSlot

/**
 * Everything the server needs to know about one diary entry, and nothing about this phone's queue.
 *
 * ## Why this is a separate type instead of passing `SyncOutboxEntity` straight through
 * `SyncOutboxEntity` has a field this payload must not carry: `enqueuedAtEpochMillis`. That value
 * describes *this* device's backlog — when the phone decided to tell the server something — and it
 * has no meaning on the remote side. A transport that took the entity would either write it into
 * the entry document (leaking local queue state into the server, and making every implementation
 * responsible for stripping a field it should never see) or silently ignore one (making the
 * omission an unstated rule that only holds as long as nobody adds a second such field).
 *
 * [SyncOutboxEntity.toPayload] is the only place that decision is made, so adding the next
 * bookkeeping field to the outbox is a compile error at the mapping instead of a field that
 * quietly leaks.
 *
 * ## All seven nutrition values are here for the same reason they are in the entity
 * A remote write is a full replace of the entry document, so every macro has to be present even
 * when it is zero — a `Double` default of `0.0` is a real value for "this product has no sugar",
 * and omitting it would be indistinguishable from not knowing.
 *
 * ## [entryId] is the server key, which is what makes the drain idempotent
 * `DiarySyncManager` deletes an outbox row only after this returns success, so a crash in between
 * re-sends the same `entryId`. The contract a transport must honour to make that safe is on
 * [SyncTransport.upsertEntry].
 */
data class SyncEntryPayload(
    /** Client-generated id; the remote entry document is keyed on it. */
    val entryId: String,

    val productBarcode: String,
    val productName: String,
    val servings: Int,
    val mealSlot: MealSlot,
    val loggedAtEpochMillis: Long,

    /** The remote `days/{yyyy-MM-dd}` document this entry rolls up into. Loaded, never recomputed. */
    val dayKey: String,

    val kcal: Int,
    val carbsG: Double,
    val proteinG: Double,
    val fatG: Double,
    val sugarsG: Double,
    val fiberG: Double,
    val sodiumG: Double
)

/**
 * The seam where sync leaves the app. Implemented later against Firestore; faked in JVM tests.
 *
 * ## Why an interface and not a `FirestoreDiaryRepository` (design D5)
 * So `DiarySyncManager` is testable with no emulator, no network, and no `FirebaseApp`. A manager
 * that calls `FirebaseFirestore.getInstance()` internally is not testable at all without all three,
 * which is the same argument as the one that keeps the repository behind an interface — and it is
 * why the Firestore implementation of *this* seam is explicitly not in this batch. The seam is
 * cheap now precisely because the alternative was discovered to be expensive later.
 *
 * ## Both operations take the whole [SyncEntryPayload], including delete
 * A `deleteEntry(entryId: String)` would be the tidier signature and would be wrong. The remote day
 * document holds a precomputed total, so confirming a delete means *decrementing* that total by the
 * deleted entry's nutrition (design D2) — and a payload that carries only an id cannot do it. The
 * signature has to be the one the arithmetic requires.
 *
 * ## Implementations must be idempotent per [SyncEntryPayload.entryId]
 * Not an optimisation: it is the durability contract that lets the manager delete a row only after
 * success. A process death between "server accepted" and "row removed" re-sends the same `entryId`,
 * so a second apply has to be a no-op rather than a second increment.
 *
 * ## Failures are returned, never thrown
 * `AppResult` is the established contract for anything that can fail in a way the caller must
 * distinguish, and this is exactly that: the manager's whole recovery policy is "stop at the first
 * failure and leave the rest queued", which requires knowing *that* it failed and *which error*, not
 * catching an exception type it would then have to guess the meaning of. An implementation that
 * throws also has to decide what a thrown `IOException` means, and the honest answer is `Network`
 * — so it should map it and return it instead.
 */
interface SyncTransport {

    /**
     * Makes the server's copy of [SyncEntryPayload.entryId] equal to [payload], creating it if
     * absent. Repeating a call for the same `entryId` must leave the same state, not add to it.
     */
    suspend fun upsertEntry(payload: SyncEntryPayload): AppResult<Unit>

    /**
     * Makes [SyncEntryPayload.entryId] not exist on the server, decrementing the aggregate on
     * [SyncEntryPayload.dayKey] by [payload]'s nutrition. Repeating a call for an already-absent
     * `entryId` is a success that changes nothing — *not* an error, because a re-sent delete after a
     * crash has to be able to complete.
     */
    suspend fun deleteEntry(payload: SyncEntryPayload): AppResult<Unit>
}

/**
 * The whole outbox-row → payload mapping, in one place.
 *
 * An extension function rather than a method on the entity so that `data.local.entity` does not have
 * to know what `data.sync` is: the storage row and the wire payload are different concerns that
 * happen to share seven numbers, and letting Room's entity depend on the transport's vocabulary
 * would make the layering diagram a cycle. The trade is one extra import at the call site, which is
 * where the omission of a field would be noticed anyway.
 *
 * Field-by-field and deliberately not copy-by-copy: `enqueuedAtEpochMillis` is the one field that
 * has no counterpart, and naming every field is what makes "and this one is not sent" a fact a
 * reader can check instead of a thing they have to diff.
 */
fun SyncOutboxEntity.toPayload(): SyncEntryPayload = SyncEntryPayload(
    entryId = entryId,
    productBarcode = productBarcode,
    productName = productName,
    servings = servings,
    mealSlot = mealSlot,
    loggedAtEpochMillis = loggedAtEpochMillis,
    dayKey = dayKey,
    kcal = kcal,
    carbsG = carbsG,
    proteinG = proteinG,
    fatG = fatG,
    sugarsG = sugarsG,
    fiberG = fiberG,
    sodiumG = sodiumG
)