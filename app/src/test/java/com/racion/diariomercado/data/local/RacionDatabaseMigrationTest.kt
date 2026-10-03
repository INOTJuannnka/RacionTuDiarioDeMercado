package com.racion.diariomercado.data.local

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.racion.diariomercado.data.local.entity.SyncOutOp
import com.racion.diariomercado.data.local.entity.SyncOutboxEntity
import com.racion.diariomercado.data.sync.toPayload
import com.racion.diariomercado.domain.model.MealSlot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * The v1 → v2 migration, run against a **real v1 database built from the committed `1.json`**.
 *
 * ## Why this test cannot be faked, and why it is not a unit test of a SQL string
 * The failure this guards against is not "the SQL has a typo" — it is "the migration produces a
 * database Room will not open, on a device that already has six months of diary". Only a test that
 * starts from an actual v1 file and lets Room validate the result can catch it. So this deliberately
 * does **not**:
 * - create the v1 schema by hand (`CREATE TABLE` typed into the test), because a hand-written v1
 *   that drifts from the real `1.json` produces a test that passes against a schema nobody ships;
 * - use `fallbackToDestructiveMigrationOnDowngrade()` to open the v1 as a v2 (design D8), because
 *   that exercises the fallback and not the migration — a test that passes because it deleted the
 *   data it was supposed to migrate is worse than no test;
 * - skip the helper's validate step, which is the only thing that compares Room's expected v2 schema
 *   against what the migration actually built, field by field.
 *
 * ## Why the explicit-`File` constructor and not `createDatabase(name, version)`
 * The name-based constructor builds a `DatabaseConfiguration` whose driver is configured with the
 * *bare* name and then asks Room to open the resolved file path. On a device or emulator the driver
 * tolerates that; under Robolectric it does not, and it fails with
 * `IllegalArgumentException: This driver is configured to open a database named 'migration-test.db'
 * but '<...>/databases/migration-test.db' was requested` before a single line of the migration runs.
 * The `file` + `driver` constructor takes the path as given and pairs it with a driver opened for
 * exactly that path, so nothing has to be re-resolved. That is why this test passes a `File` and an
 * `AndroidSQLiteDriver` instead of the `(Class, specs, openFactory)` triple — measured, not guessed,
 * and the older shape is kept out of the test so the next person does not have to rediscover it.
 *
 * ## Why the data assertions are the point, not the schema validation
 * Validation passing means the migration is *shaped* right. What it cannot mean is that a user's
 * history survived. So every row written into v1 is read back after the migration through the full
 * `DiaryEntryWithProduct` projection — the same read the app uses — with all seven nutrition values
 * asserted individually. A migration that rebuilt `diary_entries` with a renamed or retyped column
 * fails on the values even when the schema nominally validates.
 *
 * ## And what validation does *not* catch, which is why the index is asserted by hand
 * Measured, by mutating `MIGRATION_1_2` and re-running this file:
 * - a column retyped (`sodiumG` `REAL` → `TEXT`) **fails** validation, and both tests go red;
 * - the index **renamed** to `index_sync_outbox_enqueuedAtEpochMillis_MUTATION_PROBE_DISABLED`
 *   **passes** — same columns, same table, wrong name — and the suite stays green.
 *
 * So Room's table comparison checks columns and affinities but not index identity, and a migration
 * that dropped or renamed this index would ship silently: the table still works, the queries still
 * return the right rows, and the only cost is that the drain starts sorting the whole queue. That
 * is why [indexOnTheQueueTimestampExists] reads `sqlite_master` directly. Both mutations were
 * reverted; the assertions below are the ones that were kept.
 */
@RunWith(RobolectricTestRunner::class)
class RacionDatabaseMigrationTest {

    private lateinit var helper: MigrationTestHelper
    private lateinit var context: Context
    private var migrated: RacionDatabase? = null

