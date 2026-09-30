# Roadmap

Ordered checklist for taking **Ración — Tu Diario de Mercado** from a UI-only prototype to a
working app. Every task carries an ID so the inline `TODO()` comments in the code can cite it.

ID prefixes:

| Prefix  | Area                                                        |
|---------|-------------------------------------------------------------|
| `FF-N`  | Firebase (Auth, Firestore, DI wiring)                       |
| `OFF-N` | Open Food Facts (Retrofit, Moshi, mapping, rate limits)     |
| `BC-N`  | Barcode scanning (CameraX + ML Kit)                         |
| `ST-N`  | State management (ViewModels, navigation results)           |
| `DB-N`  | Local persistence (ROOM, offline-first source of truth)    |
| `Q-N`   | Quality (tests, lint, CI)                                   |

Reference material for every external fact referenced here lives in
[`docs/INTEGRATION.md`](./INTEGRATION.md). Read that before re-deriving anything about the OFF
API or Firestore limits.

---

## Phase 1 — Foundation (package identity, layers, seams) ✅ DONE

- [x] Move the app to its real identity: `com.example.myapplication` → `com.racion.diariomercado`
      (namespace **and** `applicationId`, still cheap because the app is not on Play yet).
- [x] Move the UI package `com.nutriapp.*` → `com.racion.diariomercado.ui.*`.
- [x] Rename the leftover XML theme `Theme.MyApplication` → `Theme.Racion`.
- [x] Extract the domain models out of the screen files into
      `domain/model/`: `Nutrition`, `FoodProduct`, `DiaryEntry` + `MealSlot`, `DailySummary`,
      `WeeklyReport` + `DayTotal` + `MacroSplit`, `NutritionGoals` + `DayOfWeek`, `UserProfile` +
      `SportFocus`.
- [x] Drop the `Color?` fields that had leaked into the models. `iconBg` was a UI concern; the
      domain does not know what a background colour is. `emoji` stayed — it is content.
- [x] Add the failure taxonomy `core/AppResult` + `AppError` so no repository ever throws a raw
      `IOException` at a screen.
- [x] Declare the four repository interfaces (`FoodCatalogRepository`, `DiaryRepository`,
      `GoalsRepository`, `ProfileRepository`) with their contracts documented.
- [x] Stand up the data layer as explicit, TODO-marked stubs: Moshi DTOs, the Retrofit service,
      the OFF catalog repository and the three Firestore repositories.
- [x] Wire manual DI (`di/AppContainer`) and `RacionApplication`.
- [x] Centralise every fake value into `ui/preview/PreviewData` and rewire all eight screens to
      domain models, so the app runs end to end with no backend at all.
- [x] Register Retrofit/OkHttp/Moshi/Coil/Firebase/ML Kit in `libs.versions.toml` and
      `app/build.gradle.kts` (libraries only — the google-services plugin is deliberately NOT
      applied, see `FF-3`).
- [x] Add `INTERNET` / `CAMERA` permissions and mark the camera feature optional so the app still
      installs on camera-less devices.
- [x] Delivery requirement — **Navigation Component**: already live via `navigation-compose`
      (`NavHost` + `rememberNavController`, 8 routes in `AppNavigation`). Nothing to add.
- [x] Delivery requirement — **ViewModel infrastructure**: `androidx-lifecycle-viewmodel-compose`
      is in the catalog; the implementation is `ST-1`, now mandatory rather than optional.

---

## Phase 2 — Local database (ROOM) + Firebase sync

### 2.1 Local database — ROOM (offline-first source of truth)

The diary, profile and goals live in ROOM. Every write goes to the local database first;
Firestore is the sync/backup projection, never the first stop. This satisfies the delivery
requirement for a local DB and fits a market app with intermittent signal.

- [ ] **DB-1.** Add `androidx.room:room-runtime`, `room-ktx` and `room-compiler` (KSP) to
      `libs.versions.toml`; enable the KSP plugin in `app/build.gradle.kts` (this also unblocks the
      generated Moshi adapters from `OFF-1`).
- [ ] **DB-2.** Entities: `DiaryEntryEntity`, `FoodProductEntity` (catalog cache for `LOCAL-*` and
      scanned products), `NutritionGoalsEntity`, `UserProfileEntity`. Day keys are `yyyy-MM-dd`
      strings so a week is a lexical range query, matching the Firestore layout.
- [ ] **DB-3.** DAOs: `DiaryDao` (entries by day, by week, upsert, delete), `GoalsDao`,
      `ProfileDao`, `CatalogDao`. Every read exposes a `Flow` — one stream per screen.
