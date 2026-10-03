package com.racion.diariomercado.data.sync

import com.racion.diariomercado.core.AppResult
import com.racion.diariomercado.data.local.dao.SyncOutboxDao
import com.racion.diariomercado.data.local.entity.SyncOutOp

/**
 * Uploads pending outbox rows oldest-first, and only ever deletes a row the server has confirmed.
 *
 * ## The one invariant worth more than the rest of the class
 * **Delete after success, never before.** Every ordering here follows from it. It is what makes the
 * drain idempotent, and it is the difference between a queue and a black hole: delete-then-send
 * loses a change the instant the process dies in between, silently and permanently, because the
 * only evidence was a row that no longer exists.
 *
 * ## Why it stops at the first failure instead of skipping and continuing
 * The rows are ordered oldest-first, so a failure on row *n* is a signal about the connection, not
 * about row *n* — and continuing would spend the remaining requests of an already-degraded network
 * on writes that are very likely failing for the same reason. Stopping also preserves order: a
 * later row for the *same* entry cannot overtake an earlier one, because `entryId` is the coalescing
 * key and there is only one row per entry anyway. Both rows after the failure stay queued, which is
 * what `drain` returning a `Failure` means — the caller retries later, and nothing is lost.
 *
 * ## Why the whole batch is read before the first upload
 * Reading one row per iteration would let a row enqueued *during* the drain be picked up by the same
 * call — and on a device whose sync runs on every resume, a write made between two iterations would
 * join a batch whose snapshot was taken at a different instant. Snapshot-then-work means `drain` does
 * exactly what the queue looked like when it started.
 *
 * ## Not a class that decides *when* to drain
 * There is deliberately no timer, no `repeatOnLifecycle`, no connectivity observer here. Sync
 * scheduling needs a foreground/background policy, which is a product decision, and burying one
 * inside this class would make the drain impossible to test and impossible to trigger on demand.
 * Whoever wires it calls [drain]; nothing else is implied.
 */
class DiarySyncManager(
    private val outboxDao: SyncOutboxDao,
    private val transport: SyncTransport
) {

    /**
     * Uploads up to [limit] pending rows and returns how many were confirmed.
     *
     * The return value is the count of *removed* rows, not the count of successful transport calls
     * — on the success path those are the same number, and reporting the removal count is the one
     * that stays meaningful if a future change ever acknowledges without deleting.
     *
     * On the first [AppResult.Failure] it returns that failure unchanged, so the caller sees the
     * original [com.racion.diariomercado.core.AppError] and can tell a retryable `Network` from a
     * `Server(500)` without the manager having a policy opinion about backoff. It reports the count
     * it had confirmed so far nowhere, because a `Failure` carries no data channel by design; a
     * caller that wants "it made progress before it stopped" observes
     * [SyncOutboxDao.observePendingCount] instead of parsing this.
     *
     * An empty queue is [AppResult.Success] with `0`, not a failure: there is nothing to do, which
     * is the desired state, and a caller that cannot distinguish "nothing pending" from "pending but
     * broken" will eventually report a healthy device as broken.
     *
     * `0` or negative [limit] is a programming error and throws rather than returning an empty
     * success — SQLite treats a negative `LIMIT` as no limit at all, which would turn a typo into an
     * unbounded upload of the entire queue on a phone.
     */
    suspend fun drain(limit: Int = DEFAULT_BATCH_LIMIT): AppResult<Int> {
        require(limit > 0) { "drain limit must be positive, was $limit" }

        val batch = outboxDao.oldestPending(limit)
        var uploaded = 0

        for (row in batch) {
            val payload = row.toPayload()
            val result = when (row.operation) {
                SyncOutOp.UPSERT -> transport.upsertEntry(payload)
                SyncOutOp.DELETE -> transport.deleteEntry(payload)
            }

            if (result is AppResult.Failure) {
                return AppResult.Failure(result.error)
            }

            // Confirmed, and only now: the row outlives a crash between the two by design.
            outboxDao.deleteByEntryId(row.entryId)
            uploaded++
        }

        return AppResult.Success(uploaded)
    }

    companion object {
        /**
         * Small on purpose. A drain holds up to `limit` sequential network round trips, and this
         * one runs on a phone that also has to survive a user scrolling a list; a large batch turns
         * a sync into a foreground-length network session. The queue is drained again on the next
         * trigger regardless, so a small batch costs one extra round trip per drain, not a lost row.
         */
        const val DEFAULT_BATCH_LIMIT = 25
    }
}