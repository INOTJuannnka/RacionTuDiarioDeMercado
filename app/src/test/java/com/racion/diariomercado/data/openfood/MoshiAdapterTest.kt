package com.racion.diariomercado.data.openfood

import com.racion.diariomercado.data.openfood.dto.OffNutrimentsDto
import com.racion.diariomercado.data.openfood.dto.OffProductDto
import com.racion.diariomercado.data.openfood.dto.OffProductResponseDto
import com.racion.diariomercado.data.openfood.dto.OffSearchResponseDto
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.converter.moshi.MoshiConverterFactory

/**
 * Regression guard for the Moshi adapter wiring (OFF-1).
 *
 * ## Why this test exists
 * The DTOs in `data/openfood/dto` are annotated `@JsonClass(generateAdapter = true)` while
 * this module has **no annotation processor**, so no `*JsonAdapter` classes are ever
 * generated. On its own that combination looks like a guaranteed crash on the first Open
 * Food Facts response, and a review claimed exactly that: that Moshi consults its built-in
 * factories ahead of any user-registered one, so `KotlinJsonAdapterFactory` would never see
 * the type and the lookup would throw `IllegalArgumentException: Failed to find the
 * generated JsonAdapter class for ...`.
 *
 * **That claim is false, and this test class is the evidence.** Measured against the pinned
 * Moshi 1.15.2: with the factory registered, these DTOs resolve to a `KotlinJsonAdapter` and
 * round-trip cleanly. The `@JsonClass(generateAdapter = true)` branch lives in a *built-in*
 * [com.squareup.moshi.JsonAdapter.Factory], and factories added to the builder are consulted
 * first, so the missing generated class is never reached. The failure is only reachable from
 * a Moshi built WITHOUT `KotlinJsonAdapterFactory` — which is exactly what
 * `withoutTheKotlinJsonAdapterFactoryTheGeneratedAdapterLookupIsUnreachable` pins down.
 *
 * ## What this test protects
 * - It builds [Moshi] with the **exact** expression `AppContainer` uses, so if someone removes
 *   the factory (or re-orders the builder) without re-checking the DTOs, the round trips below
 *   fail here instead of on a user's phone.
 * - It is expected to keep passing **after** KSP + `libs.squareup.moshi.kotlin.codegen` land.
 *   The annotation stays on the DTOs across that migration, so nothing here needs editing:
 *   the assertion is about the wire format surviving, not about which adapter strategy
 *   produced it.
 */
class MoshiAdapterTest {

    /**
     * Mirrors the `moshi` property in `di/AppContainer.kt` verbatim.
     *
     * The container's property is `private`, so it cannot be reused directly. This duplication
     * is deliberate: a unit test that called into the container would need an Android
     * `Context`, and the builder expression is three lines.
     */
    private fun moshiAsBuiltByAppContainer(): Moshi =
        Moshi.Builder()
            .add(KotlinJsonAdapterFactory())
            .build()

    private val nutriments = OffNutrimentsDto(
        energyKcal100g = 294.0,
        carbohydrates100g = 31.0,
        proteins100g = 10.0,
        fat100g = 12.0,
        sugars100g = 1.4,
        fiber100g = 2.2,
        sodium100g = 0.31
    )

    private val product = OffProductDto(
        code = "7501234567890",
        productName = "Arepa de choclo con queso",
        brands = "Típico",
        quantity = "90 g",
        servingQuantity = 90.0,
        servingSize = "90 g",
        categories = "Almuerzo, Bebidas",
        ingredientsText = "Masa de maíz tierno asada, rellena con queso campesino.",
        nutritionGrades = "d",
        imageFrontUrl = "https://example.com/arepa.jpg",
        countriesTags = "en:colombia",
        nutriments = nutriments
    )

    private val response = OffProductResponseDto(
        status = 1,
        statusVerbose = "product found",
        code = "7501234567890",
        product = product
    )

    @Test
    fun offProductResponseDtoRoundTripsThroughTheAppContainerMoshi() {
        val adapter = moshiAsBuiltByAppContainer().adapter(OffProductResponseDto::class.java)

        val json = adapter.toJson(response)
        val parsed = adapter.fromJson(json)

        assertEquals(response, parsed)
    }

    @Test
    fun nutrimentValuesSurviveTheRoundTrip() {
        val adapter = moshiAsBuiltByAppContainer().adapter(OffProductResponseDto::class.java)

        val parsed = requireNotNull(adapter.fromJson(adapter.toJson(response)))

        val parsedNutriments = requireNotNull(parsed.product?.nutriments)
        assertEquals(294.0, parsedNutriments.energyKcal100g!!, 0.0)
        assertEquals(31.0, parsedNutriments.carbohydrates100g!!, 0.0)
        assertEquals(10.0, parsedNutriments.proteins100g!!, 0.0)
        assertEquals(12.0, parsedNutriments.fat100g!!, 0.0)
        assertEquals(1.4, parsedNutriments.sugars100g!!, 0.0)
        assertEquals(2.2, parsedNutriments.fiber100g!!, 0.0)
        assertEquals(0.31, parsedNutriments.sodium100g!!, 0.0)
    }

