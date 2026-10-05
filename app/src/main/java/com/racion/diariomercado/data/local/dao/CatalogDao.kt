package com.racion.diariomercado.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.racion.diariomercado.data.local.entity.FoodProductEntity
import kotlinx.coroutines.flow.Flow

/**
 * The catalog cache: read a product this phone has already seen, store one it has just fetched.
 *
 * ## Why the lookup is a [Flow] even though the repository contract is not
 * `FoodCatalogRepository.productByBarcode` is `suspend`, and the reason it is `suspend` rather
 * than observable is documented there: Open Food Facts is remote and append-only with no push
 * channel, so a `Flow` there could only ever re-emit on demand.
 *
 * That argument does not apply to *this* table. `food_products` is local, and it changes for
 * reasons the network knows nothing about — the sync writes to it. A `Flow` here lets a scan screen
 * that is already showing a cached product pick up a corrected nutrition row the moment the sync
 * lands, instead of holding a stale photo-and-macros pair until the next cold start. The mapping
 * layer still calls this inside a `suspend` function and takes the first emission, which
 * satisfies the repository contract without giving up the reactive path underneath.
 *
 * ## [upsert] and the unique barcode index
 * The unique index on `barcode` (DB-5) is what makes this table a *cache* rather than an append-only
 * log: Open Food Facts corrects nutrition data, and re-scanning a product must replace its row
 * rather than accumulate duplicates. `@Upsert` resolves on the primary key, which is the same value
 * as the barcode (see `FoodProductEntity`), so a rescan of the same product cannot create a second
 * row.
 *
 * Note what this means for the constraint: because every write path here upserts on the barcode,
 * a duplicate-barcode violation cannot be produced through this DAO. The unique index guards
 * against a bad *mapping* — two source rows normalised to the same barcode — and `CatalogDaoTest`
 * exercises it at the schema level for exactly that reason.
 */
@Dao
interface CatalogDao {

    /** Cached product, or `null` when this phone has never looked it up. */
    @Query("SELECT * FROM food_products WHERE barcode = :barcode LIMIT 1")
    fun byBarcode(barcode: String): Flow<FoodProductEntity?>

    @Upsert
    suspend fun upsert(product: FoodProductEntity)
}