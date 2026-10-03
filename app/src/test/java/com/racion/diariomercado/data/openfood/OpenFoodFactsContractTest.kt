package com.racion.diariomercado.data.openfood

import com.racion.diariomercado.core.AppError
import com.racion.diariomercado.core.AppResult
import com.racion.diariomercado.data.openfood.dto.OffProductResponseDto
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit

class OpenFoodFactsContractTest {

    private lateinit var server: MockWebServer
    private lateinit var service: OpenFoodFactsService
    private lateinit var moshi: Moshi

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        moshi = Moshi.Builder()
            .add(KotlinJsonAdapterFactory())
            .build()
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("User-Agent", "RacionTuDiarioDeMercado/1.0 (test@example.com)")
                    .build()
                chain.proceed(request)
            }
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
        service = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(OpenFoodFactsService::class.java)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun productByBarcodeRequestCarriesExpectedUserAgent() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        runCatching { service.productByBarcode("123") }
        val recorded = server.takeRequest()
        val userAgent = recorded.getHeader("User-Agent")
        assertEquals("RacionTuDiarioDeMercado/1.0 (test@example.com)", userAgent)
    }

    @Test
    fun statusZeroWithHttp200MapsToNotFound() = runBlocking {
        val body = """{"status":0,"status_verbose":"product not found","code":"000000"}"""
        server.enqueue(MockResponse().setResponseCode(200).setBody(body))
        val repository = OpenFoodFactsCatalogRepository(service)
        val result = repository.productByBarcode("000000")
        assertTrue(result is AppResult.Failure)
        assertEquals(AppError.NotFound, (result as AppResult.Failure).error)
    }

    @Test
    fun successfulProductMapsToSuccessWithEveryFieldTranslated() = runBlocking {
        val body = """
            {"status":1,"status_verbose":"product found","code":"7501234567890",
             "product":{
               "code":"7501234567890",
               "product_name":"Bizcochitos",
               "brands":"Molino Canuelas,Marca Dos",
               "quantity":"215 g",
               "serving_quantity":30,
               "serving_size":"30 g",
               "categories":"Snacks,Biscuits",
               "ingredients_text":"Harina de trigo",
               "nutrition_grades":"E",
               "image_front_url":"http://example.com/front.jpg",
               "countries_tags":"en:colombia",
               "nutriments":{
                 "energy-kcal_100g":480.4,
                 "carbohydrates_100g":68.0,
                 "proteins_100g":6.0,
                 "fat_100g":21.0,
                 "sugars_100g":22.0,
                 "fiber_100g":2.5,
                 "sodium_100g":0.4}}}
        """.trimIndent()
        server.enqueue(MockResponse().setResponseCode(200).setBody(body))

        val result = OpenFoodFactsCatalogRepository(service).productByBarcode("7501234567890")

        assertTrue("expected Success but was $result", result is AppResult.Success)
        val product = (result as AppResult.Success).data
        assertEquals("7501234567890", product.barcode)
        assertEquals("Bizcochitos", product.name)
        // First brand only: OFF routinely joins several with a comma.
        assertEquals("Molino Canuelas", product.brand)
        assertEquals("215 g", product.quantityLabel)
        assertEquals(30.0, product.servingGrams!!, 0.001)
        assertEquals(listOf("Snacks", "Biscuits"), product.categories)
        // Normalised to lower case: OFF is inconsistent about the casing it stores.
        assertEquals("e", product.nutriscoreGrade)
        assertEquals("http://example.com/front.jpg", product.imageUrl)
        assertEquals("Harina de trigo", product.ingredientsText)
        // kcal is rounded, not truncated: the domain stores calories as a count.
        assertEquals(480, product.nutritionPer100g.kcal)
        assertEquals(68.0, product.nutritionPer100g.carbsG, 0.001)
        assertEquals(6.0, product.nutritionPer100g.proteinG, 0.001)
        assertEquals(21.0, product.nutritionPer100g.fatG, 0.001)
        assertEquals(22.0, product.nutritionPer100g.sugarsG, 0.001)
        assertEquals(2.5, product.nutritionPer100g.fiberG, 0.001)
        assertEquals(0.4, product.nutritionPer100g.sodiumG, 0.001)
    }

    @Test
    fun productWithoutNutrimentsMapsToZeroesInsteadOfFailing() = runBlocking {
        // Product exists and is nameable, but OFF carries no nutrition table for it. OFF-5a
        // decision: register it with zeroes rather than blocking the user.
        val body = """
            {"status":1,"code":"7500000000018",
             "product":{"code":"7500000000018","product_name":"Galleta sin tabla",
                        "nutriments":null}}
        """.trimIndent()
        server.enqueue(MockResponse().setResponseCode(200).setBody(body))

        val result = OpenFoodFactsCatalogRepository(service).productByBarcode("7500000000018")

        assertTrue("expected Success but was $result", result is AppResult.Success)
        val nutrition = (result as AppResult.Success).data.nutritionPer100g
        assertEquals(0, nutrition.kcal)
        assertEquals(0.0, nutrition.carbsG, 0.001)
        assertEquals(0.0, nutrition.proteinG, 0.001)
        assertEquals(0.0, nutrition.fatG, 0.001)
    }

    @Test
    fun productWithoutUsableNameMapsToNotFound() = runBlocking {
        // The catalog is crowd-sourced, so unnamed rows exist. A nameless product cannot be
        // rendered, so it is reported the same way a missing barcode is.
        val body = """
            {"status":1,"code":"0000000000017",
             "product":{"code":"0000000000017","product_name":"   ",
                        "nutriments":{"energy-kcal_100g":100.0}}}
        """.trimIndent()
        server.enqueue(MockResponse().setResponseCode(200).setBody(body))

        val result = OpenFoodFactsCatalogRepository(service).productByBarcode("0000000000017")

        assertTrue("expected Failure but was $result", result is AppResult.Failure)
        assertEquals(AppError.NotFound, (result as AppResult.Failure).error)
    }
}