package com.racion.diariomercado.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.racion.diariomercado.data.local.entity.SyncOutboxEntity
import kotlinx.coroutines.flow.Flow

/**
 * The pending sync queue: enqueue on a local write, drain oldest-first when there is a network.
 *
 * ## Enqueue and delete are the only writes
 * There is no "update" because there is nothing to update: [enqueue] is an upsert on `entryId`, so
 * a second write for the same entry *is* the update. See `SyncOutboxEntity`'s class note for the
 * three collapse rules that replaces — including the UPSERT→DELETE one that is not obvious.
 *
 * No `clear()`. A bulk delete on a queue is only ever correct as "every row was confirmed", and
 * that is what a drain does one row at a time; a way to empty the table exists for exactly one
 * reason and it is a bug.
 */
@Dao
interface SyncOutboxDao {

    /**
     * Records pending work for one entry, replacing any row already pending for it.
     *
     * `@Upsert` rather than `@Insert(onConflict = REPLACE)` for a reason the entity spells out:
     * `REPLACE` semantics are a `DELETE` plus an `INSERT` in SQLite, so a plain insert-conflict
     * would look like a delete to anything watching the table. The generated
     * `ON CONFLICT(entryId) DO UPDATE SET ...` touches only the columns it names.
     */
    @Upsert
    suspend fun enqueue(row: SyncOutboxEntity)

    /**
     * The next [limit] rows to upload, least-recently-touched first.
     *
     * A `LIMIT` rather than "everything", because a drain has to be bounded: an unbounded drain on a
     * queue that grew during a week without signal would hold a batch going for as long as the
     * entries took to upload, which on a phone is how a background job turns into a battery
     * complaint.
     *
     * `entryId` is the tiebreaker, matching `DiaryDao`'s "ordering is total, not incidental": two
     * rows enqueued in the same millisecond are ordinary (the confirm screen writes fast), and
     * without a deterministic tiebreaker a `LIMIT` query can return a different set on each call.
     */
    @Query(
        """
        SELECT * FROM sync_outbox
        ORDER BY enqueuedAtEpochMillis ASC, entryId ASC
        LIMIT :limit
        """
    )
    suspend fun oldestPending(limit: Int): List<SyncOutboxEntity>

    /**
     * Drops one row, after the transport has confirmed it.
     *
     * Called only on confirmed success — that ordering is the entire durability contract. If the
     * process dies between the server accepting an upload and this delete, the row is re-sent, so
     * `SyncTransport` is specified as keyed on `entryId`. Deleting first would be the mirror image:
     * a lost row is a change that never reaches the server and nothing ever notices.
     *
     * Returns the row count for the same reason `DiaryDao.deleteById` does: `0` is the honest
     * answer for a row another drain already removed, not an error.
     */
    @Query("DELETE FROM sync_outbox WHERE entryId = :entryId")
    suspend fun deleteByEntryId(entryId: String): Int

    /**
     * How much work is still outstanding, as a [Flow].
     *
     * Observable rather than a `suspend` read because its only purpose is a caller deciding
     * *whether* to start a drain — a sync indicator, a "N changes waiting" badge — and that decision
     * has to react when a local write enqueues, not only when the screen is rebuilt. It is the one
     * read in this queue that is not part of draining, which is why it is the one that is a `Flow`.
     */
    @Query("SELECT COUNT(*) FROM sync_outbox")
    fun observePendingCount(): Flow<Int>
}