# Room local persistence — J3 + J4

## Objective

Habilitar la base de datos local ROOM como source of truth offline-first (DB-1..DB-6 del
roadmap), con entities, DAOs observables, totales del día calculados por `SUM` e índices, y
estrategia de versionado con export de schemas desde el primer día.

## Problem

El diario, el perfil y las metas no tienen dónde vivir. Hoy `domain/repository/` declara las
interfaces (`DiaryRepository`, `GoalsRepository`, `ProfileRepository`, `FoodCatalogRepository`)
pero no hay ninguna implementación: `AppContainer` los tiene todos en un bloque `TODO`.
La app no persiste nada.

Además hay un defecto de build sin commitear en `app/build.gradle.kts`: `firebase-bom` y
`firebase-auth` están declarados dos veces, y las tres libs de Google Sign-In están
hardcodeadas fuera del catálogo de versiones.

## Why

Una app de mercado se usa con señal intermitente. Sin base local, cada pantalla espera la red
y en el super la app es inusable. ROOM es la fuente de verdad; Firestore queda como proyección
de sync/backup, nunca el primer destino de una escritura.

## Scope

**Dentro:**
- J3 / DB-1: deps de Room + plugin KSP en el catálogo y en `app/build.gradle.kts`.
- J4 / DB-2..DB-6: 4 entities, type converters, 4 DAOs con `Flow`, `SUM` de totales del día,
  índices, `RoomDatabase` versionada con export de schemas.
- Saneo de las dependencias duplicadas de Firebase.
- Migración de las 3 libs de Google Sign-In al catálogo de versiones (sin usarlas).
- Tests de DAO con base en memoria (parcial de Q-3).

**Fuera (no tocar):**
- `domain/repository/**` — los contratos ya están y son la referencia. No se modifican.
- `domain/model/**` — los modelos ya están.
- `data/firebase/**` — es de Daniel.
- `data/openfood/**` — es de Brayan.
- `ui/**` — no se toca ninguna pantalla en esta tarea.
- `MainActivity.kt` / `AppNavigation.kt` — el gateo por auth (FF-7) es de Daniel.
- `RacionApplication.kt` — FF-2 es de Daniel. NO se inicializa Room ahí en esta tarea.

## Constraints

- Artefactos técnicos en **inglés**: código, KDoc, identificadores, nombres de test.
- Documentos de coordinación en **español neutro profesional**.
- Sin atribución a IA. Conventional commits.
- Propietario único de `app/build.gradle.kts`, `gradle/libs.versions.toml` y
  `di/AppContainer.kt`: Julian.
- Los day keys son strings `yyyy-MM-dd` para que una semana sea un range query léxico.
- Nada de documento local de totales: los totales del día se calculan con `SUM` en la query.
- El build en Windows necesita `JAVA_HOME` al JBR de Android Studio en el mismo comando.

## Decisions

- **Room 2.8.5** (última estable). **KSP `2.2.10-2.0.2`**, que existe para el Kotlin 2.2.10
  del catálogo — verificado contra Maven Central, no asumido.
- **KSP, no KAPT.** KAPT con Kotlin 2.2 está deprecado. Room 3 va a exigir KSP igual.
- **Robolectric 4.17** para los tests de DAO: es la primera versión con soporte de SDK 37,
  que es el `compileSdk` del proyecto. Riesgo conocido: el JBR de esta máquina es **JDK 25**,
  más nuevo que lo que Robolectric suele soportar. Si falla, se documenta el fallo exacto
  en vez de degradar los tests a mano.
- **`exportSchema = true` + `RoomSchemaArgProvider`.** DB-6 pide la fontanería de migración
  desde el día uno; sin el schema exportado no hay con qué testear una migración después.
- **`Nutrition` no se embebe como entidad.** Sus campos se planean en la entity para
  que el `SUM` sea una query SQL real y no una agregación en Kotlin — ese es el punto de DB-4.
- **Las 3 libs de Google Sign-In van al catálogo, no se usan.** La estrategia ratificada es
  anónimo-primero + Email/Password; Google Sign-In es alcance futuro de otro lane. Quedan
  declaradas en el catálogo y **sin uso en el código**, que es exactamente el estado en que
  estavam antes de este trabajo.

## Acceptance criteria

- `./gradlew :app:assembleDebug` en verde.
- `./gradlew :app:testDebugUnitTest --rerun` en verde, **>= 33 tests** (la línea base nunca baja).
- 4 entities con los índices que pide DB-5.
- 4 DAOs; cada read expone `Flow`.
- Totales del día calculados con `SUM` en SQL, verificado por un test que inserta 3 entradas y
  espera la suma.
- `RacionDatabase` con `version` explícita y schemas exportados en `app/schemas/`.
- Cero declaraciones duplicadas de un mismo artifact en `app/build.gradle.kts`.
- Cero dependencias hardcodeadas fuera de `libs.versions.toml`.

## Checklist

