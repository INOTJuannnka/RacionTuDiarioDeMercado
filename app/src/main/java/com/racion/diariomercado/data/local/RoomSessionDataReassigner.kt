package com.racion.diariomercado.data.local

import androidx.room.withTransaction
import com.racion.diariomercado.core.AppError
import com.racion.diariomercado.core.AppResult
import com.racion.diariomercado.domain.repository.SessionDataReassigner

/**
 * [SessionDataReassigner] over the local Room database.
 *
 * ## Why the transaction lives on the DATABASE and not on a DAO
 * It spans `user_profiles` and `nutrition_goals`, and a Room `@Transaction` can only decorate a
 * method of a **single** DAO. Putting one method on each DAO and calling them in sequence would
 * leave a window where the profile has moved and the goals have not — and the user who triggers it
 * is already looking at a new account, so nothing would tell them a half-finished state existed.
 * `withTransaction { }` on the database is the only construct that covers both.
 *
 * ## Why delete-then-update rather than a single upsert
 * `userId` is the PRIMARY KEY of both tables, so `UPDATE ... SET userId = :new` throws
 * `SQLiteConstraintException` the instant a row for the target already exists. That case is
 * reachable, not theoretical: sign into an account, sign out (free, on a permanent session), fall
 * back to anonymous, sign into the same account again. The device still holds the account's row.
 *
 * Deleting the target first makes the update safe and states the precedence explicitly: the account
 * the user asked to enter owns its own settings. The alternative — letting the anonymous rows win —
 * would make an account's calorie target depend on whichever device happened to open it last.
 *
 * ## Why an exception becomes a value
 * Same rule as `FirebaseAuthRepository`: a caller has to be able to render every outcome, and a
 * thrown SQLite exception cannot be rendered. It becomes [AppError.Unknown] with the cause attached
 * so it is still diagnosable from a log.
 *
 * No constructor work beyond the database reference, and no Firebase: this class must keep working
 * when the provider is unreachable, because it runs *after* the provider already said yes.
 */
internal class RoomSessionDataReassigner(
    private val db: RacionDatabase
) : SessionDataReassigner {

    override suspend fun reassign(fromUserId: String, toUserId: String): AppResult<Unit> {
        // Re-keying to itself would delete the row and then have nothing left to update, which is
        // the one way this method could actually lose data. It is a legitimate no-op, not an error.
        if (fromUserId == toUserId) return AppResult.Success(Unit)

        return runCatching {
            db.withTransaction {
                // Target wins on a collision. Two statements per table, in this order:
                //   1. drop the source row IF the target already owns one — the target is the
                //      winner, and the anonymous row is device-local scratch.
                //   2. otherwise move the source row onto the free target.
                // Written as two statements rather than "delete the target, then move the source in",
                // which reads the same but inverts the precedence and lets the guest session's
                // defaults overwrite an account's real settings.
                //
                // Both tables run the same pair. `SessionDataReassignerTest` pins the outcome for
                // each, because the failure mode here is silent — a wrong winner looks like a
                // successful migration until the user notices their calorie target reset.
                db.profileDao().deleteSourceIfTargetExists(fromUserId, toUserId)
                db.profileDao().reassignUserId(fromUserId, toUserId)

                db.goalsDao().deleteSourceIfTargetExists(fromUserId, toUserId)
                db.goalsDao().reassignUserId(fromUserId, toUserId)
            }
            // The transaction returns Unit; collapsed explicitly so both fold branches agree on R.
            Unit
        }.fold(
            onSuccess = { AppResult.Success(Unit) },
            onFailure = { error: Throwable -> AppResult.Failure(AppError.Unknown(cause = error)) }
        )
    }
}
