package com.racion.diariomercado.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.racion.diariomercado.domain.model.FoodProduct

/**
 * The on-device catalog cache: the last known state of every product this phone has looked up.
 *
 * ## The barcode is the identity, so the primary key *is* the barcode
 * `FoodProduct.barcode` is documented as "stable identity for a packaged product", and it is the
 * same value Open Food Facts keys on. A separate surrogate id would mean a second mapping to keep
 * consistent for no gain, and would make "have I seen this product before?" a query instead of a
 * key lookup.
 *
 * The unique [Index] below is therefore, strictly speaking, redundant with the primary key —
 * SQLite already refuses a duplicate `barcode`. It is declared anyway because DB-5 asks for a
 * unique index on the barcode, because the exported schema is the artifact a migration test reads
 * in `app/schemas/`, and because the constraint is then visible as a *named* entry in that schema
 * rather than something a reader has to infer from the primary key. Do not remove it as
 * "duplicate cleanup" without checking the exported schema first.
 *
 * ## `LOCAL-*` barcodes
 * The roadmap text describes this table as the cache for "`LOCAL-*` and scanned products". Those
 * `LOCAL-*` codes are `ui.preview.PreviewData` fixtures whose own KDoc calls them "obviously
 * fake ... the marker for not from the catalog". Nothing in the domain treats them specially, so
 * neither does this table: a barcode is a barcode, and the uniqueness guarantee is what matters.
 *
 * ## Nutrition is per 100 g here too
 * The per-100 g suffix on the columns is the same normalisation [FoodProduct.nutritionPer100g]
 * enforces in memory. Storing serving-sized totals here would make the cache unusable for a
 * second entry at a different serving size, and would bake a UI decision (what counts as "one
 * unit") into storage.
 */
@Entity(
    tableName = "food_products",
    indices = [Index(value = ["barcode"], unique = true)]
)
data class FoodProductEntity(
    /** EAN-13 / UPC-A. Primary key and unique index; see the class note. */
    @PrimaryKey
    val barcode: String,

    val name: String,
    val brand: String? = null,

    /** As printed on the pack, e.g. "500 g". Free text, so kept verbatim. */
    val quantityLabel: String? = null,

    /** `null` when the producer declares no serving size — common in a crowd-sourced database. */
    val servingGrams: Double? = null,

    val kcalPer100g: Int,
    val carbsPer100g: Double,
    val proteinPer100g: Double,
    val fatPer100g: Double,
    val sugarsPer100g: Double,
    val fiberPer100g: Double,
    val sodiumPer100g: Double,

    val imageUrl: String? = null,

    /** "a".."e" lower case, or `null` when the product is ungraded. */
    val nutriscoreGrade: String? = null,

    /**
     * Open Food Facts returns categories as one comma-separated string, which the domain model
     * splits. The list is therefore free-form data that routinely contains commas of its own, so
     * it is encoded losslessly — see the `List<String>` converter in `Converters`.
     */
    val categories: List<String> = emptyList(),

    val ingredientsText: String? = null,

    /**
     * Non-null with the same default as `FoodProduct.emoji` (a generic plate). It is content, not
     * theme: it stands in for the product photo inside a meal row, so it has to survive a
     * reinstall with the diary.
     */
    val emoji: String = "\uD83C\uDF7D"
)