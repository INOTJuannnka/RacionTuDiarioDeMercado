package com.racion.diariomercado.domain.repository

import com.racion.diariomercado.core.AppResult

/**
 * Moves this device's per-user rows from one `uid` to another.
 *
 * ## Why this exists, and why it is so small
 * Reclaiming an anonymous account into one that **already exists** cannot be a `linkWithCredential`:
 * Firebase only links a credential that belongs to no other account, and answers
 * `ERROR_EMAIL_ALREADY_IN_USE` / `ERROR_CREDENTIAL_ALREADY_IN_USE` otherwise. There is no
 * "turn account A into account B" in Firebase Auth. So the caller signs in, the `uid` changes, and
 * anything keyed by that `uid` has to be re-keyed by hand.
 *
 * The reason this is two rows instead of a migration engine is the local schema:
 * `diary_entries`, `sync_outbox` and `food_products` have **no `userId` column at all**. The diary a
 * user has been keeping since first launch is device-local and user-agnostic, so it survives a uid
 * change with no help whatsoever. Only `user_profiles` and `nutrition_goals` are keyed by uid.
 *
 * ## The trap this interface exists to make visible
 * If someone later adds a `userId` column to `diary_entries` — which DB-7's sync leg will want —
 * this class becomes **silently incomplete**: the diary would stop following the user, and nothing
 * here would fail. `SessionDataReassignerTest` would keep passing. Any change to the entities has to
 * come back and re-check whether a table was added to the list below.
 *
 * ## Ordering
 * [reassign] must be called only AFTER the provider accepted the new session, and the caller must
 * capture the OLD uid before that call — afterwards it is gone. `ProfileViewModel` does both and the
 * tests assert the ordering, because reading the uid late produces `x -> x`, which moves zero rows
 * and loses the user's settings without any visible error.
 */
interface SessionDataReassigner {

    /**
     * Re-keys every row owned by [fromUserId] to [toUserId], atomically.
     *
     * Both ids refer to the SAME human, before and after they claimed their account.
     *
     * ## What happens on a collision
     * If a row for [toUserId] already exists — reachable by signing into an account, signing out
     * (which is free on a permanent session), falling back to anonymous, and signing into the same
     * account again — the **target's** row wins and the source row is dropped. The user explicitly
     * asked to enter that account, so that account's own settings are the ones that should apply;
     * letting the anonymous row overwrite would make an account's configuration depend on whichever
     * device it was last opened on.
     *
     * This has to be handled rather than left to fail, because `userId` is the PRIMARY KEY of both
     * tables: the obvious `UPDATE ... SET userId = :new` throws a constraint violation the moment
     * the target row exists.
     *
     * Moving zero rows is a **success**, not a failure — a user who reclaimed from a second device
     * has no local rows, and reporting that as an error would put an error in front of someone who
     * did nothing wrong.
     *
     * Re-keying to the same uid is a no-op and must not be treated as an error.
     */
    suspend fun reassign(fromUserId: String, toUserId: String): AppResult<Unit>
}
