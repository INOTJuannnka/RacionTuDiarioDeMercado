package com.racion.diariomercado.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.racion.diariomercado.domain.model.MealSlot

/**
 * One logged food event, flattened for SQL.
 *
 * ## Why [Nutrition] is not embedded here
 * `Nutrition` is deliberately **not** an `@Embedded` value object in this table. Its seven fields
 * are flattened into this entity so `SUM(kcal)`, `SUM(proteinG)`, ... is a real SQL aggregate
 * (DB-4) instead of a Kotlin fold that would have to read every entry of the day to produce one
 * number. An aggregate has to be expressible as columns for `SUM` to touch it, and an `@Embedded`
 * nested object would hide them behind a `nutrition.kcal` path the SQL layer cannot reach. The
 * domain type is rebuilt from these columns at the mapping boundary.
 *
 * ## [dayKey] is a `yyyy-MM-dd` string, and that is load-bearing
 * The zero-padded, fixed-width form is what makes a week a **lexicographic range query**: for
 * strings of equal length over the same alphabet, byte order *is* chronological order, so
 * `dayKey >= '2026-10-05' AND dayKey < '2026-10-12'` selects exactly Monday..Sunday with no date
 * arithmetic in SQL and no `BETWEEN` off-by-one at a month boundary.
 *
 * `yyyy-M-d` would sort `2026-10-05` *after* `2026-10-2`, silently dropping days from the week.
 * `MMDDYYYY` or epoch millis would sort correctly but stop matching the Firestore
 * `days/{yyyy-MM-dd}` layout, so the same calendar day would be two different keys on the two
 * sides of the sync. Zero padding is the whole contract: anything that writes this column must
 * pad, and that is why the day key is a formatted string rather than a `Long`.
 *
 * ## Why `onDelete = RESTRICT` and not CASCADE
 * `food_products` is a **cache** of Open Food Facts — a remote, append-only source. Evicting a
 * cached row is normal, expected maintenance. `diary_entries` is the opposite: it is the user's
 * history, and the premise of this app is that the diary is the source of truth on a phone with no
 * signal. The two facts point at opposite cascade policies, so the choice has to be deliberate:
 *
 * - `CASCADE` is the worst default available here. `DELETE FROM food_products WHERE barcode = ?`
 *   — a one-line cache trim, entirely reasonable on its own — would silently delete every diary
 *   entry that ever mentioned the product. No error, no crash, just a shorter diary. For an
 *   offline-first app that is data loss caused by housekeeping.
 * - `SET_NULL` is not available without cost: the link would have to become nullable, and a diary
 *   row that has lost its product identity is exactly the row the user cannot interpret.
 * - `RESTRICT` is the only policy that makes the mistake **loud**. Deleting a catalog row that
 *   history depends on throws `SQLiteConstraintException` at the single call site that can be
 *   fixed — the eviction path — instead of quietly erasing weeks of entries somewhere else.
 *
 * The practical consequence is that a barcode the user has ever logged is no longer evictable.
 * That is the correct semantic: it stopped being a redundant cache entry and became the local
 * record of something they ate. Open Food Facts never deletes products, so nothing forces the
 * eviction anyway.
 *
 * The flip side, and the reason the seven nutrition columns are stored instead of a nullable
 * pointer to the catalog row: the totals must be computable from this table alone. A `SUM` that
 * reached through a join would quietly return fewer rows — a smaller number than the user actually
 * ate — the moment a catalog row went missing, and a shortfall that looks like a diet is worse
 * than a crash.
 *
 * Reading a row back into the domain `DiaryEntry` needs the product name, which lives in the
 * catalog table; that join is a `@Transaction` + `@Relation` at the mapping layer, not here.
 */
@Entity(
    tableName = "diary_entries",
    foreignKeys = [
        ForeignKey(
            entity = FoodProductEntity::class,
            parentColumns = ["barcode"],
            childColumns = ["productBarcode"],
            onDelete = ForeignKey.RESTRICT,
            // A barcode never changes, but if one ever did, propagating it is the only outcome
            // that cannot orphan history. The policy is asymmetric on purpose: restrictive
            // deletes, permissive updates.
            onUpdate = ForeignKey.CASCADE
        )
    ],
    indices = [
        // DB-5: every "entries for this day / this week" read is a range scan on this column.
        Index(value = ["dayKey"]),
        // Not decorative: a foreign key's child column is joined on every catalog lookup, and
        // Room warns when it is unindexed.
        Index(value = ["productBarcode"])
    ]
)
data class DiaryEntryEntity(
    /**
     * Client-generated, so a retried offline write lands on the same row instead of duplicating
     * it. There is no server-assigned id to wait for — see `DiaryRepository.addEntry`.
     */
    @PrimaryKey
    val id: String,

    /** Catalog identity of the product that was eaten; the FK target. */
    @ColumnInfo(name = "productBarcode")
    val productBarcode: String,

    /** Always `>= 1`. The nutrition columns are already scaled for this many units. */
    val servings: Int,

    /** Persisted by `name`, never by `label` — see [MealSlot]. */
    val mealSlot: MealSlot,

    /**
     * Wall-clock instant of the log. Kept next to [dayKey] even though one derives from the
     * other: they genuinely disagree across a timezone change, and "when did I log this" is not
     * "which day does it count towards".
     */
    val loggedAtEpochMillis: Long,

    /** Calendar day in zero-padded `yyyy-MM-dd`. See the class note: padding is the contract. */
    val dayKey: String,

    // The seven `Nutrition` fields, precomputed for `servings` units and flattened for `SUM`.
    val kcal: Int,
    val carbsG: Double,
    val proteinG: Double,
    val fatG: Double,
    val sugarsG: Double,
    val fiberG: Double,
    val sodiumG: Double
)