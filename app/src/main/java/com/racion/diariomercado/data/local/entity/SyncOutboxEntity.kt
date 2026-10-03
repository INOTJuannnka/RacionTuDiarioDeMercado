package com.racion.diariomercado.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.racion.diariomercado.domain.model.MealSlot

/**
 * The two things the sync can owe the server about one entry.
 *
 * Stored by `name`, never by ordinal and never by a display label — same rule as
 * [MealSlot]. The remote contract is a *set* keyed on the entry id: an [UPSERT] makes the server's
 * copy of that entry equal to the payload, and a [DELETE] makes it not exist.
 */
enum class SyncOutOp {
    UPSERT,
    DELETE
}

/**
 * One unit of pending sync work: "the server still owes this entry an [SyncOutOp.UPSERT] or an
 * [SyncOutOp.DELETE], and here is everything needed to say so".
 *
 * ## Why a table and not a dirty-flag column on `diary_entries` (design D2)
 * Because a **delete needs the deleted row's nutrition values** to apply the compensating
 * `decrement` on the remote day aggregate. `FirestoreDiaryRepository` keeps a precomputed
 * `days/{yyyy-MM-dd}` total rather than summing on read, so removing an entry means subtracting its
 * kcal and all six macros from that document — and those numbers exist nowhere once the row is
 * gone. A `dirty` boolean cannot outlive its own row, so the flag-column design cannot express the
 * half of the sync contract that actually needs data.
 *
 * Soft-delete on `diary_entries` was the other candidate and it loses for a concrete reason: the
 * tombstone would keep holding the `RESTRICT` foreign key on `productBarcode`, so every barcode the
 * user ever logged would become permanently un-evictable from the catalog cache. A cache trim that
 * silently cannot trim is a cache that grows forever. The outbox row carries the payload and leaves
 * `diary_entries` with exactly the semantics its 19 `DiaryDaoTest` assertions already describe.
 *
 * ## There is deliberately NO foreign key to `diary_entries`
 * This is the load-bearing decision of the whole table, and it is what a DELETE row *is*: the
 * outbox has to remember an entry that no longer exists locally. A foreign key would force a
 * choice between the two ways to be wrong — either it blocks the `DELETE FROM diary_entries` that
 * this row exists to record, or `CASCADE`/`SET_NULL` silently drops the sync record the moment the
 * entry goes, which is the exact class of silent data loss this feature exists to prevent.
 *
 * ## [entryId] as PRIMARY KEY *is* the coalescing rule
 * At most one pending row per entry, and Room's `@Upsert` collapses a repeat in a single atomic
 * SQL statement (`INSERT ... ON CONFLICT(entryId) DO UPDATE SET ...`). That is the whole
 * implementation of the rule, and its value is that it cannot be bypassed: a future caller who
 * forgets to check "is this entry already queued?" gets correct behaviour anyway, whereas a
 * check-then-insert or check-then-update written in Kotlin is a race between two coroutines
 * scanning two products at once, and the outbox is written from exactly that kind of path.
 *
 * The three collapses, all pinned by tests in `SyncOutboxDaoTest`:
 * - **UPSERT → UPSERT**: one row, newest payload. The server is keyed on `entryId`, so sending the
 *   earlier payload first and the newer one second reaches the same state — and sending only the
 *   newer one reaches it in a single request.
 * - **DELETE → DELETE**: one row, newest payload. Same argument; the second delete re-states a
 *   non-existence the first one already established.
 * - **UPSERT → DELETE**: one row, and it is a DELETE. This is the one that is not obvious, so it
 *   is stated rather than inferred: the net effect of the two local writes is that the entry does
 *   not exist, and **an upsert that arrived first would resurrect it**. Deleting locally and
 *   syncing the delete is only correct if the server is told the entry is gone. Collapsing to
 *   UPSERT instead would leave a ghost entry on the server that no local row can ever correct,
 *   because a later `deleteEntry` for an already-absent id is a documented no-op success and would
 *   never produce another DELETE row.
 *
 * ## All seven nutrition columns, flattened, and none of them optional
 * Same reason `DiaryEntryEntity` flattens them rather than nesting a value object: a `SUM` and a
 * partial projection are both easy, and both lie. If this table carried six of the seven, a delete
 * would decrement the remote day total by zero grams of the missing macro — permanently, because
 * nothing ever recomputes that document from the entries. The symptom is a diary that slowly stops
 * matching itself, which is the failure mode an offline-first app can least afford.
 *
 * ## Product identity is a barcode and a name, and not more
 * The remote entry document stores which product this was and what it is called; the rest of
 * `FoodProduct` (brand, per-100 g nutrition, image, nutriscore, categories, ingredients, emoji)
 * belongs to the product document, which the catalog leg of the sync owns. Copying the whole
 * product into every pending row would make the outbox a second, divergent copy of the catalog
 * that nothing ever prunes — and it would have to be kept in step with `FoodProduct` forever.
 *
 * ## [op] is the constant name in a TEXT column
 * `MealSlot` goes through a `Converters` entry; this does not, and the asymmetry is intentional.
 * The column exists to be read by a human holding a hex editor on a phone whose sync is stuck —
 * `op = 'DELETE'` says what happened without a decode step. `operation` maps it back with `valueOf`,
 * which throws on an unrecognised name: a queue that has been corrupted should stop loudly rather
 * than guess an operation and apply the wrong one to the server's totals. (The `Converters`
 * fallback of substituting a default is right for a *display* field and wrong here.)
 */
