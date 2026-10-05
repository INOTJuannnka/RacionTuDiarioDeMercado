package com.racion.diariomercado.data.openfood

import com.racion.diariomercado.core.AppError
import com.racion.diariomercado.core.AppResult
import com.racion.diariomercado.data.openfood.dto.OffProductDto
import com.racion.diariomercado.domain.model.FoodProduct
import com.racion.diariomercado.domain.model.Nutrition
import com.racion.diariomercado.domain.repository.FoodCatalogRepository
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import retrofit2.HttpException

/**
 * [FoodCatalogRepository] backed by the Open Food Facts API.
 *
 * `internal` on purpose: nothing outside `data/` may depend on Open Food Facts. Screens talk to
 * the [FoodCatalogRepository] interface, so the backend can be swapped (or replaced with a
 * curated local catalog) without a single UI change.
 *
 * ## Constraints this implementation MUST honour
 *
 * **1. The `User-Agent` header is mandatory (OFF-1).**
 * Open Food Facts blocks generic user agents. Requests with no `User-Agent`, or with a default
 * library one, are treated as bot traffic and blocked. The header must have the shape
 * `AppName/Version (contact)`, e.g. `RacionTuDiarioDeMercado/1.0 (contact@example.com)`.
 * The value is wired in `AppContainer` from `BuildConfig.OPEN_FOOD_FACTS_USER_AGENT`.
 * Getting this wrong is not a soft failure: you get HTTP 403/503 and empty results.
 *
 * **2. Rate limits (OFF-4).**
 * 15 requests/min for product reads, 10 requests/min for search. Breaching the limit returns
 * HTTP 503, which must be mapped to [com.racion.diariomercado.core.AppError.RateLimited] and
 * not surfaced as a generic server error. Consequence: search must NOT be search-as-you-type —
 * each keystroke would burn a request against a budget shared by the whole user's session. The
 * UI must debounce and require an explicit submit.
 *
 * **3. A missing product is HTTP 200, not 404 (OFF-2).**
 * An unknown barcode returns `200 OK` with `status = 0` and `product = null`. Only the body
 * tells you the product is missing. Code that checks `response.isSuccessful` and then
 * dereferences `response.body()!!.product!!` will crash on the most common real-world case.
 */
