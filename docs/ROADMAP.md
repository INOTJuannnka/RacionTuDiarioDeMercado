# Roadmap

Lista ordenada para llevar **Ración — Tu Diario de Mercado** de un prototipo de UI a una app que
funciona. Cada tarea lleva un ID para que los comentarios `TODO()` en el código puedan citarla.

Prefijos de ID:

| Prefijo | Área                                                            |
|---------|-----------------------------------------------------------------|
| `FF-N`  | Firebase (Auth, Firestore, cableado de DI)                      |
| `OFF-N` | Open Food Facts (Retrofit, Moshi, mapping, límites de tasa)      |
| `BC-N`  | Escaneo de códigos de barras (CameraX + ML Kit)                 |
| `ST-N`  | Gestión de estado (ViewModels, resultados de navegación)        |
| `DB-N`  | Persistencia local (ROOM, source of truth offline-first)        |
| `Q-N`   | Calidad (tests, lint, CI)                                       |

El reparto de estas tareas entre personas, con dependencias entre lanes y el orden de arranque, vive
en [`SPRINT-1.md`](./SPRINT-1.md). Este documento es el **qué**; aquel es el **quién y cuándo**.

Todo hecho externo citado acá está referenciado en
[`INTEGRATION.md`](./INTEGRATION.md). Leerlo antes de volver a derivar cualquier cosa sobre la API de
OFF o los límites de Firestore.

---

## Fase 1 — Fundamentos (identidad del paquete, capas, costuras) ✅ HECHO

- [x] Mover la app a su identidad real: `com.example.myapplication` → `com.racion.diariomercado`
      (tanto **namespace** como **applicationId**, todavía barato porque la app no está en Play).
- [x] Mover el paquete de UI `com.nutriapp.*` → `com.racion.diariomercado.ui.*`.
- [x] Renombrar el tema XML sobrante `Theme.MyApplication` → `Theme.Racion`.
- [x] Extraer los modelos de dominio fuera de los archivos de pantalla hacia
      `domain/model/`: `Nutrition`, `FoodProduct`, `DiaryEntry` + `MealSlot`, `DailySummary`,
      `WeeklyReport` + `DayTotal` + `MacroSplit`, `NutritionGoals` + `DayOfWeek`, `UserProfile` +
      `SportFocus`.
- [x] Eliminar los campos `Color?` que se habían filtrado a los modelos. `iconBg` era una
      preocupación de UI; el dominio no sabe lo que es un color de fondo. `emoji` se quedó — es
      contenido.
- [x] Agregar la taxonomía de fallos `core/AppResult` + `AppError` para que ningún repositorio le
      lance un `IOException` crudo a una pantalla.
- [x] Declarar las cuatro interfaces de repositorio (`FoodCatalogRepository`, `DiaryRepository`,
      `GoalsRepository`, `ProfileRepository`) con sus contratos documentados.
- [x] Levantar la capa de datos como stubs explícitos y marcados con TODO: DTOs de Moshi, el
      servicio Retrofit, el repositorio de catálogo de OFF y los tres repositorios de Firestore.
- [x] Cablear DI manual (`di/AppContainer`) y `RacionApplication`.
- [x] Centralizar todos los valores fake en `ui/preview/PreviewData` y reconectar las ocho pantallas
      a los modelos de dominio, para que la app corra de punta a punta sin backend.
- [x] Registrar Retrofit/OkHttp/Moshi/Coil/Firebase/ML Kit en `libs.versions.toml` y
      `app/build.gradle.kts` (sólo librerías — el plugin de google-services está deliberadamente NO
      aplicado, ver `FF-3`).
- [x] Agregar los permisos `INTERNET` / `CAMERA` y marcar la función de cámara como opcional para que
      la app igual instale en dispositivos sin cámara.
- [x] Requisito de entrega — **Navigation Component**: ya vivo vía `navigation-compose`
      (`NavHost` + `rememberNavController`, 8 rutas en `AppNavigation`). No hay nada que agregar.
