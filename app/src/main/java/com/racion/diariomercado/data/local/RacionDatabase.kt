package com.racion.diariomercado.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.racion.diariomercado.data.local.dao.CatalogDao
import com.racion.diariomercado.data.local.dao.DiaryDao
import com.racion.diariomercado.data.local.dao.GoalsDao
import com.racion.diariomercado.data.local.dao.ProfileDao
import com.racion.diariomercado.data.local.dao.SyncOutboxDao
import com.racion.diariomercado.data.local.entity.DiaryEntryEntity
import com.racion.diariomercado.data.local.entity.FoodProductEntity
import com.racion.diariomercado.data.local.entity.NutritionGoalsEntity
import com.racion.diariomercado.data.local.entity.SyncOutboxEntity
import com.racion.diariomercado.data.local.entity.UserProfileEntity

/**
 * The local source of truth: diary, catalog cache, goals, profile.
 *
 * ## `exportSchema = true` is the migration plumbing, not documentation
 * DB-6 asks for a migration strategy "from the first day", and this is the whole of it. Room
 * writes the schema of **this** version to `app/schemas/` (wired by `RoomSchemaArgProvider` in
 * `app/build.gradle.kts`), and that file is what `MigrationTestHelper` loads to build a real v1
 * database on disk when someone writes the v1 -> v2 migration. Without the export there is nothing
 * to migrate *from*, and the first migration gets written blind against a schema nobody recorded.
 *
 * `version = 2` and the `Migration` in the `companion` below are the plumbing working, not more
 * plumbing: v1 exported its schema without ever needing a predecessor, so the first migration is the
 * first time that export has a reader. It is written against the checked-in
 * `app/schemas/...RacionDatabase/1.json` and tested against a real v1 file by `MigrationTestHelper`
 * in `RacionDatabaseMigrationTest`. The next version repeats the pair: bump, add a `Migration`,
 * export the new `N.json`.
 *
 * ## Why there is no `fallbackToDestructiveMigration()`
 * That call makes Room `DROP` the tables and recreate them when a migration is missing. For a
 * cache that is a performance problem; for **this** database it silently deletes the user's diary,
 * goals and profile on any schema change — and a schema change is exactly the moment the user
 * upgrades. No crash, no dialog, no backup: a shorter history and default goals, and nothing in the
 * logs the user can be told about.
 *
 * So it is omitted on purpose, and its absence is the behaviour: Room throws
 * `IllegalStateException: A migration from X to Y was required but not found` at open time. That
 * crash is the feature. It happens in development, where a missing `Migration` is a five-line fix,
 * instead of in a user's kitchen with no signal and no way to recover the rows. Note that it also
 * rules out the tempting shortcut for [MIGRATION_1_2]: a migration that only creates an empty table
 * could have been "verified" by opening a v1 as a v2 with a destructive fallback, which would have
 * tested the fallback instead of the migration.
 */
@Database(
    entities = [
        DiaryEntryEntity::class,
        FoodProductEntity::class,
        NutritionGoalsEntity::class,
        UserProfileEntity::class,
        SyncOutboxEntity::class
    ],
    version = 2,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class RacionDatabase : RoomDatabase() {

    abstract fun diaryDao(): DiaryDao

    abstract fun goalsDao(): GoalsDao

    abstract fun profileDao(): ProfileDao

    abstract fun catalogDao(): CatalogDao

    abstract fun syncOutboxDao(): SyncOutboxDao

    companion object {
        /**
         * On-disk file name. Stable because the local database is the sync's source of truth: a
         * rename here looks to the sync layer like the user losing every entry.
         */
        const val NAME = "racion.db"

        /**
         * v1 -> v2, and it adds exactly one table.
         *
         * ## The DDL is copied from Room's own `createSql` on purpose
         * It is tempting to write this migration by hand and roughly, because it is one
         * `CREATE TABLE`. But `MigrationTestHelper.runMigrationsAndValidate` re-reads the exported v2
         * schema and compares Room's *expected* table definition against the live database's, and a
         * hand-written `CREATE TABLE` that differs in a detail Room cares about — a missing
         * `NOT NULL`, a `TEXT` where it expects `REAL`, a `PRIMARY KEY` written as a table
         * constraint instead of a column one — fails the migration even though the app would have
         * worked fine. Room's generated `2.json` has `createSql` for the table and index; this is it,
         * pasted rather than retyped.
         *
         * ## Why no `ALTER TABLE`
         * Because nothing existing changes. `diary_entries`, `food_products`, `nutrition_goals` and
         * `user_profile` keep their v1 definitions, their data, and their rows: an install that has
         * logged six months of diary goes through this migration with those six months still there.
         * A migration that rewrote existing tables would have to copy every row through the new
         * definition and would have to survive failing halfway, and it would buy nothing.
         *
         * ## `sync_outbox` starts empty, and that is correct
         * There is no backfill. A v1 install has no pending sync work — the outbox did not exist, so
         * there is nothing that could have been queued — and inventing rows would mean guessing at
         * which entries "should" have synced, which for a diary is the user's own history and not
         * the database's to reinterpret. `LocalDiaryRepository` enqueues on the next local write, and
         * the sync of anything older is the catalog leg's problem, not a migration's.
         *
         * ## Deliberately no foreign key to `diary_entries`
         * See `SyncOutboxEntity`: a DELETE row exists precisely to describe an entry that no longer
         * exists, so a foreign key would have to either block that delete or cascade the sync record
         * away.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `sync_outbox` (" +
                        "`entryId` TEXT NOT NULL, " +
                        "`op` TEXT NOT NULL, " +
                        "`productBarcode` TEXT NOT NULL, " +
                        "`productName` TEXT NOT NULL, " +
                        "`servings` INTEGER NOT NULL, " +
                        "`mealSlot` TEXT NOT NULL, " +
                        "`loggedAtEpochMillis` INTEGER NOT NULL, " +
                        "`dayKey` TEXT NOT NULL, " +
                        "`kcal` INTEGER NOT NULL, " +
                        "`carbsG` REAL NOT NULL, " +
                        "`proteinG` REAL NOT NULL, " +
                        "`fatG` REAL NOT NULL, " +
                        "`sugarsG` REAL NOT NULL, " +
                        "`fiberG` REAL NOT NULL, " +
                        "`sodiumG` REAL NOT NULL, " +
                        "`enqueuedAtEpochMillis` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`entryId`))"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_sync_outbox_enqueuedAtEpochMillis` " +
                        "ON `sync_outbox` (`enqueuedAtEpochMillis`)"
                )
            }
        }
    }
}