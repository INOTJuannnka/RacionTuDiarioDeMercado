package com.racion.diariomercado.data.local

import androidx.room.TypeConverter
import com.racion.diariomercado.domain.model.DayOfWeek
import com.racion.diariomercado.domain.model.MealSlot
import com.racion.diariomercado.domain.model.SportFocus
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import java.time.DayOfWeek as JavaDayOfWeek

/**
 * Every non-primitive column type in the local schema, in both directions.
 *
 * Registered once on `RacionDatabase` via `@TypeConverters`, so a new entity that needs one of
 * these types does not repeat the registration.
 *
 * ## Two different codecs for two different kinds of data
 * The categories list is encoded as JSON; the `activeDays` set is encoded as a comma-delimited
 * list of enum constant names. That is not an inconsistency, it is the whole point:
 *
 * - **Enum constant names are a closed, comma-free domain owned by the compiler.** `MONDAY,
 *   TUESDAY, ... SUNDAY` is a fixed set the type system will reject an addition to without a
 *   deliberate code change, and none of those identifiers can contain a comma. A delimited codec
 *   is therefore lossless *by construction*, and it leaves a legible value in the schema dump and
 *   in `sqlite3`.
 * - **Category strings are free-form remote data.** Open Food Facts hands back one comma-separated
 *   string that the domain model splits, so its elements routinely contain commas of their own
 *   ("Beverages,Alcoholic beverages" is one category, not two), plus quotes, empty segments and
 *   non-ASCII accents. A `joinToString(",")` / `split(",")` pair on that data is not lossy *in
 *   theory* — it is lossy on the first real row, with no error and no way to notice.
 *
 * That second case is why this file uses Moshi's `List<String>` adapter instead of a hand-rolled
 * escape scheme. See [stringListAdapter] for the rest of that argument.
 *
 * ## Unknown enum constants degrade, they do not throw
 * A stored name that no longer resolves means the row was written by a different version of the
 * app — an added constant and a downgrade, or a corrupted cell. Both read paths below substitute
 * or drop instead of throwing, and **neither rewrites the stored string**. That asymmetry is
 * deliberate: crashing would take down the whole diary screen (and with it the kcal totals that
 * do not depend on the enum at all) to protect a display-only field, and overwriting the cell
 * would destroy evidence that a downgrade happened. A user who reinstalls the newer build reads
 * the original value back.
 */
class Converters {

    // -- MealSlot ---------------------------------------------------------------------------

    /**
     * Keyed off `name`, never off `label`.
     *
     * `MealSlot.label` is the Spanish string rendered verbatim in the diary row meta
     * ("ALMUERZO · 12:45 PM") because the UI language is Spanish. It is a translation, and a
     * translation gets reworded; storing it would mean a copy edit requires a data migration.
     */
    @TypeConverter
    fun fromMealSlot(value: MealSlot): String = value.name

    /** Unknown names fall back to [MealSlot.DESAYUNO], the first slot of the day. */
    @TypeConverter
    fun toMealSlot(value: String): MealSlot =
        enumValueOrNull<MealSlot>(value) ?: MealSlot.DESAYUNO

    // -- SportFocus -------------------------------------------------------------------------

    /**
     * Keyed off `name`, never off `title` or `description` — both are Spanish display copy on the
     * "Perfil deportivo" screen, and [SportFocus]'s KDoc calls out that a copy edit must never
     * require a data migration.
     */
    @TypeConverter
    fun fromSportFocus(value: SportFocus): String = value.name

    /**
     * Unknown names fall back to [SportFocus.MANTENIMIENTO], which is not an arbitrary guess: it
     * is the documented "no specific goal" option and the default `UserProfile.sportFocus` already
     * uses.
     */
    @TypeConverter
    fun toSportFocus(value: String): SportFocus =
        enumValueOrNull<SportFocus>(value) ?: SportFocus.MANTENIMIENTO

    // -- Set<DayOfWeek> ---------------------------------------------------------------------