- [x] Requisito de entrega — **infraestructura de ViewModel**: `androidx-lifecycle-viewmodel-compose`
      está en el catálogo; la implementación es `ST-1`, ahora obligatoria en vez de opcional.

---

## Fase 2 — Base de datos local (ROOM) + sync con Firebase

### 2.1 Base de datos local — ROOM (source of truth offline-first)

El diario, el perfil y las metas viven en ROOM. Toda escritura va primero a la base local; Firestore
es la proyección de sync/backup, nunca el primer destino. Esto satisface el requisito de entrega de
una base local y le queda bien a una app de mercado con señal intermitente.

- [x] **DB-1.** Agregar `androidx.room:room-runtime`, `room-ktx` y `room-compiler` (por KSP) a
      `libs.versions.toml`; habilitar el plugin KSP en `app/build.gradle.kts` (esto también
      destraba los adapters de Moshi generados de `OFF-1`).
      *Hecho (J3, 3 oct 2026).* Room `2.8.5`, KSP `2.3.12`. Tres desviaciones forzadas por fallos
      observados: KSP se elige contra **AGP 9.4.0** (built-in Kotlin prohíbe la línea 2.2.x) y la
      línea 2.3.x dropeó el sufijo `-2.0.x`; Room lleva `version.ref` explícito porque el BOM de
      Compose 2026.02.01 no contiene `androidx.room`; y `RoomSchemaArgProvider` usa `@InputFiles`,
      no el `@InputDirectory` documentado, que falla en Gradle 9.6.
- [x] **DB-2.** Entities: `DiaryEntryEntity`, `FoodProductEntity` (caché de catálogo para `LOCAL-*` y
      productos escaneados), `NutritionGoalsEntity`, `UserProfileEntity`. Los day keys son strings
      `yyyy-MM-dd` para que una semana sea una consulta de rango léxica, igual que el layout de
      Firestore.
      *Hecho (J4).* `Nutrition` se **aplana** en 7 columnas: embebido las escondería tras
      `nutrition.kcal`, donde `SUM` no llega. Sin columna `onboardingCompleted`: el consentimiento
      debe poder escribirse independiente de un write de perfil. El cacheo de `LOCAL-*` se reportan
      como fixtures de `PreviewData`, no como datos reales.
- [x] **DB-3.** DAOs: `DiaryDao` (entradas por día, por semana, upsert, delete), `GoalsDao`,
      `ProfileDao`, `CatalogDao`. Cada read expone un `Flow` — un stream por pantalla.
      *Hecho (J4).* La semana es un rango **semiabierto** `>= from AND < to`: el límite superior
      exclusivo es el lunes siguiente, que quien camina de semana en semana ya tiene. Un
      `BETWEEN` inclusivo obliga a sumar 6 días en el call site y se infla a 8 sin que nadie se entere.
- [x] **DB-4.** Los totales del día se calculan con `SUM` en la query del DAO — nada de
      write-contention de documento local. Los totales remotos en `days/{date}` siguen siendo la
      proyección server-side del sync.
      *Hecho (J4).* `SUM` + `COALESCE(...,0)` sobre los 7 campos, con proyección dedicada
      `DayNutritionTotals` (no `Nutrition`). El `COALESCE` no es decorativo: `SUM` sobre cero filas
      devuelve `NULL`, y eso fusiona "no comió nada" (0) con "la query está rota" (null) para quien
      tiene que distinguirlos.
- [x] **DB-5.** Índices: `@Index` en `DiaryEntry.dayKey` y un `@Index` único en
      `CatalogEntry.barcode`.
      *Hecho (J4).* `CatalogEntry` no existe: el tipo real es `FoodProductEntity`, así que el índice
      único va en `FoodProductEntity.barcode` (redundante con la PK, pero el schema exportado es el
      artefacto contra el que se valida una migración). Foreign key del diario al catálogo con
      **`onDelete = RESTRICT`**: `CASCADE` desde una caché podable borraría en silencio el diario
      del usuario con un `DELETE` de housekeeping rutinario. Hay dos tests que lo fijan.