- [ ] J3 · DB-1 — Catálogo: `room`, `room-ktx`, `room-compiler`, `room-testing`, `robolectric`,
      plugin `ksp`, `androidx-test-core`, `credentials`, `credentials-play-services-auth`,
      `googleid`.
- [ ] J3 · DB-1 — `app/build.gradle.kts`: plugin KSP aplicado, deps por `libs.*`,
      `RoomSchemaArgProvider`, `testOptions.unitTests.isIncludeAndroidResources = true`.
- [ ] Saneo — borrar `firebase-bom` y `firebase-auth` hardcodeados/duplicados.
- [ ] J4 · DB-2 — `DiaryEntryEntity`, `FoodProductEntity`, `NutritionGoalsEntity`,
      `UserProfileEntity` + type converters.
- [ ] J4 · DB-3 — `DiaryDao`, `GoalsDao`, `ProfileDao`, `CatalogDao`, todos con `Flow`.
- [ ] J4 · DB-4 — `SUM` de los 7 campos de nutrición por day key.
- [ ] J4 · DB-5 — `@Index` en `DiaryEntryEntity.dayKey`, `@Index(unique = true)` en el barcode.
- [ ] J4 · DB-6 — `RationDatabase` versionada + export de schemas.
- [ ] Q-3 (parcial) — Tests de DAO con base en memoria.
- [ ] Verificación — `assembleDebug` + `testDebugUnitTest --rerun` con conteo real.
- [ ] Reporte PDF en el Escritorio.
- [ ] Actualizar `docs/ROADMAP.md` y `docs/SPRINT-1.md` con el estado real.

## Route

Delegated direct: un solo writer. Son 2+ archivos no triviales (catálogo, build, 4 entities,
4 DAOs, database, converters, tests) → dispara la regla de writer. El padre conserva la
verificación, el reporte y el sanitizeo final.

## Progress

**J3 · DB-1 — HECHO.** Route: delegated direct (worker).
- Catalogo: `room = 2.8.5`, `ksp = 2.3.12`, `robolectric = 4.17`, `androidxTestCore = 1.6.1`,
  `credentials = 1.3.0`; entradas Room (4), Robolectric, test:core, credentials (2), googleid;
  plugin `ksp`.
- `app/build.gradle.kts`: plugin KSP aplicado, `RoomSchemaArgProvider` con `@InputFiles`,
  `testOptions.unitTests.isIncludeAndroidResources = true`, deps Room por `libs.*`.
- **Tres desviaciones, cada una forzada por un fallo observado** (no por criterio):
  1. KSP es **2.3.12**, no `2.2.10-2.0.2`. AGP 9.4.0 activa built-in Kotlin y eso prohibe toda la
     linea KSP 2.2.x. Ademas la linea 2.3.x **dropo el sufijo** `-2.0.x`: `2.3.12` resuelve,
     `2.3.12-2.0.2` no existe.
  2. Room **lleva `version.ref` explicito**. El BOM de Compose 2026.02.01 no contiene ninguna
     entrada `androidx.room`, asi que declararlo sin version falla.
  3. `RoomSchemaArgProvider` usa **`@InputFiles`**, no el `@InputDirectory` que documenta Google:
     el patron documentado falla en Gradle 9.6 porque KSP crea el directorio solo cuando hay una
     base que exporta schema.

**Saneo de Firebase — HECHO.** `firebase-bom:34.12.0` y `firebase-auth` hardcodeadas/duplicadas
eliminadas. Las 3 libs de Google Sign-In migradas al catalogo, sin uso en codigo.

**J4 · DB-2..DB-6 — HECHO.** Route: delegated direct (worker).
- 4 entities en `data/local/entity/`. `Nutrition` **aplana** sus 7 campos (no `@Embedded`).
- FK `DiaryEntryEntity.productBarcode` -> `FoodProductEntity.barcode` con
  **`onDelete = RESTRICT`**, `onUpdate = CASCADE`. `CASCADE` permitiria que un
  `DELETE FROM food_products WHERE barcode=?` de limpieza de cache borrara en silencio el diario.
- `Converters.kt`: categorias como **JSON via Moshi** (los elementos de Open Food Facts contienen
  comas; `joinToString(",")` trunca en la primera fila real), `activeDays` delimitado (nombres de
  enum: dominio cerrado, sin comas). Orden Monday..SUNDAY via `import java.time.DayOfWeek as
  JavaDayOfWeek`.
- 4 DAOs, todo read expone `Flow`. Query semanal **semiabierta** `>= from AND < to`.
- Totales del dia: `SUM` + `COALESCE(...,0)` sobre los 7 campos, proyeccion dedicada
  `DayNutritionTotals` (**no** `Nutrition`).
- `RationDatabase`: `version = 1`, `exportSchema = true`, **sin**
  `fallbackToDestructiveMigration()`.
- `@Index` en `dayKey` y `productBarcode`; indice unico en `barcode`.

**Q-3 (parcial) — HECHO.** 58 tests nuevos de DAO con base en memoria.

