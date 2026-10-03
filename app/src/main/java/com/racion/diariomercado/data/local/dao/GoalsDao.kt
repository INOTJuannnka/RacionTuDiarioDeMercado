package com.racion.diariomercado.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.racion.diariomercado.data.local.entity.NutritionGoalsEntity
import kotlinx.coroutines.flow.Flow

/**
 * The user's targets: one row, observed.
 *
 * ## Single row per [userId], so the flow is nullable rather than defaulted
 * The query takes the key rather than reading "the only row", because two accounts can exist on
 * one phone (anonymous-first with promotion, but an explicit sign-out/sign-in replaces the uid).
 * A `LIMIT 1` over the whole table would return whichever row SQLite happened to pick.
 *
 * The emission is `Flow<NutritionGoalsEntity?>` and **`null` is a real state** — a fresh install
 * that has not opened the "Metas" screen. `GoalsRepository.observeGoals` fills `NutritionGoals()`
 * defaults over that `null` at the mapping layer; defaulting here would write a row on first read
 * and make "the user chose these numbers" and "we invented them" the same state.
 *
 * ## Last-write-wins, deliberately
 * `@Upsert` with no merge. `GoalsRepository.saveGoals` states the hazard directly: a partial merge
 * would keep the fields the caller left at their defaults, which is indistinguishable from the
 * user having explicitly chosen those defaults. The screen always emits the whole object, so a
 * replace is correct.
 */
@Dao
interface GoalsDao {

    @Query("SELECT * FROM nutrition_goals WHERE userId = :userId LIMIT 1")
    fun observeGoals(userId: String): Flow<NutritionGoalsEntity?>

    @Upsert
    suspend fun saveGoals(goals: NutritionGoalsEntity)

    /**
     * Re-keys the goals row from one uid to another. Used by the anonymous-account claim flow.
     *
     * The `WHERE` clause is load-bearing for the same reason as [ProfileDao.reassignUserId]: without
     * it every user's calorie target would be re-keyed to whoever claimed last. `userId` is the
     * PRIMARY KEY, so a pre-existing row for [newUserId] makes this throw, and the caller deletes it
     * first.
     */
    @Query("UPDATE nutrition_goals SET userId = :newUserId WHERE userId = :oldUserId")
    suspend fun reassignUserId(oldUserId: String, newUserId: String): Int

    /**
     * Drops the anonymous row ONLY when the account being claimed already owns one on this device.
     *
     * Same precedence rule as [ProfileDao.deleteSourceIfTargetExists], and for the same reason: the
     * account's own calorie target must not be overwritten by the guest session's defaults just
     * because the user signed in from a device where they had been using the app anonymously.
     * Call before [reassignUserId].
     */
    @Query(
        """
        DELETE FROM nutrition_goals
         WHERE userId = :oldUserId
           AND EXISTS (SELECT 1 FROM nutrition_goals WHERE userId = :newUserId)
        """
    )
    suspend fun deleteSourceIfTargetExists(oldUserId: String, newUserId: String): Int
}