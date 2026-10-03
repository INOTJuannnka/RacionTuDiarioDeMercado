package com.racion.diariomercado.data.local

import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.racion.diariomercado.data.local.dao.CatalogDao
import com.racion.diariomercado.data.local.dao.GoalsDao
import com.racion.diariomercado.data.local.dao.ProfileDao
import com.racion.diariomercado.data.local.entity.FoodProductEntity
import com.racion.diariomercado.data.local.entity.NutritionGoalsEntity
import com.racion.diariomercado.data.local.entity.UserProfileEntity
import com.racion.diariomercado.domain.model.DayOfWeek
import com.racion.diariomercado.domain.model.SportFocus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The cache and the single-row tables, against a real in-memory Room database.
 *
 * The categories round-trip is here rather than only in `ConvertersTest` on purpose: a converter
 * can be lossless and still be registered wrongly, or a column can come back as its JSON text
 * instead of a list, and only a query proves the wiring. The pure-codec test proves the encoding;
 * this one proves Room actually calls it.
 */
@RunWith(RobolectricTestRunner::class)
class CatalogDaoTest {

    private lateinit var db: RacionDatabase
    private lateinit var catalogDao: CatalogDao
    private lateinit var goalsDao: GoalsDao
    private lateinit var profileDao: ProfileDao