internal class OpenFoodFactsCatalogRepository(
    private val service: OpenFoodFactsService
) : FoodCatalogRepository {

    /**
     * TODO(OFF-2): call [OpenFoodFactsService.productByBarcode], then check the body:
     * `status != 1 || product == null` -> `AppResult.Failure(AppError.NotFound)`.
     * Map `UnknownHostException`/`SocketTimeoutException` -> `AppError.Network`, HTTP 429/503 ->
     * `AppError.RateLimited`, any other non-2xx -> `AppError.Server(code, message)`, and
     * anything else -> `AppError.Unknown(it)`.
     */
    override suspend fun productByBarcode(barcode: String): AppResult<FoodProduct> {
        return try {
            val response = service.productByBarcode(barcode)
            val status = response.status
            val product = response.product
            if (status != 1 || product == null) {
                AppResult.Failure(AppError.NotFound)
            } else {
                val mapped = product.toFoodProduct()
                if (mapped == null) {
                    AppResult.Failure(AppError.NotFound)
                } else {
                    AppResult.Success(mapped)
                }
            }
        } catch (e: HttpException) {
            when (val code = e.code()) {
                403 -> AppResult.Failure(AppError.Server(code, "User-Agent rechazado. Configurá un email real en local.properties (openfoodfacts.contact.email) o build.gradle.kts."))
                429, 503 -> AppResult.Failure(AppError.RateLimited)
                else -> AppResult.Failure(AppError.Server(code, e.message()))
            }
        } catch (e: UnknownHostException) {
            AppResult.Failure(AppError.Network)
        } catch (e: SocketTimeoutException) {
            AppResult.Failure(AppError.Network)
        } catch (e: Throwable) {
            AppResult.Failure(AppError.Unknown(e))
        }
    }

    /**
     * Free-text search via the legacy v1 CGI endpoint (v2 has no free text — OFF-3).
     *
     * Maps each [OffProductDto] with [toFoodProduct], drops entries without a usable name,
     * and caps the result at [pageSize]. Errors are mapped to [AppError] per the contract.
     */
    override suspend fun search(query: String, page: Int, pageSize: Int): AppResult<List<FoodProduct>> =
        try {
            val trimmed = query.trim()
            if (trimmed.isEmpty()) {
                AppResult.Success(emptyList())
            } else {
                val response = service.searchV1(terms = trimmed, page = page, pageSize = pageSize)
                val products = response.products ?: emptyList()
                val mapped = products
                    .map { it.toFoodProduct() }
                    .filterNotNull()
                AppResult.Success(mapped)
            }
        } catch (e: HttpException) {
            when (val code = e.code()) {
                403 -> AppResult.Failure(AppError.Server(code, "User-Agent rechazado. Configurá un email real en local.properties (openfoodfacts.contact.email) o build.gradle.kts."))
                429, 503 -> AppResult.Failure(AppError.RateLimited)
                else -> AppResult.Failure(AppError.Server(code, e.message()))
            }
        } catch (e: UnknownHostException) {
            AppResult.Failure(AppError.Network)
        } catch (e: SocketTimeoutException) {
            AppResult.Failure(AppError.Network)
        } catch (e: Throwable) {
            AppResult.Failure(AppError.Unknown(e))
        }

    /**
     * DTO -> domain mapping.
     *
     * Returns `null` when there is no usable product name: the catalog is crowd-sourced and
     * unnamed rows exist, and a nameless product cannot be rendered on a diary entry.
     * [FoodCatalogRepository.productByBarcode] reports that as [AppError.NotFound], the same as
     * a barcode OFF has never seen — deliberately, because from the user's point of view both
     * mean "there is nothing to show here".
     */
    private fun OffProductDto.toFoodProduct(): FoodProduct? {
        val usableName = productName?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return FoodProduct(
            barcode = code.orEmpty(),
            name = usableName,
            // OFF routinely joins several brands with a comma ("Canuelas,Marca Dos"). One
            // product has one brand here, so the first non-blank entry wins.
            brand = brands.splitOnCommas().firstOrNull(),
            quantityLabel = quantity?.trim()?.takeIf { it.isNotEmpty() },
            servingGrams = servingQuantity,
            nutritionPer100g = toNutrition(),
            imageUrl = imageFrontUrl?.trim()?.takeIf { it.isNotEmpty() },
            // OFF stores the Nutriscore grade inconsistently cased across rows.
            nutriscoreGrade = nutritionGrades?.trim()?.lowercase()?.takeIf { it.isNotEmpty() },
            categories = categories.splitOnCommas(),
            ingredientsText = ingredientsText?.trim()?.takeIf { it.isNotEmpty() }
        )
    }

    /**
     * Maps the `nutriments` sub-object into [Nutrition].
     *
     * A product with no `nutriments` at all maps to `Nutrition()` rather than being dropped, so
     * the user can still log it. **That is the OFF-5a decision**: register with zeroes instead
     * of blocking. The consequence is that a zero here is ambiguous — it means "OFF has no data"
     * just as much as it means "this product has no calories" — which is why surfacing that
     * distinction in the UI is a separate, still-open task rather than something this mapping
     * can solve on its own.
     */
    private fun OffProductDto.toNutrition(): Nutrition {
        val n = nutriments ?: return Nutrition()
        return Nutrition(
            // Rounded, never truncated, and with the same HALF_UP convention
            // `Nutrition.scaled` uses, so a per-serving total never drifts from the per-100 g
            // figure it was derived from.
            kcal = Math.round(n.energyKcal100g ?: 0.0).toInt(),
            carbsG = n.carbohydrates100g ?: 0.0,
            proteinG = n.proteins100g ?: 0.0,
            fatG = n.fat100g ?: 0.0,
            sugarsG = n.sugars100g ?: 0.0,
            fiberG = n.fiber100g ?: 0.0,
            sodiumG = n.sodium100g ?: 0.0
        )
    }

    /**
     * Splits an OFF comma-separated field into trimmed, non-blank parts.
     *
     * OFF is sloppy with these: trailing commas, doubled separators and stray whitespace all
     * occur, and an empty [categories] string must not become a list holding one empty string.
     */
    private fun String?.splitOnCommas(): List<String> =
        this?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
}