- [x] **DB-6.** `RoomDatabase` versionada con estrategia de migración desde el primer día (una ruta de
      migración vacía es aceptable pre-release, pero la fontanería existe).
      *Hecho (J4).* `version = 1`, `exportSchema = true`, **sin**
      `fallbackToDestructiveMigration()`. Schema v1 exportado a
      `app/schemas/.../RationDatabase/1.json` (`identityHash cbbd157752eb056ac4fdeabfa180f692`).
      Pendiente de commitear ese JSON: sin él no hay línea base contra la que diffear la v2.

### 2.2 Proyecto y configuración

- [x] **FF-1.** Crear el proyecto de Firebase y agregar la app Android con
      `com.racion.diariomercado` como package name. Descargar `google-services.json` y dejarlo en
      `app/google-services.json`. Ya está en `.gitignore` — nunca commitearlo.
      *Verificado: `project_id = racion-tu-diario-de-mercado`, `package_name` correcto, cubierto por
      `.gitignore` línea 18.*
- [ ] **FF-2.** Inicializar Firebase en `RacionApplication.onCreate()`:
      `FirebaseApp.initializeApp(this)`, luego el sign-in anónimo, y después crear la instancia de
      Firestore y configurarla **una sola vez** vía `setFirestoreSettings()`.
      - [ ] **No** llamar al `setPersistenceEnabled()` deprecado. La caché offline ya está activa por
            defecto mediante `PersistentCacheSettings`.
      - [ ] `setFirestoreSettings()` debe ejecutarse antes de cualquier otro call sobre esa instancia
            o tira `IllegalStateException` en runtime.
- [ ] **FF-3.** Aplicar el plugin de google-services: agregar `alias(libs.plugins.google.services)`
      al bloque `plugins` de `app/build.gradle.kts`. El plugin ya está declarado en el catálogo de
      versiones. **Desbloqueado**: `FF-1` ya está, así que `google-services.json` existe y el build
      no va a fallar por su ausencia.

### 2.3 Servicios de backend

