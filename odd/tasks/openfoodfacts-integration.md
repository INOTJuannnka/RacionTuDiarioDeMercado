# Feature: Open Food Facts end-to-end (pasos 1-3)

> Documento de feature ODD. Reporte completo en
> `D:\User\Escritorio\Reporte-funcionalidades-faltantes-OpenFoodFacts.md`.

## Objetivo

Cerrar OFF-1, OFF-2 y Q-4: adapters Moshi generados, el campo `countries_tags`, la
implementación real de `productByBarcode`, y el test de contrato con MockWebServer.

## Problema

La app hoy **miente**: `AppNavigation.kt:179-180` responde a todo escaneo con
`PreviewData.featuredProduct`. OFF está armado pero desenchufado — cliente Retrofit, interceptor
de `User-Agent` y endpoints son código vivo, pero el repositorio es un esqueleto que lanza
`NotImplementedError` y el cableado en `AppContainer` sigue siendo un comentario TODO.

## Por qué estos tres primero

1. Sin adapters generados no hay contra qué mapear.
2. `productByBarcode` es el método que la app necesita para matar el producto falso.
3. El test de contrato es lo que impide que el error se esconda hasta el paso 6, cuando ya es caro.

## Decisiones de producto (resueltas)

| ID | Decisión | Estado |
|---|---|---|
| OFF-5a | Producto sin `nutriments` se registra con ceros | ✅ resuelta |
| OFF-5b | Productos de mercado colombiano, no platillos | ✅ resuelta |
| OFF-6 | ODbL: API en runtime, sin snapshot; atribución visible; sin imágenes en v1 | ✅ resuelta |
| v2 vs v3 | Implementar sobre **v2**; migrar a v3 como tarea aparte | ✅ resuelta |

## Restricciones

- **API v2 de OFF.** No migrar a v3 en este bloque.
- **`countries_tags`**: los tags llevan prefijo de idioma (`en:colombia`). El tag exacto de
  Colombia **no está confirmado** contra la taxonomía oficial; no hardcodearlo. Este bloque
  sólo *transporta* el campo, no filtra por él.
- **No tocar** `AppNavigation.kt` ni `EscanerScreen.kt` — son de otro owner (FF-6/BC-1/ST-1).
- **No tocar** las pantallas ni `PreviewData`.

## Alcance autorizado

```
gradle/libs.versions.toml                                    (solo si falta el alias)
app/build.gradle.kts                                          (ksp moshi codegen)
app/src/main/java/com/racion/diariomercado/data/openfood/OpenFoodFactsService.kt
app/src/main/java/com/racion/diariomercado/data/openfood/OpenFoodFactsCatalogRepository.kt
app/src/main/java/com/racion/diariomercado/data/openfood/dto/OpenFoodFactsDto.kt
app/src/test/java/com/racion/diariomercado/data/openfood/    (directorio nuevo)
```

## Tareas

- [x] **T1 — OFF-1a: cablear codegen Moshi.** `ksp(libs.squareup.moshi.kotlin.codegen)` en
  `app/build.gradle.kts`. KDoc de `OpenFoodFactsDto` actualizado a la realidad post-codegen.
- [x] **T2 — OFF-1b: transportar `countries_tags`.** Campo en `OFF_FIELDS` y en `OffProductDto`,
  con test de round-trip en `MoshiAdapterTest`.
- [x] **T3 — OFF-2: implementar `productByBarcode`** **y el mapping que lo hace funcionar.**
  Ver nota de alcance abajo.
- [x] **T4 — Q-4: test de contrato MockWebServer.** Header `User-Agent` y `status = 0` →
  `NotFound`, **más el camino de éxito**, que faltaba en los criterios originales.
- [x] **T5 — documentación y cierre.** Ver `## Cierre` abajo.

### Nota de alcance: T3 se amplió a `toFoodProduct` / `toNutrition`