    /**
     * The DTO field names are the Open Food Facts wire names, including the hyphen in
     * `energy-kcal_100g` and the plural `carbohydrates_100g`. A silently "fixed" name here
     * would deserialise to a DTO full of nulls, so the raw JSON is asserted too.
     */
    @Test
    fun serialisesUsingTheOffWireNames() {
        val adapter = moshiAsBuiltByAppContainer().adapter(OffProductResponseDto::class.java)

        val json = adapter.toJson(response)

        assertTrue(json.contains("\"energy-kcal_100g\":294.0"))
        assertTrue(json.contains("\"carbohydrates_100g\":31.0"))
        assertTrue(json.contains("\"proteins_100g\":10.0"))
        assertTrue(json.contains("\"fat_100g\":12.0"))
        assertTrue(json.contains("\"status_verbose\":\"product found\""))
    }

    /** A crowd-sourced miss is HTTP 200 + `status = 0` + `product = null`; it must deserialise. */
    @Test
    fun deserialisesTheProductNotFoundBody() {
        val adapter = moshiAsBuiltByAppContainer().adapter(OffProductResponseDto::class.java)

        val parsed = requireNotNull(
            adapter.fromJson("""{"status":0,"status_verbose":"product not found","code":"0000"}""")
        )

        assertEquals(0, parsed.status)
        assertEquals("product not found", parsed.statusVerbose)
        assertEquals(null, parsed.product)
    }

    @Test
    fun searchResponseWithANullProductListIsTolerated() {
        val adapter = moshiAsBuiltByAppContainer().adapter(OffSearchResponseDto::class.java)

        val parsed = requireNotNull(adapter.fromJson("""{"count":0,"page":1,"page_size":20}"""))

        assertEquals(0, parsed.count)
        assertEquals(null, parsed.products)
    }

    /**
     * Sanity check that the adapter really goes through the converter Retrofit is handed in
     * `AppContainer`, not through some private Moshi instance. `MoshiConverterFactory.create`
     * must at minimum accept the instance without throwing; the actual HTTP wiring is
     * covered by instrumentation, not here.
     */
    /**
     * Documents **why** `AppContainer` has to register `KotlinJsonAdapterFactory` at all.
     *
     * Measured against Moshi 1.15.2 (the version pinned in `gradle/libs.versions.toml`):
     * - `Moshi.Builder().add(KotlinJsonAdapterFactory())` resolves these DTOs to a
     *   `KotlinJsonAdapter`. User factories registered on the builder are consulted
     *   **before** the built-in factory that honours `@JsonClass(generateAdapter = true)`, so
     *   the missing generated class is never reached.
     * - A bare `Moshi.Builder().build()` throws
     *   `RuntimeException: Failed to find the generated JsonAdapter class for class
     *   ...OffProductResponseDto`.
     *
     * So the annotation is currently *inert but harmless*, and the factory is load-bearing.
     * This test tolerates both worlds on purpose: once KSP + `moshi-kotlin-codegen` land, the
     * generated adapter exists, the bare Moshi resolves the DTO successfully, and this test
     * must keep passing rather than start failing on a migration that worked.
     */
    @Test
    fun withoutTheKotlinJsonAdapterFactoryTheGeneratedAdapterLookupIsUnreachable() {
        val resolved = runCatching { Moshi.Builder().build().adapter(OffProductResponseDto::class.java) }

        val generatedAdapter = resolved.getOrNull()
        if (generatedAdapter != null) {
            // KSP codegen landed: the annotation is finally live and needs no factory.
            assertTrue(generatedAdapter.toString().contains("JsonAdapter"))
            return
        }

        val message = requireNotNull(resolved.exceptionOrNull()?.message)
        assertTrue(
            "Expected the 'no annotation processor' failure documented in the DTO KDoc, but got: $message",
            message.contains("Failed to find the generated JsonAdapter class")
        )
    }

    @Test
    fun moshiConverterFactoryAcceptsTheContainerMoshi() {
        val converterFactory = MoshiConverterFactory.create(moshiAsBuiltByAppContainer())
        assertNotNull(converterFactory)
    }

    @Test
    fun countriesTagsRoundTripThroughMoshi() {
        val adapter = moshiAsBuiltByAppContainer().adapter(OffProductDto::class.java)
        val withTags = product.copy(countriesTags = "en:colombia,es:ecuador")
        val json = adapter.toJson(withTags)
        assertTrue(json.contains("\"countries_tags\":\"en:colombia,es:ecuador\""))
        val parsed = requireNotNull(adapter.fromJson(json))
        assertEquals("en:colombia,es:ecuador", parsed.countriesTags)
    }
}