- [ ] **FF-4.** Habilitar **Authentication → Anonymous**, más **Email/Password** para el paso de
      promoción. La estrategia de cuenta ya no es una pregunta abierta: **anónimo primero está
      ratificado** (ver *Decisiones pendientes*, punto 2). Escribir datos de usuario bajo un `uid`
      anónimo es seguro, porque reclamar la cuenta vincula una credencial con `linkWithCredential`,
      que **preserva el mismo `uid`** — no se mueve ningún documento y no hace falta ningún merge de
      datos.
      - [x] Implementar el `FirebaseAuthRepository` real: `callbackFlow` sobre
            `addAuthStateListener`, cerrado con
            `awaitClose { removeAuthStateListener(listener) }`. Esto reemplaza el stub
            `flowOf(AuthState.Unauthenticated)`, que **completaba** tras una sola emisión y por
            tanto violaba el contrato de que `authState` nunca completa. El listener resuelve
            **tres** estados — `Unauthenticated` / `Anonymous` / `Authenticated`.
            *(Hecho. PR #2.)*
      - [x] `signInAnonymously()` antes de cualquier lectura o escritura de Firestore. Ahora
            corre en `RacionApplication.bootstrapAnonymousSession()`, y el orden contra
            `initializeFirestoreIfReady` está documentado en `docs/INTEGRATION.md` §2.4.
            *(Hecho.)*
      - [x] `promoteToEmailAccount(email, password)` vincula la credencial al usuario *actual*,
            conservando el `uid`. `ERROR_EMAIL_ALREADY_IN_USE` mapeado a su propio mensaje.
            *(Hecho.)*
      - [x] **No-goal de v1:** cuando la dirección escrita ya pertenece a otra cuenta, **no**
            fusionar los dos árboles de datos. Se le dice que esa cuenta ya existe.
            *(Hecho.)*
      - [x] **Trampa de producto:** `signOut()` sobre una sesión **anónima** destruye el `uid` y
            con él todos sus datos en Firestore. La UI confirma antes de cerrar sesión en estado
            `Anonymous`. *(Hecho.)*
      - [ ] **Bloqueante de release:** habilitar los providers en Firebase Console. Nunca se
            verificó en runtime; el login con Google no se probó contra el backend real.
            *(Ver J6 en `SPRINT-1.md`.)*
- [ ] **FF-5.** Crear **Cloud Firestore** y escribir **reglas de seguridad deny-by-default**. Cada
      regla debe exigir `request.auth.uid == <el userId del path>`; no hay lectura pública ni
      escritura pública.
      - [x] Proyecto de Firebase y app conectada: `RacionApplication` resuelve la instancia con
            `initializeFirestoreIfReady`.
      - [ ] **Reglas de seguridad.** El repo **no tiene `firestore.rules` ni `firebase.json`**.
            La especificación está en `docs/INTEGRATION.md` §2.2 pero **no está aplicada**: la app
            ya escribe a Firestore sin gobierno de reglas. Bloqueante de producción.
- [ ] **FF-6.** Implementar los tres repositorios de Firestore contra el layout documentado. Con
      ROOM como source of truth local, su trabajo es la proyección remota + el sync:
      ```
      users/{uid}
        ├─ profile               -> UserProfile fields
        ├─ goals                 -> NutritionGoals fields
        ├─ onboarding/completed  -> { completed: bool }
        └─ days/{yyyy-MM-dd}     -> { kcal, carbsG, proteinG, fatG, updatedAt }   <- running totals
           └─ entries/{entryId}  -> individual DiaryEntry
      ```
      - [ ] `FirestoreDiaryRepository`: snapshot listeners para el merge remoto (`observeDay` /
            `observeWeek`, `recentScans`), `pushAdd` escribiendo primero el subdocumento de la entrada
            y **después** `FieldValue.increment` sobre los totales del día, `pushDelete` con un
            decrement compensatorio.
            **Sin empezar:** los tres métodos de escritura (`addEntry`, `deleteEntry`,
            `recentScans`) tiran `NotImplementedError`.
      - [x] `FirestoreGoalsRepository`: snapshot sobre `users/{uid}/goals`, `set` al guardar.
            *(Hecho. PR #7.)*
      - [x] `FirestoreProfileRepository`: snapshot sobre `users/{uid}/profile`, y el flag de
            onboarding en su propio subdocumento.
            *(Hecho. PR #7.)*
      - [ ] Todo `Flow` debe emitir primero desde la caché local, nunca completar, y nunca tirar —
            un fallo de lectura es una emisión de estado vacío.
            **Excepción documentada:** los observadores sin sesión emiten su fallback y
            **completan**, porque sin sesión no hay nada que esperar. Documentado en el KDoc de
            `FirestoreGoalsRepository` y `FirestoreProfileRepository`.
- [x] **FF-7.** Exponer los repositorios desde `AppContainer`. *(Hecho. PR #8.)*
      - [ ] …y hacer que `MainActivity` lea el flag de onboarding desde
            `ProfileRepository.observeOnboardingCompleted()` en vez de `SharedPreferences`. La
            interfaz **ya expone** `observeOnboardingCompleted()` y `completeOnboarding()`, pero
            `MainActivity` sigue con `getSharedPreferences("ration_prefs")` y tiene el
            `TODO(FF-5)` — lo que hace que ningún `init` de Firestore sea alcanzable hoy.

### 2.4 Sync en la nube (ROOM ↔ Firestore)

- [ ] **DB-7.** `DiarySyncManager`: una bandeja de salida de sync o un dirty-flag sobre las entradas
      modificadas; cuando vuelve la conectividad, subir las escrituras locales a Firestore
      (`pushAdd` + `increment` sobre el doc del día, `pushDelete` + decrement), después traer los
      cambios remotos a ROOM. Idempotente sobre el id de la entrada — el id de entrada es la clave
      natural de sync.
      *Hecho, salvo el pull.* La bandeja de salida, la migración y el drenaje están implementados:
      tabla `sync_outbox` (`entryId` como PK, que hace el coalescing), `MIGRATION_1_2` testeada
      contra la v1 real con `MigrationTestHelper`, y `DiarySyncManager.drain(limit)` con borrado
      sólo tras `Success` y corte en el primer fallo. El lado remoto es un seam: `SyncTransport` es
      una interfaz y la implementación Firestore (`pushAdd`/`increment`, `pushDelete`/`decrement`)
      **no** está escrita — sin ella el `DiarySyncManager` no tiene a quién empujar. Tampoco hay
      scheduler: nadie llama a `drain()` todavía.
- [ ] **DB-8.** Política de conflictos: last-write-wins por entrada con server timestamps; nunca
      fusionar dentro de una misma entrada. Un pull nunca debe borrar filas locales que todavía no
      se subiéron. Documentar la política antes del primer sync, y mantener el job de reparación de
      totales del día capaz de reconstruirse desde las entradas (ver `docs/INTEGRATION.md` §2.8).

---

## Fase 3 — Open Food Facts

- [ ] **OFF-1.** Setup de Retrofit + Moshi.
  - [ ] Agregar el interceptor obligatorio `User-Agent: AppName/Version (contact)`. El valor viene
        de `BuildConfig.OPEN_FOOD_FACTS_USER_AGENT`. **Sin él la app queda bloqueada como bot** —
        es el failure más probable en la primera corrida real.
        *Pendiente: `OPEN_FOOD_FACTS_USER_AGENT` existe en `build.gradle.kts` pero con el placeholder
        `contact@example.com`. Ese placeholder no llega a producción; debería leerse de
        `local.properties` (ya está en `.gitignore`).*
  - [ ] **Decidido para el lane de OFF: adapters por reflexión**, `moshi-kotlin` +
        `KotlinJsonAdapterFactory`. Ya es dependencia y ya deserializa los DTOs hoy. KSP queda
        reservado para ROOM, que lo necesita de verdad. Los DTOs ya están anotados
        `@JsonClass(generateAdapter = true)`, pero generarlos requiere un annotation processor que
        el build todavía no corre.
- [ ] **OFF-2.** Implementar `productByBarcode`. Recordar: un barcode desconocido devuelve **HTTP
      200** con `status = 0` y `product = null`. Mapear eso a `AppError.NotFound` inspeccionando el
      body; un chequeo que mire sólo `status` va a reportar un fallo como un acierto.
- [ ] **OFF-3.** Implementar el mapping DTO → dominio y el `search` de texto libre sobre el endpoint
      **v1** `cgi/search.pl`. La v2 no tiene búsqueda de texto libre en absoluto.
- [ ] **OFF-4.** Hacer cumplir los límites de tasa antes de que esto salga.
  - [ ] 15 requests/min para lectura de productos, 10 requests/min para búsqueda; pasarse devuelve
        503 y hay que mapearlo a `AppError.RateLimited`.
  - [ ] **Search-as-you-type está prohibido.** Debounce y submit explícito. Un presupuesto de 10/min
        son unas tres búsquedas; una query por tecla lo agotaría en menos de un segundo y dejaría la
        IP de la app rate-limited.
- [ ] **OFF-5.** Decidir qué pasa con productos sin `nutriments` (registrarlos con ceros, o
      bloquearlos) y con los alimentos regionales que Open Food Facts no tiene. Los barcodes
      `LOCAL-*` en `PreviewData` marcan el segundo caso.
- [ ] **OFF-6.** Atribución y licencias. Los datos de Open Food Facts son ODbL y las imágenes son
      CC-BY-SA; la atribución a Open Food Facts es obligatoria y el share-alike tiene consecuencias
      reales para la redistribución. Ver `docs/INTEGRATION.md` y confirmar que el formulario de uso
      de la API está enviado antes de publicar.

---

## Fase 4 — Escaneo de códigos de barras

- [ ] **BC-1.** Preview de CameraX + analizador `barcode-scanning` de ML Kit.
  - [ ] Pedir `CAMERA` en runtime (sólo el permiso del manifest no alcanza desde API 23).
  - [ ] Restringir formatos a EAN-13 / EAN-8 / UPC-A; un producto de mercado es uno de esos.
  - [ ] Manejar el caso sin cámara con elegancia — la función está declarada `required="false"`, así
        que los dispositivos sin cámara igual tienen que poder usar la entrada manual.
- [ ] **BC-2.** Conectar el resultado del escaneo a la costura `NavResult.pendingProduct`: resolver
      el barcode vía `FoodCatalogRepository.productByBarcode`, después navegar a `Confirmar`.
- [ ] **BC-3.** Animar la línea láser del `ScanFrame` existente desde el estado del analizador.
      Hoy es un Canvas estático y ese es un placeholder deliberado.

---

## Fase 5 — Gestión de estado

- [ ] **ST-1.** Un ViewModel por pantalla, exponiendo `StateFlow<UiState>`. **Requisito de entrega.**
      Hoy cada pantalla toma sus datos como parámetros de función con defaults de `PreviewData`, lo
      cual está bien para previews y está mal para una app real. La infraestructura de ViewModel ya
      está en el catálogo de versiones.
- [ ] **ST-2.** Reemplazar el holder en memoria de `NavResult` con un ViewModel compartido o un
      `SavedStateHandle`. Se pierde con la muerte del proceso y no sobrevive la entrada que lo posee.
      Los argumentos de navegación son el estado final previsto.
- [ ] **ST-3.** Darle a `AgregarScreen` una affordance real de entrada manual y conectar
      `onManualEntry`, que hoy está declarado pero desconectado.
- [ ] **ST-4.** Estados de error y de vacío. `AppError` tiene cinco casos; cada uno necesita un
      mensaje distinto y honesto. Una semana vacía y una semana que falló al cargar no pueden verse
      igual.

---

## Fase 6 — Calidad

- [x] **Q-1.** Tests unitarios para `Nutrition.scaled` y `Nutrition.plus` — la única aritmética de
      la app que tiene que estar exactamente bien. Cubriendo redondeo en `.5`, valores cero y el
      total del día acumulativo.
      *Verificado: 14 tests en `NutritionTest` cubren las dos operaciones, el redondeo half-up en
      `.5` (no truncar), la identidad contra el valor por defecto, la conmutatividad, la precisión
      fraccionaria de macros, y el fold de los totales de `PreviewData`.*
- [ ] **Q-2.** Fakes en memoria para las cuatro interfaces de repositorio, para que los ViewModels se
      puedan testear con `kotlinx-coroutines-test` + Turbine sin red ni emulator.
      *Parcial: `AuthRepository` ya tiene un fake en `LoginViewModelTest`.*
- [ ] **Q-3.** Tests de ROOM con una base en memoria (`androidx.room:room-testing`): queries de DAO,
      los totales del día con `SUM` y la bandeja de salida de sync. Los tests con el emulator de
      Firestore cubren sólo la proyección de sync (aritmética increment / decrement y el camino
      offline).
      *Parcial (J4, 3 oct 2026): 58 tests nuevos de DAO con base en memoria, 0 salteados.*
      Cubierto: queries de DAO (día, semana, upsert, delete), totales con `SUM` + `COALESCE`,
      converters (JSON de categorías con comas, `activeDays`), defaults de entities, y las dos
      reglas de integridad del catálogo (`RESTRICT` y FK). Falta la bandeja de salida de sync, que
      depende de `DB-7`.
- [ ] **Q-4.** Un test grabado con `MockWebServer` que verifique que el header `User-Agent` de OFF
      sale de verdad, y que `status = 0` mapea a `NotFound`. Son los dos bugs de OFF que fallan en
      silencio cuando regresan.
- [ ] **Q-5.** `lint` limpio, incluidos los chequeos `UnusedResources` y `MissingPermission` que
      tocan las nuevas entradas del manifest.
- [ ] **Q-6.** CI en cada push: `assembleDebug`, `testDebugUnitTest`, `lint`. Nunca aplicar el plugin
      de google-services en CI sin un `google-services.json` real — el build va a fallar.

**Línea base verificada** sobre `main` @ `6edc7eb`: `assembleDebug` + `testDebugUnitTest` en verde,
**33 tests, 0 fallos, 0 errores, 0 skipped**. Para forzar una corrida real de los tests hay que pasar
`--rerun`; sin eso Gradle reporta `UP-TO-DATE` y muestra `BUILD SUCCESSFUL` sin ejecutar un solo test.

---

## Decisiones pendientes

Estas son preguntas abiertas, no defaults. Cada una cambia el modelo de datos o el roadmap de arriba.

1. **Firestore vs Realtime Database.** *Recomendación: Firestore, con los documentos agregados por
   día ya descritos.* RTDB encaja mejor con un árbol profundo que se escribe entero y se lee entero,
   pero esta app escribe una entrada a la vez y lee una semana a la vez, que es la forma de
   Firestore. Firestore además da persistencia offline real, server-side timestamps y
   `FieldValue.increment`. El costo es el documento extra `days/{date}` y el invariante de que los
   totales tienen que poder repararse desde las entradas. *Actualización (Fase 2):* esta decisión
   pesa menos ahora — ROOM es el source of truth local, así que el store remoto es un destino de
   sync; Firestore sigue ganando por `FieldValue.increment`, server timestamps y merge
   multi-dispositivo.
2. **Auth anónimo vs cuentas reales.** **RESUELTO — anónimo primero está ratificado.** Un usuario
   nuevo recibe una sesión usable sin cuenta y sin formulario; después la reclama si quiere una. El
   riesgo que este punto señalaba antes — qué pasa con los datos ya escritos bajo el `uid` anónimo en
   el momento de la promoción — **no existe**: `FirebaseUser.linkWithCredential` **preserva el mismo
   `uid`**, así que la cuenta permanente hereda el acceso a todos los documentos que escribió la
   sesión anónima. No se mueve ningún documento, y no hay ningún merge que diseñar antes de `FF-4`.
   - *No-goal de v1:* si la dirección que el usuario escribe ya pertenece a una cuenta **distinta**,
     `linkWithCredential` falla con `ERROR_EMAIL_ALREADY_IN_USE` y reconciliar dos árboles de datos
     sin relación es genuinamente caro. v1 no lo intenta. El llamador muestra un mensaje honesto:
     esa cuenta ya existe, así que inicie sesión con ella. Un merge parcial y silencioso sería peor
     que una negativa clara.
   - *Revisar sólo si* alguna vez se requieren cuentas obligatorias (una función que necesite una
     identidad recuperable entre dispositivos, por ejemplo). Si eso pasa, el merge es una tarea nueva
     y real — no es un retrabajo de lo que se está construyendo ahora.
3. **Hilt, o seguimos con DI manual?** El DI manual hoy es más barato y totalmente explícito. Empieza
   a doler cuando hay más de un puñado de objetos con scope, o cuando los test doubles hay que
   cambiarlos por test. Decidirlo cuando aterricen los primeros ViewModels (`ST-1`), no antes.
4. **¿Cachear las respuestas de Open Food Facts en Firestore?** Vale la pena: OFF tiene límites de
   tasa, es lento, y le faltan alimentos regionales seguido. Una caché `catalog/{barcode}` dejaría
   los escaneos repetidos en una sola lectura. En contra: obligaciones de share-alike ODbL sobre los
   datos derivados, más obsolescencia sobre una base que los usuarios corrigen constantemente.
   *Actualización:* con ROOM en el panorama esto queda resuelto en su mayor parte localmente —
   `CatalogDao` (`DB-2`/`DB-3`) cachea los escaneos en el dispositivo, que captura casi todo el
   beneficio. Sincronizar esa caché a Firestore mantiene la salvedad de share-alike de arriba.
5. **¿Cómo se capturan los alimentos regionales?** Open Food Facts no va a tener sancocho, patacón ni
   ajiaco. Hoy la UI insinúa una "Mercado Fresco API" que no existe. O se publica un catálogo local
   curado, o se es explícito en la UI que la búsqueda puede fallar y que la entrada manual es el
   camino normal. Fingir lo contrario es justo el tipo de cosa que erosiona la confianza la primera
   vez que falla.