    /**
     * Encoded Monday..Sunday, always in that order.
     *
     * The ordering is the point of the JDK import alias. [DayOfWeek] in `domain/model` carries the
     * one-letter Spanish labels and nothing else, so it cannot order itself; `java.time.DayOfWeek`
     * can. A `Set` has no defined iteration order, and while `enumSetOf` happens to iterate in
     * declaration order, a set built by deserialisation or by a Kotlin `map{}.toSet()` iterates
     * in insertion order — so the same user's rows could store `MONDAY,TUESDAY` on one launch and
     * `TUESDAY,MONDAY` on the next. Nothing would break functionally, but the stored bytes would
     * stop being reproducible, and a column whose value depends on hash order is a column nobody
     * can diff.
     */
    @TypeConverter
    fun fromDayOfWeekSet(value: Set<DayOfWeek>): String =
        value.sortedBy { ISO_DAY_ORDER[it.name] ?: Int.MAX_VALUE }
            .joinToString(separator = ",") { it.name }

    /**
     * Unrecognised tokens are dropped rather than fatal: one unresolvable weekday must not make a
     * user's weight targets unreadable. Losing a single active-day flag degrades a checkbox; a
     * thrown exception loses the whole goals row.
     */
    @TypeConverter
    fun toDayOfWeekSet(value: String): Set<DayOfWeek> =
        if (value.isEmpty()) {
            emptySet()
        } else {
            value.split(",").mapNotNull { token ->
                token.takeIf { it.isNotEmpty() }
                    ?.let { enumValueOrNull<DayOfWeek>(it) }
            }.toSet()
        }

    // -- List<String> (product categories) --------------------------------------------------

    /**
     * JSON array of strings.
     *
     * Rejected alternatives, in the order they are tempting:
     * - `joinToString(",")` / `split(",")` — corrupts and **truncates** on the first category that
     *   contains a comma, which the source format produces routinely.
     * - `joinToString("|")` — the same bug with a delimiter nobody has spotted yet.
     * - A hand-rolled escape (`\` before delimiter and quote) — smaller, but it is a new parser
     *   to get right, with a new failure mode per delimiter, and nothing in this project would test
     *   it harder than Moshi is already tested.
     *
     * A JSON array escapes what it must and preserves element order, element count, empty strings
     * and embedded quotes exactly, so `["a,b", "", "he said \"hi\""]` survives byte for byte.
     *
     * Built on a **bare** Moshi with no `KotlinJsonAdapterFactory`, unlike the app-wide instance in
     * `AppContainer`. That is not an oversight: this adapter only ever handles
     * `List<String>`, which Moshi resolves through its built-in collection adapter over the
     * built-in `String` adapter, so no reflection and no Kotlin metadata are involved. Leaving the
     * factory out removes the `generateAdapter` landmine documented in `AppContainer` from a code
     * path that cannot hit it anyway. `Moshi` is thread-safe and the adapter is resolved once.
     */
    private val stringListAdapter by lazy {
        Moshi.Builder()
            .build()
            .adapter<List<String>>(
                Types.newParameterizedType(List::class.java, String::class.java)
            )
    }

    @TypeConverter
    fun fromStringList(value: List<String>): String = stringListAdapter.toJson(value)

    /**
     * A JSON `null` literal becomes an empty list — that is a real, recoverable "no categories".
     * Malformed JSON is *not* swallowed: it throws, because a truncated cell is genuine corruption
     * and returning `emptyList()` for it would silently erase a product's categories with no trace
     * that anything had ever been stored there.
     */
    @TypeConverter
    fun toStringList(value: String): List<String> = stringListAdapter.fromJson(value).orEmpty()

    private companion object {
        /** ISO-8601 ordinals, Monday = 1 .. Sunday = 7. Mirrors `java.time.DayOfWeek.getValue()`. */
        private val ISO_DAY_ORDER: Map<String, Int> = JavaDayOfWeek.values()
            .associateBy({ it.name }, { it.value })
    }
}

/**
 * `enumValueOf` throws on an unknown name; every read path in [Converters] wants to decide what an
 * unknown name means instead of finding out from a stack trace. Going through `enumValues` keeps
 * the compile-time check that the constant name belongs to [T], which a `Map<String, T>` lookup
 * would throw away.
 */
private inline fun <reified T : Enum<T>> enumValueOrNull(name: String): T? =
    enumValues<T>().firstOrNull { it.name == name }