- [ ] **DB-4.** Day totals are computed with `SUM` in the DAO query — no write-contention document
      locally. The remote `days/{date}` totals remain the server-side projection for sync.
- [ ] **DB-5.** Indices: `@Index` on `DiaryEntry.dayKey` and a unique `@Index` on
      `CatalogEntry.barcode`.
- [ ] **DB-6.** Versioned `RoomDatabase` with a migration strategy from day one (an empty migration
      path is acceptable pre-release, but the plumbing exists).

### 2.2 Project and configuration

- [ ] **FF-1.** Create the Firebase project and add the Android app with
      `com.racion.diariomercado` as the package name. Download `google-services.json` and place
      it at `app/google-services.json`. It is already in `.gitignore` — never commit it.
- [ ] **FF-2.** Initialise Firebase in `RacionApplication.onCreate()`:
      `FirebaseApp.initializeApp(this)`, then anonymous sign-in, then create the Firestore
      instance and configure it **once** via `setFirestoreSettings()`.
      - [ ] Do **not** call the deprecated `setPersistenceEnabled()`. Offline persistence is
            already on by default through `PersistentCacheSettings`.
      - [ ] `setFirestoreSettings()` must run before any other call on that instance or it
            throws `IllegalStateException` at runtime.
- [ ] **FF-3.** Apply the google-services plugin: add `alias(libs.plugins.google.services)` to the
      `plugins` block in `app/build.gradle.kts`. The plugin is already declared in the version
      catalog. **This step hard-fails the build if `google-services.json` is missing**, so it comes
      strictly after `FF-1`.

### 2.3 Backend services

- [ ] **FF-4.** Enable **Authentication → Anonymous**, plus **Email/Password** for the promotion
      step. The account strategy is no longer an open question: **anonymous-first is ratified** (see
      *Decisions to make*, item 2). Writing user data under an anonymous `uid` is therefore safe,
      because claiming the account links a credential with `linkWithCredential`, which
      **preserves the same `uid`** — no document moves and no data merge is required.
      - [ ] Implement the real `FirebaseAuthRepository`: `callbackFlow` over `addAuthStateListener`,
            closed with `awaitClose { removeAuthStateListener(listener) }`. This is what replaces
            today's `flowOf(AuthState.Unauthenticated)` stub, which **completes** after a single
            emission and so violates the contract that `authState` never completes. The listener
            must resolve **three** states — `Unauthenticated` / `Anonymous` / `Authenticated` —
            not two.
      - [ ] `signInAnonymously()` is the default entry point and must run before any Firestore read
            or write, because the security rules and the `users/{uid}` paths are keyed on a
            `currentUser` that does not exist yet. It is a network call and can fail.
      - [ ] `promoteToEmailAccount(email, password)` links the credential to the *current* user,
            keeping the `uid`. Map `ERROR_EMAIL_ALREADY_IN_USE` to its own actionable message.
      - [ ] v1 non-goal: when the address the user types already belongs to another account, do
            **not** merge the two data trees. Tell the user that account already exists and to sign
            in with it instead.
- [ ] **FF-5.** Create **Cloud Firestore** and write **deny-by-default security rules**. Every
      rule must require `request.auth.uid == <the path's userId>`; there is no public read and no
      public write.
- [ ] **FF-6.** Implement the three Firestore repositories against the documented layout. With ROOM
      as the local source of truth their job is the remote projection + sync:
      ```
      users/{uid}
        ├─ profile               -> UserProfile fields
        ├─ goals                 -> NutritionGoals fields
        ├─ onboarding/completed  -> { completed: bool }
        └─ days/{yyyy-MM-dd}     -> { kcal, carbsG, proteinG, fatG, updatedAt }   <- running totals
           └─ entries/{entryId}  -> individual DiaryEntry
      ```
      - [ ] `FirestoreDiaryRepository`: snapshot listeners for the remote merge (`observeDay` /
            `observeWeek`, `recentScans`), `pushAdd` writing the entry subdocument **then**
            `FieldValue.increment` on the day totals, `pushDelete` with a compensating decrement.
      - [ ] `FirestoreGoalsRepository`: snapshot on `users/{uid}/goals`, `set` on save.
      - [ ] `FirestoreProfileRepository`: snapshot on `users/{uid}/profile`, and the onboarding
            flag on its own subdocument.
      - [ ] Every `Flow` must emit from the local cache first, never complete, and never throw —
            a read failure is an empty-state emission.
