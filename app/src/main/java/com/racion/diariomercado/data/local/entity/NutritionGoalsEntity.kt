package com.racion.diariomercado.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.racion.diariomercado.domain.model.DayOfWeek
import com.racion.diariomercado.domain.model.NutritionGoals

/**
 * The user's targets, one row per user.
 *
 * ## Why the key is [userId] and not a constant
 * `GoalsRepository` says "single document per user", and Firestore stores it at
 * `users/{uid}/goals`. A hardcoded `id = 0` singleton would make that key a lie the moment a
 * second account signs in on the same phone — which this app does support, because anonymous
 * session promotion *preserves* the uid but an explicit sign-out/sign-in does not. Keying by
 * [userId] keeps the local layout identical to the remote one, so the sync has no special case.
 *
 * ## Defaults are duplicated from [NutritionGoals], not referenced from it
 * The literals below are copies. Reading them off `NutritionGoals()` instead would make one
 * constructor call per defaulted row and hide the real values in another type's parameter list;
 * what matters is that a row created for a brand-new user is indistinguishable from
 * `NutritionGoals()` in the UI. `EntityDefaultsTest` fails if the two ever drift.
 *
 * ## [activeDays] and the DayOfWeek name clash
 * [DayOfWeek] here is the domain type from `NutritionGoals.kt`, which deliberately shadows
 * `java.time.DayOfWeek`. The persistence layer needs the JDK one for *ordering* the encoded set,
 * and gets it through an import alias inside `Converters`. The clash is confined to one file on
 * purpose: re-declaring the name here would produce two types that both look like "the weekday"
 * and neither of which is the other.
 */
@Entity(tableName = "nutrition_goals")
data class NutritionGoalsEntity(
    @PrimaryKey
    val userId: String,

    /** Mirrors `NutritionGoals.targetWeightKg` (kg). */
    val targetWeightKg: Float = 63f,

    /** Mirrors `NutritionGoals.currentWeightKg` (kg). */
    val currentWeightKg: Float = 68f,

    /** Mirrors `NutritionGoals.kcalPerDay`. An `Int` because calories are a count, not a measure. */
    val kcalPerDay: Int = 1900,

    /** ISO weekdays the user trains on; drives the weekly active-day row. */
    val activeDays: Set<DayOfWeek> = DayOfWeek.entries.toSet(),

    /**
     * `MacroSplit` is flattened into three columns for the same reason the goals row is not a
     * single JSON blob: three readable integers beat one opaque string in a schema dump, and the
     * type is small and closed. Not validated to sum to 100 — see `MacroSplit`, which does not
     * promise that either.
     */
    val carbsPct: Int = 45,
    val proteinPct: Int = 25,
    val fatPct: Int = 30
)