@Entity(
    tableName = "sync_outbox",
    indices = [
        // The drain is `ORDER BY enqueuedAtEpochMillis LIMIT :limit`, and this queue holds one row
        // per touched entry — after a week offline with no signal that is hundreds, not tens. The
        // index turns the drain into a bounded index scan with early termination instead of
        // sorting the whole table on every attempt. Declared rather than inferred so the ordering
        // contract is visible in the exported schema, which is what a migration test reads.
        Index(value = ["enqueuedAtEpochMillis"])
    ]
)
data class SyncOutboxEntity(
    /**
     * The diary entry this row is about, and the coalescing key. Client-generated, so it is the
     * same value on both sides of the sync and a retried upload cannot duplicate a server document.
     */
    @PrimaryKey
    val entryId: String,

    /** [SyncOutOp] constant name. See the class note on why this is a `String`. */
    val op: String,

    val productBarcode: String,
    val productName: String,
    val servings: Int,
    val mealSlot: MealSlot,
    val loggedAtEpochMillis: Long,

    /**
     * Which remote `days/{yyyy-MM-dd}` document this belongs to, loaded rather than recomputed.
     *
     * Day-key formatting is a mapping-layer concern and `DiaryDao`'s week range depends on the zero
     * padding; a second implementation of that rule in the sync path is how a payload starts
     * landing on the wrong day document.
     */
    val dayKey: String,

    // The entry's nutrition, already scaled for `servings`. Flattened for the same reason as
    // `DiaryEntryEntity`, and all seven are load-bearing — see the class note.
    val kcal: Int,
    val carbsG: Double,
    val proteinG: Double,
    val fatG: Double,
    val sugarsG: Double,
    val fiberG: Double,
    val sodiumG: Double,

    /**
     * When this row became pending, refreshed on every collapse.
     *
     * Refreshed, not preserved, and that is the fairness property: an entry the user keeps
     * re-editing goes to the back of the queue instead of holding the head until it stops
     * changing. "Oldest pending first" therefore means least-recently-touched first, which is the
     * order that gets a long-offline queue drained evenly rather than in edit-recency order.
     *
     * Pure queue bookkeeping. It is deliberately absent from `SyncTransport`'s payload: it describes
     * this phone's queue, not the entry, and shipping it would make every transport implementation
     * know to drop it before writing the remote document.
     */
    val enqueuedAtEpochMillis: Long
) {
    /** The typed operation. Throws on an unrecognised [op] — see the class note. */
    val operation: SyncOutOp
        get() = SyncOutOp.valueOf(op)
}