- [ ] **FF-7.** Expose the repositories from `AppContainer` and make `MainActivity` read the
      onboarding flag from `ProfileRepository.observeOnboardingCompleted()` instead of
      `SharedPreferences`. The start route has to become a collected state, not a `val`
      computed before `setContent`.

### 2.4 Cloud sync (ROOM ↔ Firestore)

- [ ] **DB-7.** `DiarySyncManager`: a sync outbox or dirty-flag on changed entries; when
      connectivity returns, push local writes to Firestore (`pushAdd` + `increment` on the day
      doc, `pushDelete` + decrement), then pull remote changes into ROOM. Idempotent on the entry
      id — the entry id is the natural sync key.
- [ ] **DB-8.** Conflict policy: last-write-wins per entry with server timestamps; never merge
      inside a single entry. A pull must never delete local rows that have not been pushed yet.
      Document the policy before the first sync, and keep the day-totals repair job able to
      rebuild from the entries (see `docs/INTEGRATION.md` §2.8).

---

## Phase 3 — Open Food Facts

- [ ] **OFF-1.** Retrofit + Moshi setup.
  - [ ] Add the mandatory `User-Agent: AppName/Version (contact)` interceptor. The value comes
        from `BuildConfig.OPEN_FOOD_FACTS_USER_AGENT`. **The app is blocked as a bot without
        it** — this is the single most likely first-run failure.
  - [ ] Decide between the KSP-generated Moshi adapters and the reflection-based
        `moshi-kotlin` + `KotlinJsonAdapterFactory`. The DTOs are already annotated
        `@JsonClass(generateAdapter = true)`; generating them needs an annotation processor,
        which the build does not run yet.
- [ ] **OFF-2.** Implement `productByBarcode`. Remember: an unknown barcode returns **HTTP 200**
      with `status = 0` and `product = null`. Map that to `AppError.NotFound` by inspecting the
      body; a `status`-only check will surface a miss as a hit.
- [ ] **OFF-3.** Implement the DTO → domain mapping and the free-text `search` on the **v1**
      `cgi/search.pl` endpoint. v2 has no free-text search at all.
- [ ] **OFF-4.** Enforce the rate limits before this ships.
  - [ ] 15 requests/min for product reads, 10 requests/min for search; breaching returns 503 and
        must map to `AppError.RateLimited`.
  - [ ] **Search-as-you-type is forbidden.** Debounce and require an explicit submit. A 10/min
        budget is roughly three searches; a per-keystroke query would exhaust it in under a
        second and get the app's IP rate-limited.
- [ ] **OFF-5.** Decide what happens for products with no `nutriments` (log them with zeroes, or
      block them) and for regional foods Open Food Facts does not carry. The `LOCAL-*` barcodes
      in `PreviewData` mark the second case.
- [ ] **OFF-6.** Attribution and licensing. Open Food Facts data is ODbL and images are CC-BY-SA;
      attribution to Open Food Facts is required and the share-alike has real consequences for
      redistribution. See `docs/INTEGRATION.md` and confirm the API usage form is filled in
      before shipping.

---

## Phase 4 — Barcode scanning

- [ ] **BC-1.** CameraX preview + ML Kit `barcode-scanning` analyser.
  - [ ] Request `CAMERA` at runtime (the manifest permission alone is not enough from API 23).
  - [ ] Restrict formats to EAN-13 / EAN-8 / UPC-A; a market product is one of those.
  - [ ] Handle the no-camera case gracefully — the feature is declared `required="false"`, so
        devices without a camera must still be able to use manual entry.
- [ ] **BC-2.** Wire the scan result into the `NavResult.pendingProduct` seam: resolve the
      barcode through `FoodCatalogRepository.productByBarcode`, then navigate to `Confirmar`.
- [ ] **BC-3.** Animate the existing `ScanFrame` laser line from the analyser state. It is
      currently a static Canvas and that is a deliberate placeholder.

---

## Phase 5 — State management

- [ ] **ST-1.** One ViewModel per screen, exposing `StateFlow<UiState>`. **Delivery requirement.**
      Today every screen takes its data as plain function parameters with `PreviewData` defaults,
      which is fine for previews and wrong for a real app. The ViewModel infrastructure is already
      in the version catalog.
- [ ] **ST-2.** Replace the in-memory `NavResult` holder with a shared ViewModel or
      `SavedStateHandle`. It is lost on process death and cannot survive the entry that owns it.
      Navigation arguments are the intended end state.
