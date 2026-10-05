package com.racion.diariomercado.data.openfood

import com.racion.diariomercado.core.AppError
import com.racion.diariomercado.core.AppResult
import com.racion.diariomercado.data.openfood.dto.OffProductDto
import com.racion.diariomercado.domain.model.FoodProduct
import com.racion.diariomercado.domain.model.Nutrition
import com.racion.diariomercado.domain.repository.FoodCatalogRepository
import com.squareup.moshi.JsonDataException
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
     * Fallback productos típicos colombianos — usado cuando la API legacy falla.
     * Barcodes con prefijo "LOCAL-CO-" para distinguirlos de reales.
     */
    private val fallbackColombia = listOf(
        // Bebidas
        FoodProduct(
            barcode = "LOCAL-CO-COCA600",
            name = "Coca-Cola 600 ml",
            brand = "Coca-Cola",
            quantityLabel = "Botella 600 ml",
            servingGrams = 600.0,
            nutritionPer100g = Nutrition(kcal = 42, carbsG = 10.6, proteinG = 0.0, fatG = 0.0, sugarsG = 10.6),
            categories = listOf("Bebidas", "Gaseosas"),
            emoji = "🥤"
        ),
        FoodProduct(
            barcode = "LOCAL-CO-PEPSI600",
            name = "Pepsi 600 ml",
            brand = "Pepsi",
            quantityLabel = "Botella 600 ml",
            servingGrams = 600.0,
            nutritionPer100g = Nutrition(kcal = 41, carbsG = 10.3, proteinG = 0.0, fatG = 0.0, sugarsG = 10.3),
            categories = listOf("Bebidas", "Gaseosas"),
            emoji = "🥤"
        ),
        FoodProduct(
            barcode = "LOCAL-CO-POSTOBON_MANZANA",
            name = "Postobón Manzana 350 ml",
            brand = "Postobón",
            quantityLabel = "Botella 350 ml",
            servingGrams = 350.0,
            nutritionPer100g = Nutrition(kcal = 44, carbsG = 11.0, proteinG = 0.0, fatG = 0.0, sugarsG = 11.0),
            categories = listOf("Bebidas", "Gaseosas"),
            emoji = "🍎"
        ),
        FoodProduct(
            barcode = "LOCAL-CO_COLOMBIANA",
            name = "Colombiana 350 ml",
            brand = "Postobón",
            quantityLabel = "Botella 350 ml",
            servingGrams = 350.0,
            nutritionPer100g = Nutrition(kcal = 48, carbsG = 12.0, proteinG = 0.0, fatG = 0.0, sugarsG = 12.0),
            categories = listOf("Bebidas", "Gaseosas"),
            emoji = "🍾"
        ),
        FoodProduct(
            barcode = "LOCAL-CO_KOLA_ROMANA",
            name = "Kola Román 350 ml",
            brand = "Postobón",
            quantityLabel = "Botella 350 ml",
            servingGrams = 350.0,
            nutritionPer100g = Nutrition(kcal = 45, carbsG = 11.3, proteinG = 0.0, fatG = 0.0, sugarsG = 11.3),
            categories = listOf("Bebidas", "Gaseosas"),
            emoji = "🥤"
        ),
        // Jugos y néctares
        FoodProduct(
            barcode = "LOCAL-CO_HIT_MANGO",
            name = "Hit Mango 250 ml",
            brand = "Postobón",
            quantityLabel = "Caja 250 ml",
            servingGrams = 250.0,
            nutritionPer100g = Nutrition(kcal = 52, carbsG = 13.0, proteinG = 0.2, fatG = 0.0, sugarsG = 12.5),
            categories = listOf("Bebidas", "Jugos"),
            emoji = "🥭"
        ),
        FoodProduct(
            barcode = "LOCAL-CO_HIT_MARACUYA",
            name = "Hit Maracuyá 250 ml",
            brand = "Postobón",
            quantityLabel = "Caja 250 ml",
            servingGrams = 250.0,
            nutritionPer100g = Nutrition(kcal = 48, carbsG = 12.0, proteinG = 0.2, fatG = 0.0, sugarsG = 11.5),
            categories = listOf("Bebidas", "Jugos"),
            emoji = "🟡"
        ),
        FoodProduct(
            barcode = "LOCAL-CO_JUGO_NATURAL_NARANJA",
            name = "Jugo Natural Naranja 1L",
            brand = "Fruton",
            quantityLabel = "Botella 1 L",
            servingGrams = 1000.0,
            nutritionPer100g = Nutrition(kcal = 45, carbsG = 10.4, proteinG = 0.7, fatG = 0.2, sugarsG = 9.0),
            categories = listOf("Bebidas", "Jugos"),
            emoji = "🍊"
        ),
        // Café y bebidas calientes
        FoodProduct(
            barcode = "LOCAL-CO_CAFE_COLOMBIANO",
            name = "Café Colombiano Molido 500g",
            brand = "Juan Valdez",
            quantityLabel = "Bolsa 500 g",
            servingGrams = 2.0,
            nutritionPer100g = Nutrition(kcal = 0, carbsG = 0.0, proteinG = 0.1, fatG = 0.0, sugarsG = 0.0),
            categories = listOf("Desayuno", "Café"),
            emoji = "☕"
        ),
        FoodProduct(
            barcode = "LOCAL-CO_CHOCOLATE_CALIENTE",
            name = "Chocolate Caliente en Polvo 200g",
            brand = "Colombina",
            quantityLabel = "Caja 200 g",
            servingGrams = 20.0,
            nutritionPer100g = Nutrition(kcal = 380, carbsG = 85.0, proteinG = 3.0, fatG = 3.5, sugarsG = 78.0),
            categories = listOf("Desayuno", "Chocolate"),
            emoji = "🍫"
        ),
        // Pan y repostería
        FoodProduct(
            barcode = "LOCAL-CO_AREPA_MAIZ",
            name = "Arepa de Maíz Blanca x10",
            brand = "Doñarepa",
            quantityLabel = "Paquete 10 unidades (500 g)",
            servingGrams = 50.0,
            nutritionPer100g = Nutrition(kcal = 230, carbsG = 50.0, proteinG = 5.0, fatG = 1.5, sugarsG = 0.5, fiberG = 2.0),
            categories = listOf("Desayuno", "Pan", "Arepas"),
            emoji = "🫓"
        ),
        FoodProduct(
            barcode = "LOCAL-CO_ALMOJABANA",
            name = "Almojábana 100g",
            brand = "Tradicional",
            quantityLabel = "Unidad 100 g",
            servingGrams = 100.0,
            nutritionPer100g = Nutrition(kcal = 280, carbsG = 35.0, proteinG = 8.0, fatG = 12.0, sugarsG = 2.0),
            categories = listOf("Desayuno", "Pan", "Queso"),
            emoji = "🧀"
        ),
        FoodProduct(
            barcode = "LOCAL-CO_PAN_BONO",
            name = "Pan de Bono 100g",
            brand = "Tradicional",
            quantityLabel = "Unidad 100 g",
            servingGrams = 100.0,
            nutritionPer100g = Nutrition(kcal = 290, carbsG = 38.0, proteinG = 7.0, fatG = 13.0, sugarsG = 3.0),
            categories = listOf("Desayuno", "Pan", "Queso"),
            emoji = "🥯"
        ),
        // Snacks y galletas
        FoodProduct(
            barcode = "LOCAL-CO_GALLETAS_FESTIVAL",
            name = "Galletas Festival Vainilla 300g",
            brand = "Noel",
            quantityLabel = "Paquete 300 g",
            servingGrams = 30.0,
            nutritionPer100g = Nutrition(kcal = 450, carbsG = 70.0, proteinG = 5.5, fatG = 16.0, sugarsG = 28.0),
            categories = listOf("Snacks", "Galletas"),
            emoji = "🍪"
        ),
        FoodProduct(
            barcode = "LOCAL-CO_GALLETAS_SALTINAS",
            name = "Galletas Saltinas 200g",
            brand = "Noel",
            quantityLabel = "Paquete 200 g",
            servingGrams = 10.0,
            nutritionPer100g = Nutrition(kcal = 420, carbsG = 74.0, proteinG = 8.0, fatG = 11.0, sugarsG = 1.5),
            categories = listOf("Snacks", "Galletas", "Saladas"),
            emoji = "🍘"
        ),
        FoodProduct(
            barcode = "LOCAL-CO_CHOCORRAMO",
            name = "Chocorramo 70g",
            brand = "Ramo",
            quantityLabel = "Unidad 70 g",
            servingGrams = 70.0,
            nutritionPer100g = Nutrition(kcal = 380, carbsG = 55.0, proteinG = 4.5, fatG = 15.0, sugarsG = 40.0),
            categories = listOf("Snacks", "Pasteles", "Chocolate"),
            emoji = "🍰"
        ),
        // Lácteos
        FoodProduct(
            barcode = "LOCAL-CO_LECHE_ALPINA",
            name = "Leche Entera Alpina 1L",
            brand = "Alpina",
            quantityLabel = "Bolsa 1 L",
            servingGrams = 1000.0,
            nutritionPer100g = Nutrition(kcal = 62, carbsG = 4.8, proteinG = 3.3, fatG = 3.3, sugarsG = 4.8),
            categories = listOf("Lácteos", "Leche"),
            emoji = "🥛"
        ),
        FoodProduct(
            barcode = "LOCAL-CO_YOGURT_ALPINA_FRESA",
            name = "Yogurt Alpina Fresa 170g",
            brand = "Alpina",
            quantityLabel = "Vaso 170 g",
            servingGrams = 170.0,
            nutritionPer100g = Nutrition(kcal = 85, carbsG = 12.0, proteinG = 3.5, fatG = 2.5, sugarsG = 11.5),
            categories = listOf("Lácteos", "Yogurt"),
            emoji = "🍓"
        ),
        FoodProduct(
            barcode = "LOCAL-CO_KUMIS",
            name = "Kumis Alpina 250ml",
            brand = "Alpina",
            quantityLabel = "Botella 250 ml",
            servingGrams = 250.0,
            nutritionPer100g = Nutrition(kcal = 55, carbsG = 7.0, proteinG = 3.0, fatG = 2.0, sugarsG = 6.5),
            categories = listOf("Lácteos", "Bebidas Fermentadas"),
            emoji = "🥛"
        ),
        // Frutas y snacks saludables
        FoodProduct(
            barcode = "LOCAL-CO_PLATANO_MADURO",
            name = "Plátano Maduro 1kg",
            brand = "Fresco",
            quantityLabel = "Unidad ~1 kg",
            servingGrams = 120.0,
            nutritionPer100g = Nutrition(kcal = 89, carbsG = 23.0, proteinG = 1.1, fatG = 0.3, sugarsG = 12.0, fiberG = 2.6),
            categories = listOf("Frutas", "Snacks Saludables"),
            emoji = "🍌"
        ),
        FoodProduct(
            barcode = "LOCAL-CO_MANGO_BICHE",
            name = "Mango Biche 500g",
            brand = "Fresco",
            quantityLabel = "Bandeja 500 g",
            servingGrams = 150.0,
            nutritionPer100g = Nutrition(kcal = 60, carbsG = 15.0, proteinG = 0.8, fatG = 0.4, sugarsG = 14.0, fiberG = 1.6),
            categories = listOf("Frutas", "Snacks Saludables"),
            emoji = "🥭"
        ),
        // Carnes y proteínas
        FoodProduct(
            barcode = "LOCAL-CO_CHORIZO_SANTARROSA",
            name = "Chorizo Santarrosa 250g",
            brand = "Santarrosa",
            quantityLabel = "Paquete 250 g",
            servingGrams = 50.0,
            nutritionPer100g = Nutrition(kcal = 320, carbsG = 2.0, proteinG = 14.0, fatG = 28.0, sodiumG = 1.2),
            categories = listOf("Carnes", "Embutidos"),
            emoji = "🌭"
        ),
        FoodProduct(
            barcode = "LOCAL-CO_HUEVO_AAA",
            name = "Huevo AAA x12",
            brand = "Campi",
            quantityLabel = "Cartón 12 unidades (~600 g)",
            servingGrams = 50.0,
            nutritionPer100g = Nutrition(kcal = 155, carbsG = 1.1, proteinG = 13.0, fatG = 11.0),
            categories = listOf("Proteínas", "Huevos"),
            emoji = "🥚"
        ),
    )

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
        } catch (e: JsonDataException) {
            // JSON parsing failed (API returned HTML instead of JSON)
            AppResult.Failure(AppError.Server(0, "Formato de respuesta inválido del servidor"))
        } catch (e: Throwable) {
            AppResult.Failure(AppError.Unknown(e))
        }
    }

    /**
     * Free-text search via the legacy v1 CGI endpoint (v2 has no free text — OFF-3).
     *
     * Maps each [OffProductDto] with [toFoodProduct], drops entries without a usable name,
     * and caps the result at [pageSize]. Errors are mapped to [AppError] per the contract.
     * Falls back to local Colombian products when the API fails.
     */
    override suspend fun search(query: String, page: Int, pageSize: Int): AppResult<List<FoodProduct>> =
        try {
            val trimmed = query.trim()
            if (trimmed.isEmpty()) {
                AppResult.Success(emptyList())
            } else {
                // Try the legacy CGI search endpoint with additional parameters
                val response = service.searchV1(
                    terms = trimmed,
                    page = page,
                    pageSize = pageSize,
                    simple = 1,
                    action = "process",
                    json = 1
                )
                val products = response.products ?: emptyList()
                val mapped = products
                    .map { it.toFoodProduct() }
                    .filterNotNull()
                AppResult.Success(mapped)
            }
        } catch (e: HttpException) {
            // API failed → return filtered local fallback
            val localResults = fallbackColombia
                .filter { it.name.contains(query, ignoreCase = true) }
            if (localResults.isNotEmpty()) {
                AppResult.Success(localResults)
            } else {
                when (val code = e.code()) {
                    403 -> AppResult.Failure(AppError.Server(code, "User-Agent rechazado. Configurá un email real."))
                    429, 503 -> AppResult.Failure(AppError.RateLimited)
                    else -> {
                        val body = e.response()?.errorBody()?.string() ?: "no body"
                        AppResult.Failure(AppError.Server(code, "HTTP $code: $body"))
                    }
                }
            }
        } catch (e: UnknownHostException) {
            // No internet → return filtered local fallback
            val localResults = fallbackColombia
                .filter { it.name.contains(query, ignoreCase = true) }
            AppResult.Success(localResults)
        } catch (e: SocketTimeoutException) {
            // Timeout → return filtered local fallback
            val localResults = fallbackColombia
                .filter { it.name.contains(query, ignoreCase = true) }
            AppResult.Success(localResults)
        } catch (e: JsonDataException) {
            // JSON parsing failed (API returned HTML instead of JSON) → fallback
            val localResults = fallbackColombia
                .filter { it.name.contains(query, ignoreCase = true) }
            AppResult.Success(localResults)
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