    private val barcode = "7791234567890"
    private val userId = "user-1"

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            RacionDatabase::class.java
        )
            .allowMainThreadQueries()
            .build()
        catalogDao = db.catalogDao()
        goalsDao = db.goalsDao()
        profileDao = db.profileDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    // -- Catalog: categories and identity ----------------------------------------------------

    /**
     * A category list containing a comma, an empty string and embedded quotes survives a full
     * write/read cycle through the database.
     *
     * Two failures are possible here and only this test sees both: the codec corrupts the data
     * (covered in `ConvertersTest`) or Room fails to register the converter and hands back the
     * raw JSON string as a `List<String>`, which fails at the type boundary instead.
     */
    @Test
    fun categoriesRoundTripThroughTheDatabaseUnchanged() = runTest {
        val categories = listOf(
            "Beverages,Alcoholic beverages",
            "",
            "he said \"organic\" twice",
            "Plats préparés"
        )
        catalogDao.upsert(product(categories = categories))

        val restored = catalogDao.byBarcode(barcode).first()

        assertEquals(categories, restored?.categories)
    }

    /** The same comma-bearing list is visible as a single element, not split into two. */
    @Test
    fun aCommaBearingCategoryStaysOneElementInTheDatabase() = runTest {
        catalogDao.upsert(product(categories = listOf("Beverages,Alcoholic beverages")))

        val restored = catalogDao.byBarcode(barcode).first()!!

        assertEquals(1, restored.categories.size)
        assertEquals("Beverages,Alcoholic beverages", restored.categories.single())
    }

    /** A lookup for a barcode this phone has never seen is `null`, not an empty product. */
    @Test
    fun anUnknownBarcodeResolvesToNull() = runTest {
        catalogDao.upsert(product())

        assertNull(catalogDao.byBarcode("0000000000000").first())
    }

    /** The nullable scalars survive as nulls, so "not declared" is distinguishable from "zero". */
    @Test
    fun undeclaredProductFieldsStayNull() = runTest {
        catalogDao.upsert(
            product().copy(brand = null, servingGrams = null, nutriscoreGrade = null, imageUrl = null)
        )

        val restored = catalogDao.byBarcode(barcode).first()!!

        assertNull(restored.brand)
        assertNull(restored.servingGrams)
        assertNull(restored.nutriscoreGrade)
        assertNull(restored.imageUrl)
    }

    /**
     * The seven per-100 g columns round-trip exactly. A catalog row with a swapped `fatG` /
     * `sugarG` pair would be invisible on the scan screen until someone counted the macros.
     */
    @Test
    fun everyNutritionColumnRoundTripsThroughTheDatabase() = runTest {
        catalogDao.upsert(
            product().copy(
                kcalPer100g = 294, carbsPer100g = 31.0, proteinPer100g = 10.0, fatPer100g = 12.0,
                sugarsPer100g = 2.5, fiberPer100g = 1.75, sodiumPer100g = 320.5
            )
        )

        val restored = catalogDao.byBarcode(barcode).first()!!

        assertEquals(294, restored.kcalPer100g)
        assertEquals(31.0, restored.carbsPer100g, 0.0)
        assertEquals(10.0, restored.proteinPer100g, 0.0)
        assertEquals(12.0, restored.fatPer100g, 0.0)
        assertEquals(2.5, restored.sugarsPer100g, 0.0)
        assertEquals(1.75, restored.fiberPer100g, 0.0)
        assertEquals(320.5, restored.sodiumPer100g, 0.0)
    }

    /**
     * Re-scanning a product replaces its row. Open Food Facts corrects nutrition data, and a cache
     * that appended a second row per scan would show the stale one forever.
     */
    @Test
    fun upsertingTheSameBarcodeUpdatesTheCachedProduct() = runTest {
        catalogDao.upsert(product().copy(name = "Old name", kcalPer100g = 500))
        catalogDao.upsert(product().copy(name = "Corrected name", kcalPer100g = 480))

        val restored = catalogDao.byBarcode(barcode).first()!!

        assertEquals("Corrected name", restored.name)
        assertEquals(480, restored.kcalPer100g)
        assertEquals(1, rowCount("food_products", barcode))
    }

    // -- DB-5: the unique barcode index ------------------------------------------------------

    /**
     * A second row with the same barcode is rejected by the unique index (DB-5).
     *
     * Exercised at the SQL layer on purpose: `CatalogDao` only offers `@Upsert`, which resolves on
     * the barcode and so *cannot* produce a violation — the write path is correct by construction.
     * The constraint the roadmap asks for defends against something else: a mapping bug that
     * normalises two different source products onto one barcode. That is only reachable by
     * bypassing the DAO, which is exactly what this test does.
     */
    @Test
    fun theUniqueBarcodeIndexRejectsADuplicateRow() = runTest {
        catalogDao.upsert(product())

        assertThrows(SQLiteConstraintException::class.java) {
            db.openHelper.writableDatabase.execSQL(
                """
                INSERT INTO food_products (
                    barcode, name, kcalPer100g, carbsPer100g, proteinPer100g, fatPer100g,
                    sugarsPer100g, fiberPer100g, sodiumPer100g, categories, emoji
                ) VALUES (
                    '$barcode', 'Duplicated', 100, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, '[]', 'x'
                )
                """.trimIndent()
            )
        }

        assertEquals(1, rowCount("food_products", barcode))
    }

    /** DB-5's index is in the exported schema, which is the artifact a migration test reads. */
    @Test
    fun theExportedSchemaDeclaresTheUniqueBarcodeIndex() {
        val indices = db.openHelper.writableDatabase
            .query("PRAGMA index_list('food_products')")
            .use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(cursor.getInt(cursor.getColumnIndexOrThrow("unique")) to cursor.getString(1))
                    }
                }
            }

        assertTrue(
            "expected a unique index on food_products, found $indices",
            indices.any { it.first == 1 }
        )
    }

    /** DB-5 also indexes the diary's day key, which is what the week range scan rides on. */
    @Test
    fun theExportedSchemaIndexesTheDiaryDayKey() {
        val indexedColumns = db.openHelper.writableDatabase
            .query("PRAGMA index_list('diary_entries')")
            .use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        val indexName = cursor.getString(1)
                        db.openHelper.writableDatabase
                            .query("PRAGMA index_info('$indexName')")
                            .use { info ->
                                while (info.moveToNext()) add(info.getString(2))
                            }
                    }
                }
            }

        assertTrue(
            "expected dayKey to be indexed, indexed columns were $indexedColumns",
            indexedColumns.contains("dayKey")
        )
    }

    // -- Goals: single row, observable -------------------------------------------------------

    /** An unwritten goals row emits `null` — a fresh install, a real state and not a failure. */
    @Test
    fun goalsAreNullBeforeAnythingIsSaved() = runTest {
        assertNull(goalsDao.observeGoals(userId).first())
    }

    @Test
    fun savedGoalsComeBackIntactIncludingTheDaySet() = runTest {
        val days = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.SATURDAY)
        goalsDao.saveGoals(
            NutritionGoalsEntity(
                userId = userId,
                targetWeightKg = 71.5f,
                currentWeightKg = 74f,
                kcalPerDay = 2350,
                activeDays = days,
                carbsPct = 50,
                proteinPct = 20,
                fatPct = 30
            )
        )

        val restored = goalsDao.observeGoals(userId).first()!!

        assertEquals(71.5f, restored.targetWeightKg, 0f)
        assertEquals(2350, restored.kcalPerDay)
        assertEquals(days, restored.activeDays)
        assertEquals(50, restored.carbsPct)
        assertEquals(20, restored.proteinPct)
        assertEquals(30, restored.fatPct)
    }

    /**
     * Last-write-wins: the second save replaces the row wholesale. A merge would keep the fields
     * the caller left at their defaults, which is indistinguishable from the user having chosen
     * those defaults on purpose.
     */
    @Test
    fun savingGoalsTwiceKeepsOnlyTheLastWrite() = runTest {
        goalsDao.saveGoals(NutritionGoalsEntity(userId = userId, kcalPerDay = 2000))
        goalsDao.saveGoals(NutritionGoalsEntity(userId = userId, kcalPerDay = 1700, proteinPct = 30))

        val restored = goalsDao.observeGoals(userId).first()!!

        assertEquals(1700, restored.kcalPerDay)
        assertEquals(30, restored.proteinPct)
        assertEquals(45, restored.carbsPct)
    }

    /** Goals are keyed by user, so a second account cannot overwrite the first one's targets. */
    @Test
    fun goalsAreScopedToTheirUser() = runTest {
        goalsDao.saveGoals(NutritionGoalsEntity(userId = userId, kcalPerDay = 2000))
        goalsDao.saveGoals(NutritionGoalsEntity(userId = "user-2", kcalPerDay = 1500))

        assertEquals(2000, goalsDao.observeGoals(userId).first()?.kcalPerDay)
        assertEquals(1500, goalsDao.observeGoals("user-2").first()?.kcalPerDay)
    }

    // -- Profile: single row, observable -----------------------------------------------------

    @Test
    fun aProfileIsNullBeforeAnythingIsSaved() = runTest {
        assertNull(profileDao.observeProfile(userId).first())
    }

    @Test
    fun savedProfilesComeBackIntact() = runTest {
        profileDao.saveProfile(
            UserProfileEntity(
                userId = userId,
                displayName = "Julian",
                sportFocus = SportFocus.FUERZA,
                currentWeightKg = 74.5f
            )
        )

        val restored = profileDao.observeProfile(userId).first()!!

        assertEquals("Julian", restored.displayName)
        assertEquals(SportFocus.FUERZA, restored.sportFocus)
        assertEquals(74.5f, restored.currentWeightKg, 0f)
    }

    /**
     * `sportFocus` is stored as the constant name. If it regressed to `title`, this row would
     * still read back as a valid enum — only the raw column would show the Spanish copy edit that
     * now needs a data migration.
     */
    @Test
    fun theSportFocusColumnHoldsTheConstantName() = runTest {
        profileDao.saveProfile(
            UserProfileEntity(userId = userId, displayName = "Julian", sportFocus = SportFocus.PERDIDA_PESO)
        )

        val stored = db.openHelper.writableDatabase
            .query("SELECT sportFocus FROM user_profiles WHERE userId = '$userId'")
            .use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

        assertEquals("PERDIDA_PESO", stored)
        assertTrue(stored != SportFocus.PERDIDA_PESO.title)
    }

    // -- Fixtures ----------------------------------------------------------------------------

    private fun product(
        categories: List<String> = listOf("Snacks", "Beverages,Alcoholic beverages")
    ) = FoodProductEntity(
        barcode = barcode,
        name = "Arepa de choclo con queso",
        brand = "Local",
        quantityLabel = "500 g",
        servingGrams = 90.0,
        kcalPer100g = 294,
        carbsPer100g = 31.0,
        proteinPer100g = 10.0,
        fatPer100g = 12.0,
        sugarsPer100g = 2.5,
        fiberPer100g = 1.75,
        sodiumPer100g = 320.5,
        imageUrl = "https://example.test/arepa.jpg",
        nutriscoreGrade = "d",
        categories = categories,
        ingredientsText = "Maíz, queso, agua"
    )

    private fun rowCount(table: String, key: String): Int =
        db.openHelper.writableDatabase
            .query("SELECT COUNT(*) FROM $table WHERE ${if (table == "food_products") "barcode" else "userId"} = '$key'")
            .use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }
}