    private val databaseName = "migration-test.db"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(databaseName)
        helper = MigrationTestHelper(
            instrumentation = InstrumentationRegistry.getInstrumentation(),
            file = File(context.getDatabasePath(databaseName).absolutePath),
            driver = AndroidSQLiteDriver(),
            databaseClass = RacionDatabase::class
        )
    }

    @After
    fun tearDown() {
        migrated?.close()
        context.deleteDatabase(databaseName)
    }

    // -- Migration 1 -> 2 ---------------------------------------------------------------------

    /**
     * The whole point of DB-7: v1 data survives v1 → v2, and `sync_outbox` exists afterwards.
     *
     * The validate step is not optional and takes no argument on this constructor: it re-reads the
     * exported v2 schema and fails the test on any unexpected table, missing column, or wrong
     * affinity. That is what catches a migration that quietly drops something it should have kept —
     * the failure a user would experience as a silently shorter history.
     */
    @Test
    fun migrationFrom1To2KeepsDiaryRowsAndAddsTheOutbox() {
        helper.createDatabase(1).use { v1 -> seedV1Diary(v1) }

        helper.runMigrationsAndValidate(2, listOf(RacionDatabase.MIGRATION_1_2)).close()

        val database = openMigratedDatabase()
        val outboxDao = database.syncOutboxDao()
        val diaryDao = database.diaryDao()

        runTest {
            // The outbox exists, and a v1 install has nothing pending — see MIGRATION_1_2's note on
            // why there is deliberately no backfill.
            assertEquals(
                "no row should be backfilled into a table that did not exist in v1",
                0,
                outboxDao.observePendingCount().first()
            )

            val entry = diaryDao.withProductById("v1-entry")
            assertNotNull("the v1 entry must still be readable after the migration", entry)
            requireNotNull(entry)

            // All seven, individually. Same rule as DiaryDaoTest's aggregate assertions: a partial
            // assertion passes while a column is silently wrong, and the damage here is a user's
            // calorie total rather than a red test. The v1 fixture gives each column a distinct
            // value for the same reason — equal values would let a migration that swapped two
            // columns pass all seven asserts.
            assertEquals(200, entry.kcal)
            assertEquals(30.0, entry.carbsG, 0.0)
            assertEquals(10.0, entry.proteinG, 0.0)
            assertEquals(5.0, entry.fatG, 0.0)
            assertEquals(4.0, entry.sugarsG, 0.0)
            assertEquals(2.0, entry.fiberG, 0.0)
            assertEquals(300.0, entry.sodiumG, 0.0)

            // And the columns that are not nutrition, because a migration that zeroed `servings`
            // would pass every nutrition assertion above.
            assertEquals(2, entry.servings)
            assertEquals(MealSlot.ALMUERZO, entry.mealSlot)
            assertEquals(DAY_KEY, entry.dayKey)

            // The joined product survives too: the FK is RESTRICT, so if the migration had dropped
            // the catalog the entry read would have failed rather than returned zeroes.
            assertEquals(PRODUCT_BARCODE, entry.productBarcode)
            assertEquals(PRODUCT_NAME, entry.productName)
        }
    }

    /**
     * `sync_outbox` is writable after the migration, not merely present.
     *
     * A `CREATE TABLE` missing the primary key constraint, or with a column in the wrong affinity,
     * can leave a table that exists and that validation accepts on paper while the DAO's `@Upsert`
     * — which compiles to `INSERT ... ON CONFLICT(entryId) DO UPDATE` — fails at runtime. Only an
     * actual enqueue proves the constraint exists.
     *
     * This is also where coalescing is exercised end to end on a *migrated* database rather than only
     * on a fresh in-memory one: a table produced without `entryId` as the primary key would let two
     * rows for one entry through and break the collapse rule.
     */
    @Test
    fun outboxIsWritableAndCoalescingOnAMigratedDatabase() = runTest {
        helper.createDatabase(1).use { v1 -> seedV1Diary(v1) }
        helper.runMigrationsAndValidate(2, listOf(RacionDatabase.MIGRATION_1_2)).close()

        val outboxDao = openMigratedDatabase().syncOutboxDao()

        outboxDao.enqueue(v1OutboxRow(op = SyncOutOp.UPSERT, enqueuedAt = 100L, kcal = 200))
        outboxDao.enqueue(v1OutboxRow(op = SyncOutOp.UPSERT, enqueuedAt = 200L, kcal = 999))

        val pending = outboxDao.oldestPending(10)
        assertEquals("one entry id must collapse to one pending row", 1, pending.size)
        assertEquals(999, pending.single().kcal)
        assertEquals("the collapse must refresh the queue timestamp", 200L, pending.single().enqueuedAtEpochMillis)

        // And it round-trips into the wire payload without the queue timestamp leaking into it.
        val payload = pending.single().toPayload()
        assertEquals("v1-entry", payload.entryId)
        assertEquals(999, payload.kcal)
    }

    // -- Fixtures and helpers -----------------------------------------------------------------

    /**
     * The drain's ordering index exists under Room's generated name.
     *
     * Hand-asserted because Room's own validation does not check it — see the class note, where that
     * was measured by renaming the index in the migration and watching this file stay green. The
     * index is what keeps `oldestPending`'s `ORDER BY ... LIMIT` a bounded scan instead of a full
     * sort of the queue, so "the index has the wrong name" is a real performance regression that
     * Room will not report.
     *
     * `sqlite_master` rather than `PRAGMA index_list`: the pragma reports indexes positionally, and
     * what needs pinning here is the exact name Room generated in `2.json`.
     *
     * `sql IS NOT NULL` filters out SQLite's own index for the `entryId` primary key
     * (`sqlite_autoindex_sync_outbox_1`), which every correct migration creates and which is
     * reported alongside the real one.
     */
    @Test
    fun indexOnTheQueueTimestampExists() {
        helper.createDatabase(1).use { v1 -> seedV1Diary(v1) }
        helper.runMigrationsAndValidate(2, listOf(RacionDatabase.MIGRATION_1_2)).close()

        val name = openMigratedDatabase().openHelper.readableDatabase.query(
            """
            SELECT name FROM sqlite_master
            WHERE type = 'index' AND tbl_name = 'sync_outbox' AND sql IS NOT NULL
            """.trimIndent()
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }

        assertEquals("index_sync_outbox_enqueuedAtEpochMillis", name)
    }

    /**
     * Writes one product and one diary entry into a **v1** database, using the exact v1 column names
     * and affinities read out of the committed `1.json`.
     *
     * Nullable v1 columns are left out of the statement so SQLite fills them with NULL — that is what
     * a real v1 row for a product with no brand and no image looks like, and the projection reads
     * them back as null rather than as empty strings.
     *
     * `categories` is written as `'["dairy"]'` and not as `'dairy'` because `Converters.toStringList`
     * parses that column as a JSON array. A bare word there is not a lenient-JSON edge case; it
     * throws `JsonEncodingException` out of the projection read, which looks exactly like a broken
     * migration and is not one.
     *
     * Values are inlined rather than bound because they are test constants, and `SQLiteConnection`
     * exposes `prepare`/`bind*` without an `execSQL(sql, args)` convenience on this API. A literal in
     * a private test fixture is not an injection surface; a bound-argument helper for two INSERTs
     * would be more code than it removes.
     */
    private fun seedV1Diary(v1: SQLiteConnection) {
        v1.insert(
            """
            INSERT INTO food_products (
                barcode, name, kcalPer100g, carbsPer100g, proteinPer100g, fatPer100g,
                sugarsPer100g, fiberPer100g, sodiumPer100g, categories, emoji
            ) VALUES (
                '$PRODUCT_BARCODE', '$PRODUCT_NAME', 200, 30.0, 10.0, 5.0,
                4.0, 2.0, 300.0, '["$CATEGORIES"]', '$EMOJI'
            )
            """.trimIndent()
        )
        v1.insert(
            """
            INSERT INTO diary_entries (
                id, productBarcode, servings, mealSlot, loggedAtEpochMillis, dayKey,
                kcal, carbsG, proteinG, fatG, sugarsG, fiberG, sodiumG
            ) VALUES (
                'v1-entry', '$PRODUCT_BARCODE', 2, 'ALMUERZO', $LOGGED_AT, '$DAY_KEY',
                200, 30.0, 10.0, 5.0, 4.0, 2.0, 300.0
            )
            """.trimIndent()
        )
    }

    /** Runs a no-result statement. `step()` performs the INSERT and returns false for it. */
    private fun SQLiteConnection.insert(sql: String) {
        prepare(sql).use { it.step() }
    }

    /** A row describing the entry seeded above, for the post-migration enqueue. */
    private fun v1OutboxRow(op: SyncOutOp, enqueuedAt: Long, kcal: Int) = SyncOutboxEntity(
        entryId = "v1-entry",
        op = op.name,
        productBarcode = PRODUCT_BARCODE,
        productName = PRODUCT_NAME,
        servings = 2,
        mealSlot = MealSlot.ALMUERZO,
        loggedAtEpochMillis = LOGGED_AT,
        dayKey = DAY_KEY,
        kcal = kcal,
        carbsG = 30.0,
        proteinG = 10.0,
        fatG = 5.0,
        sugarsG = 4.0,
        fiberG = 2.0,
        sodiumG = 300.0,
        enqueuedAtEpochMillis = enqueuedAt
    )

    /**
     * Reopens the migrated file with Room itself.
     *
     * Deliberately no `.addMigrations` and no fallback: the file on disk is already v2, so Room has
     * nothing to migrate and any wiring it needed would be a sign the test is hiding something. If
     * this open throws, the migration produced a file Room will not accept — which is the failure
     * the helper's validate step exists to pre-empt, and is worth catching a second time through the
     * production builder path.
     */
    private fun openMigratedDatabase(): RacionDatabase =
        Room.databaseBuilder(context, RacionDatabase::class.java, databaseName)
            .allowMainThreadQueries()
            .build()
            .also { migrated = it }

    private companion object {
        const val PRODUCT_BARCODE = "7791234567890"
        const val PRODUCT_NAME = "Yogur natural"
        const val CATEGORIES = "dairy"
        const val EMOJI = "🥛"
        const val DAY_KEY = "2026-03-02"
        const val LOGGED_AT = 1_772_419_200_000L
    }
}