**Defecto encontrado y corregido durante la verificacion:** los 35 tests Robolectric fallaban con
`IllegalAccessException: ... jdk.internal.access.SharedSecrets` porque el JBR de esta maquina es
**JDK 25** y ese paquete interno ya no se exporta. Fix: bloque
`tasks.withType<Test>().configureEach { jvmArgs(...) }` en `app/build.gradle.kts`. El flag
load-bearing es `--add-exports=java.base/jdk.internal.access=ALL-UNNAMED`; los tres `--add-opens`
son preventivos y **no se aislaron empiricamente**. La senal positiva de que funciona es el warning
`System::load` de `DefaultNativeRuntimeLoader`: solo aparece cuando Robolectric supera el
`FileDescriptorInterceptor`.

## Verification

```
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat :app:assembleDebug --console=plain
BUILD SUCCESSFUL

$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat :app:testDebugUnitTest --rerun --console=plain
BUILD SUCCESSFUL
```

Conteo desde los XML de JUnit (nunca desde la linea verde):

| Suite | Tests | F | E |
|---|---|---|---|
| data.local.DiaryDaoTest | 19 | 0 | 0 |
| data.local.CatalogDaoTest | 16 | 0 | 0 |
| data.local.ConvertersTest | 17 | 0 | 0 |
| data.local.EntityDefaultsTest | 6 | 0 | 0 |
| domain.model.NutritionTest | 14 | 0 | 0 |
| ui.auth.LoginViewModelTest | 11 | 0 | 0 |
| data.openfood.MoshiAdapterTest | 7 | 0 | 0 |
| ExampleUnitTest | 1 | 0 | 0 |
| **TOTAL** | **91** | **0** | **0** |

Skipped: **0**. Baseline de 33 intacto (Ningun suite perdio tests). XML escritos 12 s antes del
conteo: corrida real, no cacheada.

`app/schemas/com.racion.diariomercado.data.local.RationDatabase/1.json` existe (10.867 bytes),
`identityHash cbbd157752eb056ac4fdeabfa180f692`.

## Desviaciones del roadmap (el codigo gano, por §7 del Sprint 1)

| Roadmap dice | Realidad | Se hizo |
|---|---|---|
| DB-5: `CatalogEntry.barcode` | No existe ningun tipo `CatalogEntry` | `FoodProductEntity.barcode` |
| DB-2: cachea `LOCAL-*` | Son fixtures de `ui.preview.PreviewData`, su propio KDoc los llama falsos | Un barcode es un barcode |
| `days/{date}` lista 4 campos | `DailySummary` exige 7 y explica por que | Los 7 |
| `ProfileRepository` tiene 4 metodos | 2 son de onboarding, necesitan una 5ta tabla (FF-5/FF-7) | No agregada |

## Pendiente

- [ ] **`app/schemas/` sin commitear.** Un schema que no entra al control de versiones no sirve
      como linea base de migracion cuando llegue la v2.
- [ ] **Nada commiteado ni pusheado**, por instruccion del usuario. Todo en el working tree.
- [ ] Room **no se inicializa** en `RacionApplication` (FF-2, de Daniel) y **no se cablea** en
      `AppContainer`. Los DAOs estan probados pero nadie los consume: la app sigue sin persistir
      hasta que exista el wiring. Eso es un paso posterior, no un defecto de J3/J4.
- [ ] `mockwebserver` sigue **faltando** en el catalogo: Brayan lo necesita para B6/Q-4 y no lo
      tiene. Es el bloqueo externo que Julian puede resolver ahora mismo.
- [ ] Comentario stale: `squareup-moshi-kotlin-codegen` dice "inert until KSP is applied" y KSP ya
      esta aplicado. Corregirlo toca el razonamiento de OFF-1 (lane de Brayan): anotado, no
      ampliado.
- [ ] Decidir si `odd/` entra al repo o se ignora.

## Next step

Revision y test del usuario. Después, commit por unidad de trabajo (J3 y J4 separados) y avisar a
Brayan y Daniel el nuevo `main`.

## Key Learnings

1. La version de KSP se elige contra la de **AGP**, no contra la de Kotlin: AGP 9.4.0 activa
   built-in Kotlin y prohibe toda la linea KSP 2.2.x.
2. La linea KSP 2.3.x dropo el sufijo `-2.0.x`, asi que `2.3.12` es la coordenada valida y
   `2.3.12-2.0.2` no resuelve.
3. El BOM de Compose no constrain `androidx.room`, asi que Room no se puede declarar sin version.
4. El `RoomSchemaArgProvider` documentado con `@InputDirectory` falla en Gradle 9.6; KSP crea el
   directorio de schemas solo cuando hay una base que exporta.
5. Robolectric 4.17 corre bien en JDK 25 con `--add-exports=java.base/jdk.internal.access=ALL-UNNAMED`
   en la tarea `Test`; la incompatibilidad no era fundamental.
6. `onDelete = RESTRICT` y no `CASCADE`: cascadear desde una cache podable hacia el diario del
   usuario es perdida de datos silenciosa causada por housekeeping rutinario.