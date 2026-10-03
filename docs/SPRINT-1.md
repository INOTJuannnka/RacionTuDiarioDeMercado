# Sprint 1 — Login, Open Food Facts y persistencia local

**Base:** `main` @ `74a1314`
**Ramas:** `Julian`, `Brayan`, `Daniel` — las tres cortadas desde `main`, sin commits entre sí todavía.
**Documento de referencia:** [`ROADMAP.md`](./ROADMAP.md) (IDs `FF-*`, `OFF-*`, `DB-*`, `ST-*`, `Q-*`) y
[`INTEGRATION.md`](./INTEGRATION.md) (hechos verificables sobre la API de Open Food Facts y Firestore).

Cada tarea de este documento lleva el ID del roadmap que le corresponde. Si algo no está explícito acá,
está en el roadmap; si no está en ninguno de los dos, se pregunta antes de inventar.

---

## 1. Regla de coordinación (leer antes de tocar nada)

### 1.1 Archivos con dueño único

Estos tres archivos **no se tocan en las ramas de Brayan ni de Daniel**:

| Archivo | Dueño |
|---|---|
| `app/build.gradle.kts` | Julian |
| `gradle/libs.versions.toml` | Julian |
| `app/src/main/java/com/racion/diariomercado/di/AppContainer.kt` | Julian |

Si necesitás una dependencia nueva o una línea en `AppContainer`, **no la agregues**: avisá por el
canal del equipo y Julian la aterriza en `main`. Los tres lanes necesitan estos archivos al mismo
tiempo; si los tres los editan, el merge es un conflicto de tres versiones sobre las mismas diez
líneas y se pierde trabajo de las tres partes.

**Ejemplo concreto, ya detectado:** la tarea `B6` necesita `com.squareup.okhttp3:mockwebserver`, que
**no está** en `libs.versions.toml`. Pedilo, no lo agregues.

### 1.2 Ownership por directorio (disjuntos, no se pisan)

| Ruta | Dueño |
|---|---|
| `domain/repository/AuthRepository.kt`, `data/firebase/FirebaseAuthRepository.kt`, `ui/screens/auth/**` | Daniel |
| `data/openfood/**`, tests de Open Food Facts | Brayan |
| `domain/model/**`, `data/firebase/Firestore{Diary,Goals,Profile}Repository.kt`, `data/local/**` | Julian |

### 1.3 Definition of Done (aplica a todas las PRs)

Una PR entra a `main` sólo con las dos condiciones cumplidas:

