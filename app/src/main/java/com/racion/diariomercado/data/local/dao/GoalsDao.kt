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
}