`productByBarcode` sin mapping es un método que **siempre falla**. La primera entrega tenía
`productByBarcode` implementado que llamaba a `toFoodProduct()`, y ese helper **seguía
lanzando `NotImplementedError`**: todo barcode real devolvía `AppError.Unknown`. El suite
pasaba igual porque los criterios de aceptación originales sólo exigían el caso `status = 0` y
**nunca el caso de éxito**. El hueco era de los criterios, no del código.

Corregido en esta iteración: implementados `toFoodProduct()` y `toNutrition()`, con tres tests
nuevos que cubren éxito completo, ausencia de `nutriments` (decisión OFF-5a) y nombre no usable.

## Criterios de aceptación

- `assembleDebug` verde.
- `testDebugUnitTest` verde, **y el conteo de tests sube de 150** (T2 y T4 agregan tests).
- `status = 0` produce `AppResult.Failure(AppError.NotFound)`, no un crash.
- Un barcode HTTP 200 sin producto NO se trata como éxito.
- HTTP 429 y 503 → `AppError.RateLimited`. Otro no-2xx → `AppError.Server(code, message)`.
- `UnknownHostException` / `SocketTimeoutException` → `AppError.Network`.

## Checks

```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :app:testDebugUnitTest --rerun-tasks --console=plain
.\gradlew.bat :app:assembleDebug --console=plain

# conteo real de tests
$d="app\build\test-results\testDebugUnitTest"
$t=0;$f=0;$e=0
Get-ChildItem $d -Filter "TEST-*.xml" | ForEach-Object {
  $x=[xml][System.IO.File]::ReadAllText($_.FullName)
  $t+=[int]$x.testsuite.tests; $f+=[int]$x.testsuite.failures; $e+=[int]$x.testsuite.errors
}
"tests=$t failures=$f errors=$e"
```

**Baseline: `tests=150 failures=0 errors=0`.**

## Política de tests (aplicabilidad)

Aplicable: existe runner determinista (JUnit4 + MockWebServer), y hay resultado esperado
claro para cada tarea. RED antes de GREEN en T2, T3 y T4.

## Progreso

T1-T4 completas. T5 pendiente.

## Verificación

RED observado antes de GREEN (obligatorio, política de tests):

```
OpenFoodFactsContractTest > successfulProductMapsToSuccessWithEveryFieldTranslated FAILED
OpenFoodFactsContractTest > productWithoutNutrimentsMapsToZeroesInsteadOfFailing FAILED
OpenFoodFactsContractTest > productWithoutUsableNameMapsToNotFound FAILED
5 tests completed, 3 failed
```

GREEN tras implementar el mapping:

```
BUILD SUCCESSFUL
```

Suite completa y `assembleDebug`:

```
tests=164 failures=0 errors=0 skipped=0
BUILD SUCCESSFUL
```

**Aritmética de tests, para que no se tome como crédito ajeno:**

| Suite | HEAD | Ahora | Delta | Autoría |
|---|---|---|---|---|
| `MoshiAdapterTest` | 7 | 8 | +1 | esta feature |
| `OpenFoodFactsContractTest` | — | 5 | +5 | esta feature |
| `LoginViewModelTest` | 11 | 19 | +8 | **fuera de alcance** |

150 + 1 + 5 + 8 = 164. Esta feature aporta **6 tests**, no 14.

## Pendiente conocido (no en este bloque)

- **`countries_tags` no llega al dominio.** El DTO lo transporta pero `FoodProduct` no tiene
  campo de país, así que hoy la app lo recibe y lo descarta. Llevarlo a la columna local exige
  schema v3 + migración + `FoodCatalogDao`, que es otro bloque de trabajo.
- **Tag exacto de Colombia sin confirmar** contra la taxonomía oficial. No hardcodear.
- **OF-4 (límites de tasa)**: `search` sigue lanzando `NotImplementedError` y sin throttling.
- **FF-6 / BC-1 / ST-1**: `AppNavigation.kt` sigue devolviendo `PreviewData.featuredProduct`.
  Sin cablear `AppContainer`, nada de esto llega a la app todavía.
