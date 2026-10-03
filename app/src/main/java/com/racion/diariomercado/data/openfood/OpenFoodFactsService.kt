package com.racion.diariomercado.data.openfood

import com.racion.diariomercado.data.openfood.dto.OffProductResponseDto
import com.racion.diariomercado.data.openfood.dto.OffSearchResponseDto
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Retrofit description of the two Open Food Facts endpoints this app uses.
 *
 * Both are rate limited (OFF-4): 15 requests/min for product reads, 10 requests/min for
 * search, and breaching that returns HTTP 503. The layer above this interface is responsible
 * for not exceeding it.
 */
interface OpenFoodFactsService {

    /**
     * `GET /api/v2/product/{barcode}.json` — single product by barcode.
     *
     * `fields` keeps the payload small: the full product object contains dozens of keys the
     * app never reads, and the `fields` parameter is the documented way to trim it.
     *
     * Returns HTTP 200 even when the barcode is unknown, with `status = 0` and a null product
     * (OFF-2). The caller must inspect the body.
     */
    @GET("api/v2/product/{barcode}.json")
    suspend fun productByBarcode(
        @Path("barcode") barcode: String,
        @Query("fields") fields: String = OFF_FIELDS
    ): OffProductResponseDto

    /**
     * `GET /cgi/search.pl` — free-text search.
     *
     * IMPORTANT (OFF-3): Open Food Facts **v2 does not support free-text search**. The v2
     * search endpoints only do faceted/tag filtering. Free text lives exclusively in this
     * legacy CGI endpoint, which is why it is still called even though everything else moved
     * to v2.
     *
     * `search_simple = 1` disables the spellcheck-and-rerank step, which is both faster and
     * more predictable for a type-ahead that is debounced rather than fired per keystroke.
     */
    @GET("cgi/search.pl")
    suspend fun searchV1(
        @Query("search_terms") terms: String,
        @Query("search_simple") simple: Int = 1,
        @Query("action") action: String = "process",
        @Query("json") json: Int = 1,
        @Query("page") page: Int = 1,
        @Query("page_size") pageSize: Int = 20
    ): OffSearchResponseDto

    companion object {
        /**
         * The subset of product fields this app consumes, passed as `?fields=`.
         * Must stay in sync with [OffProductDto].
         */
        const val OFF_FIELDS =
            "code,product_name,brands,quantity,serving_quantity,serving_size," +
                "categories,ingredients_text,nutrition_grades,image_front_url,countries_tags,nutriments"
    }
}
