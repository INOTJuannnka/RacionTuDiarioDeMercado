package com.racion.diariomercado.data.openfood.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Moshi DTOs mirroring the **real** Open Food Facts v2 responses.
 *
 * Field names below are the wire names, verbatim, and were verified against the live API
 * (see `docs/INTEGRATION.md`). They are not a guess and they are not normalised: this is the
 * only layer allowed to know that Open Food Facts writes `energy-kcal_100g` with a hyphen and
 * `carbohydrates_100g` in the plural. Mapping to `FoodProduct` / `Nutrition` is the job of
 * `OpenFoodFactsCatalogRepository`.
 *
 * Every field is nullable. Open Food Facts is crowd-sourced, so a product may legitimately
 * have no brand, no serving size, or no nutriments at all. Forcing non-null defaults here
 * would invent data.
 *
 * `@JsonClass(generateAdapter = true)` now produces codegen adapters via KSP
 * (`libs.squareup.moshi.kotlin.codegen`), so Moshi uses generated classes at runtime.
 * The `KotlinJsonAdapterFactory` registered in `AppContainer` remains as a fallback
 * for any non-annotated type.
 */

/**
 * Envelope of `GET /api/v2/product/{barcode}.json`.
 *
 * CRITICAL (OFF-2): a barcode that is not in the database still returns **HTTP 200** with
 * `status = 0` and `product = null`. Any code that only checks the HTTP status will treat a
 * miss as a hit and read a null product.
 */
@JsonClass(generateAdapter = true)
data class OffProductResponseDto(
    @Json(name = "status") val status: Int?,
    @Json(name = "status_verbose") val statusVerbose: String?,
    @Json(name = "code") val code: String?,
    @Json(name = "product") val product: OffProductDto?
)

/**
 * Envelope of the legacy v1 `cgi/search.pl` free-text endpoint.
 *
 * v2 has no free-text search at all (OFF-3), which is why this type exists alongside
 * [OffProductResponseDto] rather than the other way around.
 */
@JsonClass(generateAdapter = true)
data class OffSearchResponseDto(
    @Json(name = "count") val count: Int?,
    @Json(name = "page") val page: Int?,
    @Json(name = "page_size") val pageSize: Int?,
    @Json(name = "products") val products: List<OffProductDto>?
)

/** A single product as returned by either endpoint. */
@JsonClass(generateAdapter = true)
data class OffProductDto(
    @Json(name = "code") val code: String?,
    @Json(name = "product_name") val productName: String?,
    @Json(name = "brands") val brands: String?,
    @Json(name = "quantity") val quantity: String?,
    @Json(name = "serving_quantity") val servingQuantity: Double?,
    @Json(name = "serving_size") val servingSize: String?,
    @Json(name = "categories") val categories: String?,
    @Json(name = "ingredients_text") val ingredientsText: String?,
    @Json(name = "nutrition_grades") val nutritionGrades: String?,
    @Json(name = "image_front_url") val imageFrontUrl: String?,
    @Json(name = "countries_tags") val countriesTags: String?,
    @Json(name = "nutriments") val nutriments: OffNutrimentsDto?
)

/**
 * The `nutriments` sub-object. All keys are per 100 g.
 *
 * Note the asymmetry that trips everyone up once: energy uses a **hyphen** while the macros
 * use **underscores**, and the macro names are plural (`carbohydrates_100g`,
 * `proteins_100g`) while fat is singular (`fat_100g`).
 */
@JsonClass(generateAdapter = true)
data class OffNutrimentsDto(
    @Json(name = "energy-kcal_100g") val energyKcal100g: Double?,
    @Json(name = "carbohydrates_100g") val carbohydrates100g: Double?,
    @Json(name = "proteins_100g") val proteins100g: Double?,
    @Json(name = "fat_100g") val fat100g: Double?,
    @Json(name = "sugars_100g") val sugars100g: Double?,
    @Json(name = "fiber_100g") val fiber100g: Double?,
    @Json(name = "sodium_100g") val sodium100g: Double?
)
