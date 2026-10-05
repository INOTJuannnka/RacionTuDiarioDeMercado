package com.racion.diariomercado.di

import android.content.Context
import androidx.room.Room
import com.google.firebase.firestore.FirebaseFirestore
import com.racion.diariomercado.BuildConfig
import com.racion.diariomercado.data.firebase.FirebaseAuthRepository
import com.racion.diariomercado.data.firebase.FirestoreGoalsRepository
import com.racion.diariomercado.data.firebase.FirestoreProfileRepository
import com.racion.diariomercado.data.local.LocalDiaryRepository
import com.racion.diariomercado.data.local.RacionDatabase
import com.racion.diariomercado.data.local.RoomSessionDataReassigner
import com.racion.diariomercado.data.openfood.OpenFoodFactsCatalogRepository
import com.racion.diariomercado.data.openfood.OpenFoodFactsService
import com.racion.diariomercado.domain.repository.AuthRepository
import com.racion.diariomercado.domain.repository.DiaryRepository
import com.racion.diariomercado.domain.repository.FoodCatalogRepository
import com.racion.diariomercado.domain.repository.GoalsRepository
import com.racion.diariomercado.domain.repository.ProfileRepository
import com.racion.diariomercado.domain.repository.SessionDataReassigner
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Manual dependency container: the single place where concrete implementations are chosen.
 *
 * ## Why manual DI instead of Hilt (for now)
 * - **Zero annotation processing.** Hilt requires KSP/KAPT on this module, which is pure
 *   build time and configuration surface while the data layer is still `TODO`s.
 * - **Fully explicit.** Every edge is a `by lazy` property you can read top to bottom. With
 *   Hilt, the graph lives in generated code and the runtime cost shows up in a `@Singleton`
 *   whose scope is easy to get subtly wrong.
 * - **Trivially swappable.** Swapping to Hilt later is a mechanical change: these `by lazy`
 *   bodies become `@Provides @Singleton` functions and screens keep depending on the
 *   interfaces in `domain/repository/`, which never change.
 *
 * Everything is `by lazy` so nothing is built until first use, and nothing holds a `Context`
 * beyond the application context (a static `Context` is a leak otherwise).
 */
class AppContainer(private val context: Context) {