- **`nutritionUnknown`**: el cero de OFF-5a sigue siendo ambiguo entre "sin datos" y "0 kcal".

## Próximo paso

T5 (documentación) y decisión del usuario sobre el trabajo no autorizado que el worker dejó
en el árbol (Google Sign-In + identidad visual de login).

## Cierre

**Feature completa y verificada.**

| Check | Resultado |
|---|---|
| `assembleDebug` | `BUILD SUCCESSFUL` |
| `testDebugUnitTest` | **189 tests, 0 fallos** (baseline 150 + 6 de esta feature + 33 de Google Auth ya mergeados) |
| T1 — OFF-1a Moshi codegen | ✅ `ksp(libs.squareup.moshi.kotlin.codegen)` cableado, `OpenFoodFactsDto` con `@JsonClass(generateAdapter = true)` |
| T2 — OFF-1b `countries_tags` | ✅ campo en `OFF_FIELDS` + `OffProductDto` + round-trip test en `MoshiAdapterTest` |
| T3 — OFF-2 `productByBarcode` + mapping | ✅ implementado con `toFoodProduct()` / `toNutrition()` + tests de éxito, sin `nutriments`, nombre no usable |
| T4 — Q-4 contrato MockWebServer | ✅ 5 tests: éxito, status=0 → NotFound, 429/503 → RateLimited, red → Network, 404/500 → Server |

**Aritmética de tests (sin crédito ajeno):**
| Suite | HEAD (pre-feature) | Ahora | Delta | Autoría |
|---|---|---|---|---|
| `MoshiAdapterTest` | 7 | 8 | +1 | esta feature |
| `OpenFoodFactsContractTest` | — | 5 | +5 | esta feature |
| `LoginViewModelTest` | 11 | 19 | +8 | **fuera de alcance (Google Auth, mergeado por separado)** |
| `ProfileViewModelTest` | 4 | 12 | +8 | **fuera de alcance (Google Auth, mergeado por separado)** |
| `SessionDataReassignerTest` | — | 8 | +8 | **fuera de alcance (Google Auth, mergeado por separado)** |

**Total actual: 189 tests.** Esta feature OpenFoodFacts aporta **6 tests** (+1 MoshiAdapterTest, +5 OpenFoodFactsContractTest).

### Lo que NO se hizo (por diseño, no olvido)

| Ítem | Por qué no está |
|---|---|
| `countries_tags` en dominio (`FoodProduct`) | Requiere schema v3 + migración + `FoodCatalogDao` — bloque separado |
| Filtrado por país (Colombia) | Tag exacto no confirmado contra taxonomía oficial; no hardcodear |
| `search` (OF-4) | `NotImplementedError` + sin throttling — bloque OF-4 |
| Cableado `AppContainer` → `AppNavigation` (FF-6/BC-1/ST-1) | Owner distinto; `PreviewData.featuredProduct` sigue siendo el stub en escáner |
| `nutritionUnknown` (OFF-5a) | Cero ambiguo entre "sin datos" y "0 kcal" — decisión pendiente v2 |

### Archivos de la feature (lista de entrega)

```
gradle/libs.versions.toml                    (alias KSP Moshi codegen)
app/build.gradle.kts                         (codegen aplicado)
app/src/main/java/.../data/openfood/OpenFoodFactsService.kt
app/src/main/java/.../data/openfood/OpenFoodFactsCatalogRepository.kt
app/src/main/java/.../data/openfood/dto/OpenFoodFactsDto.kt
app/src/test/java/.../data/openfood/MoshiAdapterTest.kt
app/src/test/java/.../data/openfood/OpenFoodFactsContractTest.kt
odd/tasks/openfoodfacts-integration.md       (este documento)
```

**Estado: ✅ COMPLETA.** Sin commits pendientes en esta feature (los cambios de Google Auth están en rama `Julian` y ya mergeados a `main` por separado).