```
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

En verde. Línea base verificada sobre `main` @ `74a1314`: **33 tests, 0 fallos, 0 errores, 0 skipped.**

| Suite | Tests | Dueño |
|---|---|---|
| `domain.model.NutritionTest` | 14 | Julian |
| `ui.auth.LoginViewModelTest` | 11 | Daniel |
| `data.openfood.MoshiAdapterTest` | 7 | Brayan |
| `ExampleUnitTest` | 1 | — |

El número sube, nunca baja. Si tu PR lo baja, no entra.

> **Ojo con `UP-TO-DATE`.** Gradle no re-ejecuta una tarea cuyas entradas no cambiaron: vas a ver
> `BUILD SUCCESSFUL` sin que corra un solo test. Para forzar la corrida real: `--rerun`.
> Un `BUILD SUCCESSFUL` sobre tareas cacheadas **no** es evidencia de que tus tests pasan.
>
> Si aparece `JAVA_HOME is not set and no 'java' command could be found`, no es un problema del
> proyecto: exportá `JAVA_HOME` apuntando al JDK de Android Studio
> (`C:\Program Files\Android\Android Studio\jbr`) antes de invocar el wrapper.

Commits conventional. Nada de atribución a IA.

---

## 2. Dependencias entre lanes (leé esto antes de arrancar)

```
J1 (google-services.json)  ──bloquea──>  D3 (plugin google-services)
J6 (providers en consola)  ──bloquea──>  D2, D4 (probar un sign-in real)
J3 (deps Room + KSP)      ──bloquea──>  J4 (entities y DAOs)
B1 (interceptor UA)       ──bloquea──>  B3 (llamadas reales a la API)
```

J2 **ya no bloquea a nadie**: la decisión de estrategia de cuenta está ratificada (ver J2).

Brayan **no está bloqueado por nadie** y puede empezar de inmediato. Empezá por ahí.

---

## 3. Daniel — Login

### D1 · FF-2 — Inicialización de Firebase
En `RacionApplication.onCreate()`: `FirebaseApp.initializeApp(this)` y luego la instancia de
Firestore configurada **una sola vez** vía `setFirestoreSettings()`.

- `setFirestoreSettings()` **debe** ejecutarse antes de cualquier otro call sobre esa instancia; si no,
  tira `IllegalStateException` en runtime.
- **No** llames a `setPersistenceEnabled()`. Está deprecado: la caché offline ya viene activada por
  defecto vía `PersistentCacheSettings`.

### D2 · FF-4 — `FirebaseAuthRepository` real (tarea central)
Hoy el stub emite con `flowOf(...)`, que **completa**. Eso viola el contrato a propósito y hay que
reemplazarlo: `callbackFlow` sobre `addAuthStateListener`, cerrado con `awaitClose`.

El contrato de `AuthRepository` es que `authState` **nunca completa y nunca tira**. Un read fallido
es una emisión de estado vacío, no una excepción.

> **Ya no está bloqueado por J2:** la decisión está ratificada y es *anonymous-first*. El alcance es:
> - `signInAnonymously()` como punto de entrada por defecto. Tiene que correr **antes** de
>   cualquier lectura o escritura de Firestore, porque las reglas de seguridad y los paths
>   `users/{uid}` están indexados por un `currentUser` que todavía no existe. Es una llamada de
>   red: puede fallar y hay que mapear el fallo, no tragárselo.
> - `promoteToEmailAccount(email, password)` vía `linkWithCredential`. **Conserva el mismo `uid`**,
>   así que ningún documento se mueve y no hay migración de datos. `ERROR_EMAIL_ALREADY_IN_USE`
>   necesita su propio mensaje accionable, no un error genérico.
> - El `callbackFlow` real resolviendo **tres** estados: `Unauthenticated` / `Anonymous` /
>   `Authenticated`. Mapear todo `currentUser != null` a `Authenticated` borra el estado anónimo y
>   deja indistinguible una sesión descartable de una permanente.
> - `signUp` queda **supersedido** por la promoción. No lo borres —`LoginViewModel` y
>   `RegisterScreen` lo siguen llamando— pero tampoco lo extiendas: queda como andamiaje hasta que
>   sus callers migren. Borrarlo es un follow-up conocido, no parte de esta tarea.
>
> **No-objetivo de v1:** si el correo que tipea el usuario ya pertenece a otra cuenta, **no** se
> mergean los dos árboles de datos. Se le dice que esa cuenta ya existe y que inicie sesión con
> ella. Mergear a medias en silencio sería peor que una negativa clara.
>
> **Cuidado con `signOut()`.** Sobre una sesión anónima destruye el `uid` y todos sus datos de
> Firestore, de forma **irreversible** y del lado del servidor. No hay undo ni backup posible. La
> UI tiene que confirmar antes de llamarlo, y la confirmación tiene que decir qué se pierde —un
> "¿Cerrar sesión?" genérico hace pasar una acción destructiva por reversible.

### D3 · FF-3 — Plugin de google-services
Descomentar `alias(libs.plugins.google.services)` en el `plugins` block.

> **Bloqueado por J1.** Aplicar el plugin sin un `google-services.json` real en `app/` rompe el build
> de forma inmediata: `processDebugGoogleServices` falla con *"File google-services.json is missing"*.

### D4 — Tests del repositorio de auth
Cubrir las transiciones de `authState`: anónimo → logueado → deslogueado.

- contra un **seam falso**, no contra Firebase real — es test unitario JVM, no hay emulator.
- `kotlinx-coroutines-test` **no** instala `Dispatchers.Main` solo. `viewModelScope` usa
  `Dispatchers.Main.immediate`; sin `Dispatchers.setMain` la corrutina queda encolada en un dispatcher
  que nadie bombea. El síntoma es un `signInCalls == 0` silencioso. Reusa el `MainDispatcherRule` que ya
  existe en `app/src/test/java/com/racion/diariomercado/ui/auth/LoginViewModelTest.kt`.
- `UnconfinedTestDispatcher` es lo correcto acá: no hay punto de suspensión real, corre en el `launch`.

### D5 · ST-4 — Estados de error y de carga en la UI
`AppError` tiene cinco casos y cada uno necesita un mensaje **distinto y honesto**. Una semana vacía
y una semana que falló al cargar no pueden verse igual.

Además: estado de carga visible y submit deshabilitado mientras la validación corre.

Y el branch de **tres** estados en la pantalla de perfil: `Unauthenticated` ofrece iniciar sesión,
`Authenticated` muestra la identidad con un cierre de sesión normal (que no destruye nada), y
`Anonymous` es el único que tiene que ofrecer **"reclamá tu cuenta"** — con la advertencia de que
cerrar sesión ahí borra los datos. Es la única pantalla que hace este branch; el resto delega en
"hay sesión o no".

### D6 · FF-7 — Gatear la ruta inicial por autenticación
`startRoute` tiene que pasar a ser **estado recolectado**, no un `val` calculado antes de `setContent`.
Construí el `NavHost` sobre la primera emisión de `authState`.

> Alcance acotado a auth. El flag de onboarding que vive en `ProfileRepository` es de Julian.

---

## 4. Brayan — Open Food Facts

### B1 · OFF-1 — Interceptor `User-Agent` (obligatorio)
Open Food Facts bloquea requests con User-Agent genérico o ausente. **Es el failure más probable en la
primera corrida.**

`OPEN_FOOD_FACTS_USER_AGENT` ya existe como `buildConfigField`, pero con el placeholder
`contact@example.com`. Ese placeholder **no llega a producción**: es la dirección que Open Food Facts
usa para identificar la app y desbloquearla si empieza a rechazar. Un IP ban sin casilla de correo es
un problema que no vas a poder rastrear.

Solución: leelo de `local.properties` (ya está en `.gitignore`, línea 15), con el placeholder como
fallback.

### B2 · Decisión de tooling: NO actives KSP
`moshi-kotlin` (adapters por reflexión) **ya es dependencia y ya deserializa los DTOs hoy**. KSP se
reserva para Room, que lo necesita de verdad. Activar el annotation processor para esto es agregar
una dependencia de build y una superficie de fallo sin ganar nada.

> Codegen está declarado en el catálogo (`squareup-moshi-kotlin-codegen`) pero **no** lo uses en este
> lane. Está en la lista de archivos con dueño único (§1.1).

### B3 · OFF-2 — `productByBarcode`
Trampa de la API: un barcode desconocido devuelve **HTTP 200** con `status = 0` y `product = null`.

Un check de status-only reporta el miss como un hit. Hay que **inspeccionar el body** y mapear ese
caso a `AppError.NotFound`.

### B4 · OFF-3 — Mapping DTO → dominio y búsqueda por texto
- Mapping de `data/openfood/dto/OpenFoodFactsDto.kt` a los modelos de `domain/model/`.
- La búsqueda por texto va contra el endpoint **v1**: `cgi/search.pl`. La v2 **no tiene** búsqueda
  libre.

### B5 · OFF-4 — Rate limits (antes de que esto salga)
- 15 req/min para lectura de producto, **10 req/min para búsqueda**.
- Un breach devuelve 503 y hay que mapearlo a `AppError.RateLimited`.
- **Search-as-you-type está prohibido.** 10/min son unas tres búsquedas: un query por tecla agota el
  presupuesto en menos de un segundo y deja la IP de todos rate-limited. Debounce + submit explícito.

### B6 · Q-4 — Test con MockWebServer
Dos asserts que hoy nadie tiene y que son los dos bugs que **fallan en silencio** cuando regresan:

1. el header `User-Agent` sale efectivamente en la request;
2. `status = 0` mapea a `NotFound`.

> **Pedí `mockwebserver` a Julian.** No está en `libs.versions.toml` y no lo agregues vos.

---

## 5. Julian — Persistencia local e integración

### J1 · FF-1 — Proyecto Firebase *(bloquea a Daniel)*
Crear el proyecto en Firebase Console, sumar la app Android con package **`com.racion.diariomercado`**,
descargar `google-services.json` y dejarlo en `app/`.

Verificado: `.gitignore` línea 18 ya cubre `google-services.json`. **Nunca se commitea.**

### J2 · Decisión de estrategia de cuenta — **HECHO**
Veredicto escrito en `ROADMAP.md` § *Decisions to make*, punto 2: **anonymous-first está ratificado**.

La línea que lo justifica, en una: `linkWithCredential` **conserva el mismo `uid`**, así que no hay
migración de datos de Firestore — la cuenta permanente hereda todo lo que ya escribió la sesión
anónima. El merge que esta tarea tenía que diseñar antes de FF-4 resultó no existir.

**No-objetivo de v1:** si el correo tipeado ya pertenece a otra cuenta, no se mergean los árboles de
datos; se le dice al usuario que esa cuenta ya existe y que inicie sesión con ella. La decisión se
puede revisar más adelante si alguna vez hacen falta cuentas obligatorias.

### J3 · DB-1 — Dependencias de Room y KSP — **HECHO** (3 oct 2026)
`androidx.room:room-runtime`, `room-ktx`, `room-compiler` (por KSP) en `libs.versions.toml`, y el
plugin KSP habilitado en `app/build.gradle.kts`. Esto también destraba el codegen de Moshi.

Room `2.8.5`, KSP `2.3.12`, Robolectric `4.17`. Tres desviaciones, cada una forzada por un fallo
observado al correr el build, no por criterio:
1. **KSP se elige contra AGP 9.4.0, no contra Kotlin.** AGP activa built-in Kotlin, y eso prohíbe
   toda la línea KSP 2.2.x (`2.2.20-2.0.4` incluida). La línea 2.3.x además dropeó el sufijo
   `-2.0.x`: `2.3.12` resuelve, `2.3.12-2.0.2` no existe.
2. **Room lleva `version.ref` explícito.** El BOM de Compose 2026.02.01 no contiene ninguna entrada
   `androidx.room`, así que declararlo sin versión falla con *Could not find*.
3. **`RoomSchemaArgProvider` usa `@InputFiles`, no `@InputDirectory`.** El patrón documentado por
   Google falla en Gradle 9.6: KSP crea el directorio de schemas sólo cuando hay una base que
   exporta, y git no trackea directorios vacíos.

También se saneó un defecto de build: `firebase-bom:34.12.0` y `firebase-auth` hardcodeadas y
duplicadas, que Gradle no reporta como error (resuelve a la mayor en silencio, dejando el catálogo
mintiendo). Las 3 libs de Google Sign-In se migraron al catálogo, sin uso en código.

### J4 · DB-2..DB-6 — Esquema local — **HECHO** (3 oct 2026)
- **Entities**: `DiaryEntryEntity`, `FoodProductEntity`, `NutritionGoalsEntity`, `UserProfileEntity`.
  Los day keys son strings `yyyy-MM-dd`, para que una semana sea un **range query léxico** y coincida
  con el layout de Firestore. `Nutrition` se **aplana** en 7 columnas: embebido las escondería tras
  `nutrition.kcal`, donde `SUM` no llega. Sin columna `onboardingCompleted`, para que el
  consentimiento se pueda escribir independiente de un write de perfil.
- **DAOs**: `DiaryDao`, `GoalsDao`, `ProfileDao`, `CatalogDao`. Cada read expone un `Flow`. La
  semana es un rango **semiabierto** (`>= from AND < to`): el límite superior exclusivo es el lunes
  siguiente, que quien camina de semana en semana ya tiene. Un `BETWEEN` inclusivo obliga a sumar 6
  días en el call site y se infla a 8 sin que nadie se entere.
- **Totales del día**: se calculan con `SUM` en la query del DAO. Nada de documento local con
  write-contention — los totales remotos en `days/{date}` son la proyección server-side del sync.
  `SUM` + `COALESCE(...,0)` sobre los 7 campos, con proyección dedicada `DayNutritionTotals`: sin el
  `COALESCE`, "no comió nada" (0) y "la query está rota" (null) serían el mismo valor.
- **Índices**: `@Index` en `DiaryEntry.dayKey`, y un `@Index` único en el barcode del catálogo
  (`FoodProductEntity.barcode`; el tipo `CatalogEntry` que menciona la spec no existe). La foreign
  key del diario al catálogo usa **`onDelete = RESTRICT`**, no `CASCADE`: cascadear desde una caché
  podable hacia el diario del usuario es pérdida de datos silenciosa causada por housekeeping
  rutinario. Dos tests lo fijan.
- **DB versionada** con estrategia de migración desde el día uno. `version = 1`,
  `exportSchema = true`, **sin** `fallbackToDestructiveMigration()`.

**Verificación**: `:app:assembleDebug` y `:app:testDebugUnitTest --rerun` en verde. **91 tests, 0
fallos, 0 salteados** (baseline 33 + 58 nuevos), contados desde los XML de JUnit. Robolectric 4.17
requirió `--add-exports=java.base/jdk.internal.access=ALL-UNNAMED` en la tarea `Test` porque el JBR de
la máquina es JDK 25.

**Desviaciones de la spec, con el código ganando (§7)**: `CatalogEntry` no existe →
`FoodProductEntity`; el cacheo de `LOCAL-*` son fixtures de `PreviewData`, no datos reales;
`days/{date}` lista 4 campos pero `DailySummary` exige 7 y explica por qué → los 7;
`ProfileRepository` declara 4 métodos pero 2 son de onboarding y necesitan una 5ta tabla (FF-5/FF-7)
→ no agregada.

**Pendiente**: `app/schemas/` no está commiteado (sin esa línea base no hay migraciones validables),
y Room todavía **no se inicializa** en `RacionApplication` (FF-2, de Daniel) ni se cablea en
`AppContainer`. Los DAOs están probados pero nadie los consume: la app sigue sin persistir hasta que
exista el wiring. Eso es un paso posterior, no un defecto de J3/J4.

### J5 · Mergear a `main`
Sólo con `:app:assembleDebug` y `:app:testDebugUnitTest` en verde.

### J6 · FF-4 — Habilitar los providers en Firebase Console *(bloquea a Daniel)*
En **Authentication → Sign-in method**:

- **Anonymous**: habilitado. Es el arranque de toda sesión nueva.
- **Email/Password**: habilitado. Es lo que permite reclamar la cuenta más adelante.

> **Es acceso a consola, así que es tuyo.** Daniel no lo puede hacer.
>
> **No se puede probar ningún sign-in hasta que esté.** Con el provider apagado, Firebase falla en
> runtime con un error poco descriptivo, y es fácil perder horas debuggeando el repositorio cuando
> el problema real era un toggle en la consola. Hacelo antes de que Daniel mergee su rama.

---

## 6. Orden sugerido de arranque

| Quién | Primer paso |
|---|---|
| **Brayan** | B1 (interceptor) → B3 → B4. No espera a nadie. |
| **Julian** | J1 (libera a Daniel) → J6 (libera las pruebas de Daniel) → J3 → J4. J2 ya está. |
| **Daniel** | D1 → D2 (**ya no espera a J2**) → **esperar J1** → D3 → D4 (probar sign-in real necesita J6), D5, D6 |

---

## 7. Si algo del roadmap no coincide con el código

El código gana. Si encontrás que una tarea está desactualizada, o el comportamiento real contradice lo
escrito acá, **no lo arregles por tu cuenta**: dejá el commit en verde, anotá la discrepancia en la PR
y avisá. Lo ajusta Julian en el roadmap, porque el roadmap es la referencia compartida de los tres.