    /**
     * MANDATORY (OFF-1): Open Food Facts blocks requests that do not send a proper
     * `User-Agent`. The value comes from `BuildConfig.OPEN_FOOD_FACTS_USER_AGENT`, which is
     * built as `AppName/Version (contact)` — the exact shape their bot filter expects.
     * Without this interceptor the API answers 403/503 and returns nothing.
     */
    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("User-Agent", BuildConfig.OPEN_FOOD_FACTS_USER_AGENT)
                    .build()
                chain.proceed(request)
            }
            .apply {
                if (BuildConfig.DEBUG) {
                    // BODY logging is a rate-limit footgun: OFF responses are large and
                    // logging them is pure overhead. HEADERS is enough to debug wiring.
                    addInterceptor(
                        HttpLoggingInterceptor().apply {
                            level = HttpLoggingInterceptor.Level.HEADERS
                        }
                    )
                }
            }
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    /**
     * The DTOs in `data/openfood/dto` are annotated `@JsonClass(generateAdapter = true)`, which
     * asks Moshi for a *generated* adapter class — and no annotation processor runs in this
     * module yet, so no `*JsonAdapter` class is ever produced.
     *
     * ## Why the factory below is load-bearing
     * It is tempting to read that annotation as a landmine that throws on the first response.
     * It does not, and `MoshiAdapterTest` pins down why. In Moshi 1.15.2 the
     * `@JsonClass(generateAdapter = true)` branch lives inside a **built-in**
     * [com.squareup.moshi.JsonAdapter.Factory], and factories registered on
     * [Moshi.Builder] are consulted *before* the built-ins. So [KotlinJsonAdapterFactory]
     * claims these DTOs first and the missing generated class is never looked up.
     *
     * Build this Moshi **without** the factory and it does throw
     * `RuntimeException: Failed to find the generated JsonAdapter class for class
     * ...OffProductResponseDto`. That is the one change to this block that breaks the OFF
     * integration, and the test guards it.
     *
     * TODO(OFF-1): apply KSP with `libs.squareup.moshi.kotlin.codegen`, at which point the
     * existing `@JsonClass` annotations on the DTOs start doing real work and this factory can
     * be dropped. Codegen is faster, reflection-free, and proguard-safe. Keep the annotation
     * in the meantime: it is inert but harmless, and removing it would only add churn to that
     * migration.
     */
    private val moshi: Moshi by lazy {
        Moshi.Builder()
            .add(KotlinJsonAdapterFactory())
            .build()
    }

    private val retrofit: Retrofit by lazy {
        Retrofit.Builder()
            .baseUrl(BuildConfig.OPEN_FOOD_FACTS_BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
    }

    /** Retrofit-generated implementation of the OFF endpoints. */
    val openFoodFactsService: OpenFoodFactsService by lazy {
        retrofit.create(OpenFoodFactsService::class.java)
    }

    /** [FoodCatalogRepository] backed by Open Food Facts (OFF-3). */
    val openFoodFactsCatalogRepository: FoodCatalogRepository by lazy {
        OpenFoodFactsCatalogRepository(openFoodFactsService)
    }

    /**
     * [AuthRepository] backed by Firebase Authentication.
     *
     * ## Still takes no constructor argument — and now that is a decision, not a gap
     * FF-2 and FF-3 have landed, so this note previously predicted the opposite: it said the
     * property "will take `FirebaseAuth.getInstance()` the moment both land, and that is the one
     * signature change this property will see". That prediction was wrong, and the way it was
     * wrong is worth keeping.
     *
     * [FirebaseAuthRepository] resolves the handle **lazily inside each method**, never in a
     * constructor or a property. So the seam did not move: this property still reads
     * `FirebaseAuthRepository()` and the wiring below needed no edit when the login landed.
     * Passing the handle in would have made this container depend on the *timing* of Firebase
     * initialisation — `FirebaseAuth.getInstance()` throws when `FirebaseApp` is not ready yet —
     * and a manual DI container is exactly the place where that ordering bug would be hardest to
     * see. Lazy resolution moves the failure to the call that actually needs it.
     *
     * It is exposed here rather than in the TODO block below because the login screens are real
     * and they need an interface to bind against. The dependency is on the INTERFACE only — see
     * the note in the TODO block.
     */
    val authRepository: AuthRepository by lazy {
        FirebaseAuthRepository()
    }

    /**
     * The local ROOM database, built here rather than in `RationApplication`.
     *
     * ## Why not in `RationApplication.onCreate()`
     * `RationApplication` belongs to FF-2, and opening a database from `onCreate` makes this
     * container a passive bystander: a test, a preview or any future `Application` subclass would
     * silently get a different database instance than production does. Owning the builder here
     * keeps the whole graph — including the one expensive-to-build singleton — readable in this
     * file, which is the entire point of manual DI.
     *
     * ## The destructive fallbacks are absent, and `.addMigrations(...)` is present
     * No `fallbackToDestructiveMigration` and no `fallbackToDestructiveMigrationOnDowngrade`.
     * `RacionDatabase` omits them on purpose (see its KDoc): on a missing migration Room throws at
     * open time rather than `DROP`-ing the user's diary, and that crash is the feature.
     *
     * `.addMigrations(RacionDatabase.MIGRATION_1_2)` is what makes that crash not fire on upgrade.
     * Without it, every install that was on v1 throws `IllegalStateException: A migration from 1 to
     * 2 was required but not found` the moment DB-7's version bump ships — which is the trade this
     * line is closing: the strictness above is only affordable because the real migration is
     * registered here.
     *
     * The downgrade variant stays absent, and it is worth being explicit about why, because the
     * reason changed since it was first written. It existed to let a test build a v1 database and
     * reopen it as v2 without a `Migration`. DB-7's `MigrationTestHelper` test does exactly that,
     * and it needs no help from here — it constructs its own database against the checked-in
     * `app/schemas/1.json`. So the tempting shortcut is available and remains rejected: it would
     * exercise the destructive fallback instead of the migration, and a test that passes because
     * it deleted the data it was meant to migrate proves nothing about the migration.
     */
    private val rationDatabase: RacionDatabase by lazy {
        Room.databaseBuilder(context, RacionDatabase::class.java, RacionDatabase.NAME)
            .addMigrations(RacionDatabase.MIGRATION_1_2)
            .build()
    }

    /**
     * The app's [DiaryRepository]: ROOM, local-first, offline-tolerant.
     *
     * ## Why it replaces the `FirestoreDiaryRepository` line in the TODO block
     * The roadmap fixes the data flow — "toda escritura va primero a la base local; Firestore es
     * la proyección de sync/backup, nunca el primer destino" — so the repository the screens
     * depend on has to be the local one. Wiring Firestore here instead would make the app's
     * diary unreadable without a network, which is the opposite of the requirement.
     *
     * `FirestoreDiaryRepository` is not wasted work: it becomes the *sync* leg in DB-7, behind
     * `SyncTransport`, and the interface both satisfy is unchanged.
     *
     * The `userId` is [LocalDiaryRepository.LOCAL_USER_ID] until `AuthRepository` exposes the
     * session uid — a substitution point in one line, not an architecture decision.
     *
     * `syncOutboxDao` is wired here for the same reason `catalogDao` is: DB-7 made it a
     * constructor dependency, and a repository that took the database already owns it could have
     * reached it through `database.syncOutboxDao()`. Passing the DAO is the house convention —
     * dependencies named at the edge, one accessor per collaborator — and it is what lets a test
     * assert on the queue without constructing a second repository.
     *
     * `now` is left at its default on purpose. It exists so tests can order the queue; production
     * wants the wall clock, and spelling out `System::currentTimeMillis` here would be a line that
     * can only ever be deleted.
     */
    val diaryRepository: DiaryRepository by lazy {
        LocalDiaryRepository(
            diaryDao = rationDatabase.diaryDao(),
            catalogDao = rationDatabase.catalogDao(),
            goalsDao = rationDatabase.goalsDao(),
            syncOutboxDao = rationDatabase.syncOutboxDao(),
            database = rationDatabase,
            userId = LocalDiaryRepository.LOCAL_USER_ID
        )
    }

    /**
     * Moves this device's per-user rows when someone claims an anonymous account into one that
     * already existed — the only claim path where the uid actually moves.
     *
     * ## Why the database, not the two DAOs
     * The move spans `user_profiles` and `nutrition_goals`, and a Room `@Transaction` can only
     * decorate a method on a single DAO. `RoomSessionDataReassigner` therefore takes the database
     * and opens one `withTransaction` across both — see its KDoc for why a half-finished state here
     * would be invisible to the user.
     *
     * Taking `rationDatabase` rather than re-deriving the builder is deliberate and not an
     * optimisation: two `Room.databaseBuilder` calls for the same file name hand out two separate
     * connection pools, and a transaction on one would not cover the other. It is also why
     * `rationDatabase` is `private` — this accessor is the only way out.
     */
    val sessionDataReassigner: SessionDataReassigner by lazy {
        RoomSessionDataReassigner(rationDatabase)
    }

    /**
     * [GoalsRepository] backed by Cloud Firestore (FF-5).
     *
     * ## Why both repositories take lambdas and none of them take a handle
     * The commented TODO that used to sit here read `FirestoreGoalsRepository(firestore)`. Passing
     * the instance would move the ordering bug this file already documents one level up: the
     * container would resolve `FirebaseFirestore.getInstance()` while the UI is building, which
     * throws `IllegalStateException` when `FirebaseApp` is not ready yet.
     *
     * A provider defers that to the call that actually needs it, and it is the same shape
     * [FirebaseAuthRepository] settled on after FF-2 and FF-3 — the note on [authRepository]
     * explains why that prediction turned out to be wrong.
     *
     * ## Why `currentUid` goes through [authRepository] instead of reading Firebase Auth directly
     * So there is exactly one place that answers "who is the current user". A repository reaching
     * for `FirebaseAuth.getInstance()` on its own would be a second answer that could disagree with
     * the first the moment the auth repository gained caching or an override.
     *
     * No session is not an error at construction time: both repositories resolve the uid per call
     * and report "no session" as a value or a `Failure`, never as a thrown exception.
     */
    val goalsRepository: GoalsRepository by lazy {
        FirestoreGoalsRepository(
            firestore = { FirebaseFirestore.getInstance() },
            currentUid = { authRepository.currentUid }
        )
    }

    /**
     * [ProfileRepository] backed by Cloud Firestore (FF-5).
     *
     * Same construction rationale as [goalsRepository]: providers instead of handles, and the uid
     * read through [authRepository] so the session has a single owner.
     */
    val profileRepository: ProfileRepository by lazy {
        FirestoreProfileRepository(
            firestore = { FirebaseFirestore.getInstance() },
            currentUid = { authRepository.currentUid }
        )
    }

    // `diaryRepository`, `goalsRepository` and `profileRepository` are no longer in this list: all
    // three are wired above.
    //
    // Screens must depend on the INTERFACES, never on the implementations above, so the
    // Open Food Facts / ROOM / Firestore choice stays replaceable.
    //
    // TODO(ST-1): once ViewModels exist, scope the repositories to them (or to an explicit
    // application-scoped holder) instead of leaking them into the Activity.
}
