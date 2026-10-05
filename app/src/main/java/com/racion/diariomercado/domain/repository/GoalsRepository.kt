package com.racion.diariomercado.domain.repository

import com.racion.diariomercado.core.AppResult
import com.racion.diariomercado.domain.model.NutritionGoals
import kotlinx.coroutines.flow.Flow

/**
 * Persists the user's targets ([NutritionGoals]).
 *
 * Contract:
 * - Single document per user, so [observeGoals] always has exactly one value to emit and can
 *   fall back to `NutritionGoals()` defaults on a first read.
 * - [saveGoals] is last-write-wins. There is no merge: passing a partially populated object
 *   would wipe the fields left at their defaults, so the screen must always emit the whole
 *   object.
 */
interface GoalsRepository {

    /** Emits the current goals, defaulting to [NutritionGoals] defaults when unset. */
    fun observeGoals(): Flow<NutritionGoals>

    suspend fun saveGoals(goals: NutritionGoals): AppResult<Unit>

    /**
     * Ensures a goals document exists for the current user.
     *
     * Creates default goals if the document doesn't exist yet. Called on first sign-in
     * for users who skip the onboarding flow.
     */
    suspend fun ensureGoalsExist(): AppResult<Unit>
}