- [ ] **ST-3.** Give `AgregarScreen` a real manual-entry affordance and wire `onManualEntry`,
      which is currently declared but unconnected.
- [ ] **ST-4.** Error and empty states. `AppError` has five cases; each needs a distinct, honest
      message. An empty week and a failed week must not look the same.

---

## Phase 6 — Quality

- [ ] **Q-1.** Unit tests for `Nutrition.scaled` and `Nutrition.plus` — the only arithmetic in
      the app that must be exactly right. Cover rounding at `.5`, zero values, and the
      accumulating day total.
- [ ] **Q-2.** In-memory fakes for all four repository interfaces, so ViewModels can be tested
      with `kotlinx-coroutines-test` + Turbine without a network or an emulator.
- [ ] **Q-3.** ROOM tests with an in-memory database (`androidx.room:room-testing`): DAO queries,
      the `SUM` day totals and the sync outbox. Firestore emulator tests cover only the sync
      projection (increment / decrement arithmetic and the offline path).
- [ ] **Q-4.** A recorded `MockWebServer` test asserting the OFF `User-Agent` header is actually
      sent, and that `status = 0` maps to `NotFound`. These are the two OFF bugs that are silent
      when they regress.
- [ ] **Q-5.** `lint` clean, including the `UnusedResources` and `MissingPermission` checks that
      the new manifest entries touch.
- [ ] **Q-6.** CI on every push: `assembleDebug`, `testDebugUnitTest`, `lint`. Never apply the
      google-services plugin in CI without a real `google-services.json` — the build will fail.

---

## Decisions to make

These are open questions, not defaults. Each one changes the data model or the roadmap above.

1. **Firestore vs Realtime Database.** *Recommend: Firestore, with the per-day aggregate
   documents already described.* RTDB is a better fit for a deep tree that is written whole and
   read whole, but this app writes one entry at a time and reads a week at a time, which is
   Firestore's shape. Firestore also gives real offline persistence, server-side timestamps and
   `FieldValue.increment`. The cost is the extra `days/{date}` document and the invariant that
   the totals must be repairable from the entries. *Update (Phase 2):* this decision carries less
   weight now — ROOM is the local source of truth, so the remote store is a sync target; Firestore
   still wins on `FieldValue.increment`, server timestamps and multi-device merge.
2. **Anonymous auth vs real accounts.** **RESOLVED — anonymous-first is ratified.** A new user gets
   a usable session with no account and no form; they claim it later if they want one. The risk this
   item used to flag — what happens to the data already written under the anonymous `uid` at
   promotion time — **does not exist**: `FirebaseUser.linkWithCredential` **preserves the same
   `uid`**, so the permanent account inherits access to every document the anonymous session wrote.
   No document moves, and there is no merge to design before `FF-4`.
   - *v1 non-goal:* if the address the user types already belongs to a **different** account,
     `linkWithCredential` fails with `ERROR_EMAIL_ALREADY_IN_USE` and reconciling two unrelated data
     trees is genuinely expensive. v1 does not attempt it. The caller surfaces an honest message:
     that account already exists, so sign in with it instead. A partial, silent merge would be worse
     than a clear refusal.
   - *Revisit only if* mandatory accounts are ever required (a feature that needs a recoverable
     identity across devices, for instance). If that happens the merge is a new, real task — it is
     not a rework of what is being built now.
3. **Hilt, or stay on manual DI?** Manual DI is currently cheaper and fully explicit. It starts
   to hurt when there are more than a handful of scoped objects or when test doubles need to be
   swapped per-test. Decide when the first ViewModels land (`ST-1`), not before.
4. **Cache Open Food Facts responses in Firestore?** Worth it: OFF is rate-limited, slow, and
   frequently missing for regional foods. A `catalog/{barcode}` cache would cut repeat scans to
   a single read. Against it: ODbL share-alike obligations on the derived data, plus staleness on
   a database that is constantly corrected by users. *Update:* with ROOM in the picture this is
   mostly answered locally — `CatalogDao` (`DB-2`/`DB-3`) caches scans on device, which captures
   most of the benefit. Syncing that cache to Firestore keeps the share-alike caveat above.
5. **How are regional foods entered at all?** Open Food Facts will not have sancocho, patacón or
   ajiaco. Today the UI implies a "Mercado Fresco API" that does not exist. Either ship a curated
   local catalog, or be explicit in the UI that lookup can miss and manual entry is the normal
   path. Pretending otherwise is the kind of thing that erodes trust the first time it fails.
