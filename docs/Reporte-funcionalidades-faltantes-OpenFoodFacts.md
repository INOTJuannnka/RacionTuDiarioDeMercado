# Reporte: funcionalidades faltantes para que la app funcione

**Fecha:** 2026-10-03 · **Rama:** `Julian` (`f1f3278`) · **Tests hoy:** 150 verdes, 0 fallos

---

## 1. Veredicto corto

Tu intuición es **correcta**, pero incompleta, y la diferencia importa.

Open Food Facts **no** está "sin conectar". Está **con las piezas puestas y el cable desconectado**:

- El cliente HTTP, el User-Agent y los endpoints Retrofit **funcionan y están en vivo**.
- El repositorio **existe pero es un esqueleto que lanza `NotImplementedError`**.
- El cableado en `AppContainer` **sigue siendo un comentario TODO**.
- Y lo peor: **escanear un código de barras hoy devuelve siempre el mismo producto falso.**

Ese último punto es el que cambia la prioridad. No es que falte "conectar la API": es que la
app **miente**. Un usuario escanea y ve un producto de preview, siempre el mismo. Eso es peor
que un error visible, porque el usuario cree que funcionó.

**Alcance de este reporte:** 4 de 6 tareas de OFF (la API). Al final hay una sección con el
resto de lo que falta para que la app funcione de punta a punta, para que no te surprises.

---

## 2. Qué existe (código vivo) vs. qué falta

| Pieza | Estado | Evidencia |
|---|---|---|
| Cliente Retrofit + `MoshiConverterFactory` | ✅ **VIVO** | `AppContainer.kt:98` |
| Interceptor `User-Agent` (requisito de OFF) | ✅ **VIVO** | `AppContainer.kt:48` |
| `openFoodFactsService` (endpoints) | ✅ **VIVO** | `AppContainer.kt:108`, `OpenFoodFactsService.kt:27,44` |
| Interfaz `FoodCatalogRepository` | ✅ **VIVO** | `FoodCatalogRepository.kt:25` |
| `LocalDiaryRepository.addEntry` (persiste producto + entrada) | ✅ **VIVO** | `LocalDiaryRepository.kt:149` |
| `OpenFoodFactsCatalogRepository` (clase) | ⚠️ **ESQUELETO** — `throw NotImplementedError` | `OpenFoodFactsCatalogRepository.kt:48,63,81,95` |
| `productByBarcode` (OFF-2) | ❌ **FALTA** | ídem |
| Mapping DTO → dominio (OFF-3) | ❌ **FALTA** | ídem |
| `search` texto libre (OFF-3) | ❌ **FALTA** | ídem |
| Wiring en `AppContainer` | ❌ **SOLO COMENTARIO** | `AppContainer.kt:207-210` |
| Adapters Moshi generados (OFF-1) | ❌ **FALTA** | `OpenFoodFactsDto.kt:19` |
| Campo `countries_tags` (necesario para el enfoque colombiano) | ❌ **FALTA** | `OpenFoodFactsService.kt:59`, `OpenFoodFactsDto.kt:62` |
| Escaneo real (BC-1) | ❌ **FALTA** — devuelve `PreviewData.featuredProduct` | `AppNavigation.kt:179-180` |
| Persistencia del escaneo (FF-6) | ❌ **FALTA** — solo memoria volátil | `AppNavigation.kt:207`, `:86` |
| Test de contrato OFF (Q-4) | ❌ **FALTA** — `MockWebServer` instalado pero sin usar | `docs/ROADMAP.md:289` |

**El único test de OFF que existe** es `MoshiAdapterTest.kt`. No hay ningún test de red real.

> ⚠️ **El roadmap está desactualizado.** `docs/ROADMAP.md:205` marca `OFF-1` (Setup de
> Retrofit + Moshi) como **pendiente**, pero ya está hecho y en vivo. No confíes en los
> checkboxes del roadmap como fuente de verdad — verifiqué el código.

---

## 3. La cadena rota: qué pasa hoy al escanear

Este es el flujo real, leyendo el código:

```
Usuario escanea
      │
      ▼
AppNavigation.kt:180  onScanResult {
      │                  navResult.pendingProduct = PreviewData.featuredProduct
      │                  ▲^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
      │                  ⚠️ DATO FAJO. No consulta la API. No usa Open Food Facts.
      ▼
AppNavigation.kt:207  TODO(FF-6) — construye el DiaryEntry pero NO lo persiste
      │
      ▼
AppNavigation.kt:86   TODO(ST-1) — queda en un objeto en memoria (navResult)
      │
      ▼
   💥 Se pierde al rotar el dispositivo o cerrar la app.
```

**Tres cortes consecutivos**, y ninguno está donde uno esperaría:

1. **BC-1** — el escaneo no llama a OFF. Devuelve un producto hardcodeado.
2. **FF-6** — el resultado no se persiste contra el repositorio.
3. **ST-1** — lo que "se guardó" vive en un objeto en memoria. No sobrevive al proceso.

Ironía útil: **`LocalDiaryRepository.addEntry` ya está listo y funciona.** Ya persiste producto
+ entrada en una transacción (`LocalDiaryRepository.kt:149`). La infraestructura que falta para
persistir ya existe; lo que falta es la *llamada*. FF-6 es barato de cerrar.

---

## 4. Un riesgo que no vas a ver si no te lo digo

`OpenFoodFactsCatalogRepository` lanza `NotImplementedError`, y los KDoc lo-advierten:

```kotlin
// OpenFoodFactsCatalogRepository.kt:49-52
// Note this throws NotImplementedError (an Error, NOT an Exception):
// the documented "no method may throw" contract applies to REAL implementations, and callers
// writing `catch (e: Exception)` will not catch this stub.
```

Es un `throw` **desnudo**, sin `runCatching`. `NotImplementedError` extiende `Error`, no
`Exception`. Consecuencia práctica: si cableás el repositorio sin implementar los métodos, la
app **no** cae en tu `catch (e: Exception)` ni en tu `AppResult.Failure`. Se rompe con un crash
duro, en un hilo de corrutinas, sin que tu manejo de errores lo vea.

**Por qué importa para el orden de los pasos:** implementar los métodos **antes** de cablear.
Si cableás primero, el test falla de forma engañosa y vas a perder tiempo buscando el error en
el lugar equivocado.

---

## 5. Paso a paso — orden por dependencias

Cada paso es un commit. Respetá el orden: hay dependencias reales.

### Paso 1 — Cerrar OFF-1: adapters Moshi generados + campo `countries_tags`
**Archivo:** `data/openfood/dto/OpenFoodFactsDto.kt` y `data/openfood/OpenFoodFactsService.kt`
**Qué, dos cosas:**
1. Aplicar KSP con `libs.squareup.moshi.kotlin.codegen` (`AppContainer.kt:86` ya lo avisa)
   para que `@JsonClass(generateAdapter = true)` funcione.
2. Sumar `countries_tags` a `OFF_FIELDS` (`OpenFoodFactsService.kt:59`) y el campo al DTO:
   `@Json(name = "countries_tags") val countriesTags: String?`.
   **Hace falta para el enfoque colombiano** (ver 6.4): hoy la app no le pregunta a OFF de
   qué país es cada producto.
**Verificar:** el test `MoshiAdapterTest` debe compilar y pasar sin cambios.
**Por qué primero:** sin adapters generados, todo el mapping de OFF-3 no tiene contra qué
funcionar. Es la base.

### Paso 2 — Implementar `productByBarcode` (OFF-2)
**Archivo:** `data/openfood/OpenFoodFactsCatalogRepository.kt:48`
**Qué:** reemplazar el `throw` por la llamada real, con este mapeo de errores (copiado del KDoc
de la línea 42-46, es el contrato acordado):
- `status != 1 || product == null` → `AppResult.Failure(AppError.NotFound)`
- `UnknownHostException` / `SocketTimeoutException` → `AppError.Network`
- HTTP 429 / 503 → `AppError.RateLimited`
- otro no-2xx → `AppError.Server(code, message)`
- resto → `AppError.Unknown(it)`

**Ojo, esto es una trampa real:** OFF devuelve **HTTP 200** con `status: 0` para un barcode
desconocido. Si sólo mirás el código HTTP, tratás un "no existe" como éxito con producto nulo.
Tenés que mirar el **cuerpo**.

### Paso 3 — Test de contrato con MockWebServer (Q-4)
**Archivo:** nuevo en `app/src/test/java/com/racion/diariomercado/data/openfood/`
**Qué:** test grabado que verifique **dos** cosas:
1. La petición sale con el header `User-Agent` correcto.
2. `status = 0` se traduce a `AppResult.Failure(AppError.NotFound)`.

**Por qué en este punto:** `mockwebserver:4.12.0` ya está en `debugUnitTestRuntimeClasspath`
(`build.gradle.kts:199`), pero **no hay ni un test que lo use**. Estás pagando la dependencia
sin cobrar el valor. Este paso la paga.
**Verificación:** contar tests en `app/build/test-results/testDebugUnitTest/TEST-*.xml` — el
número debe subir de 150.

### Paso 4 — Implementar el mapping DTO → dominio (OFF-3)
**Archivo:** `OpenFoodFactsCatalogRepository.kt:75,90`
**Qué:** los dos helpers que faltan:
- Nombre: devolver `null` cuando no hay nombre usable (`ProductName.toFoodProductName()`).
- Nutriments: mapear el sub-objeto `nutriments` a `Nutrition`, **redondeando `kcal`**.
  **Aplicar la decisión 6.1:** si no hay `nutriments`, devolver `Nutrition` con ceros **y**
  `nutritionUnknown = true`, para que el cero sea explícito y no indistinguible de un
  producto que realmente tiene 0 calorías.

### Paso 5 — Implementar `search` (OFF-3)
**Archivo:** `OpenFoodFactsCatalogRepository.kt:63`
**Qué:** usar `searchV1` (v2 no soporta texto libre), mapear con el helper del paso 4, descartar
entradas sin nombre usable, y capar el resultado a `pageSize`.

### Paso 6 — Cablear en `AppContainer`
**Archivo:** `di/AppContainer.kt:207-210`
**Qué:** promover el bloque comentario a código vivo:
```kotlin
val foodCatalogRepository: FoodCatalogRepository by lazy {
    OpenFoodFactsCatalogRepository(openFoodFactsService)
}
```
Nota: la clase es `internal`, así que el acceso desde otro paquete requiere revisar la
visibilidad. Verificá esto.
**Verificar:** el build debe seguir verde **y** los 150 tests más los nuevos.

### Paso 7 — Cerrar FF-6: persistir el escaneo
**Archivo:** `ui/navigation/AppNavigation.kt:207`
**Qué:** reemplazar la construcción en memoria por la llamada real:
`diaryRepository.addEntry(diaryEntry)`. La firma ya está lista y `LocalDiaryRepository`
persiste producto + entrada transaccionalmente.
**Extra:** `DiaryRepository.addEntry` toma **un solo** parámetro (`DiaryEntry`), y el producto
va embebido adentro. No hace falta cambiar ninguna firma.

### Paso 8 — Cerrar BC-1: escaneo real
**Archivo:** `ui/navigation/AppNavigation.kt:179-180`
**Qué:** reemplazar `PreviewData.featuredProduct` por la llamada real a
`foodCatalogRepository.productByBarcode(barcode)`, con estados de carga y error visibles.
**Depende de:** pasos 2 y 6.

### Paso 9 — Cerrar ST-1: sacar el seam en memoria
**Archivo:** `ui/navigation/AppNavigation.kt:86`
**Qué:** reemplazar el `navResult` en memoria por un `ViewModel` compartido con scope de
activity, o `SavedStateHandle`. Mientras sea un objeto en memoria, el paso 7 escribe y después
se pierde.

### Paso 10 — Cerrar OFF-4: límites de tasa
**Qué:** aplicar throttling antes de que esto salga a producción. OFF tiene límites de tasa y
su API es un bien común — abusarla es una falta de respeto al proyecto, no sólo un riesgo técnico.
`search` sólo debe alcanzarse desde un submit explícito o una query con debounce, **nunca por
tecla**.

---

## 6. Decisiones de producto — RESUELTAS

### 6.1 Productos sin `nutriments` → se registran con ceros

**Decisión:** si OFF no trae tabla de nutrición, el producto se registra igual con
calorías y macronutrientes en **cero**. No se bloquea.

**Consecuencia práctica:** un yogur sin datos de nutrición entra al diario y **cuenta como
0 kcal**, no como "desconocido". Ojo con esto: es un dato *falso* presentado como verdadero.
El diario va a subestimar la ingesta calórica de quien registre productos sin tabla.

**Mitigación que implemento (decidí esto yo, no estaba en tu pregunta):** marcar esos productos
con un flag `nutritionUnknown` en la entidad local, y mostrarlo en la UI. Así el cero es
explícito y el usuario puede corregirlo a mano después. Sin el flag, un 0 es indistinguible
de "el producto realmente tiene 0 calorías".

### 6.2 Enfoque: productos de mercado colombiano, no platillos

**Decisión:** trabajar con **productos** que se encuentran en mercados Colombia. No platillos
prepared (sancocho, patacón, etc.) por ahora.

**Verifiqué tu observación y es correcta.** La faceta de Colombia de OFF existe y tiene
productos reales: *Club Social Integral Tradicional - Nabisco*, *Bizcochitos - Molino
Cañuelas*, *Suero Electrolit*, *Yogurt Griego Alpina*, *Coca-Cola pet*. Tu hallazgo no era
una suposición.

**Corrección a mi reporte anterior:** yo lo había planteado mal, como si OFF no tuviera
cobertura colombiana. La cobertura existe y es real. El problema real es otro, y es técnico
(ver 6.4).

### 6.3 Licencia (OFF-6) — decisión tomada, te la explico

No la entendías porque estaba mal explicada. Va en claro:

**De dónde vienen los datos.** Open Food Facts es un proyecto voluntario. Los datos están bajo
**ODbL** (Open Database License), y el contenido individual (fotos, textos) bajo **DbCL**.

**Qué te obliga a vos.** Dos cosas:

1. **Atribución obligatoria.** Hay que *creditar* a Open Food Facts. Es barato: una línea en
   la pantalla donde aparecen los productos.
2. **Share-alike sobre la base de datos.** Si distribuís una base derivada, tenés que
   publicarla bajo ODbL. Esto es lo que asusta, pero tiene un contorno que lo hace manejable.

**Lo que NO está afectado:** tu **código**. ODbL es copyleft de *datos*, no de software. Tu
código sigue siendo tuyo, incluso si lo subís a GitHub.

**Mi decisión para este proyecto (4 partes):**

1. **Nada de base de datos empaquetada.** Nunca un snapshot de OFF dentro del repo ni dentro
   del APK. Todo se consulta por API en runtime. Esto es lo que mantiene el share-alike lejos
   de cualquier cosa que distribuyamos — **es la decisión de mayor impacto y ya está tomada
   por arquitectura**: el proyecto hoy consulta la API y no vendoriza nada.
2. **Atribución visible** en la pantalla de producto: "Datos de Open Food Facts, licencia ODbL".
3. **La caché en ROOM es copia local de trabajo**, no una base distribuida. No se exporta ni
   se comparte.
4. **No replicar ni alojar las imágenes de OFF.** Para v1 la opción más limpia es **no
   mostrar imágenes**, o enlazar a la fuente. Las fotos tienen su propio licencia (CC-BY-SA en
   la mayoría) y es la parte más pegajosa del asunto.

**Consecuencia práctica:** si más adelante querés distribuir un dataset con la app, hay que
revisar esto de nuevo. Para una app que consulta la API, esto es sentido común y ya estamos
bien.

### 6.4 Hallazgo que tu decisión exige: falta pedir `countries_tags`

Acá está el problema técnico real de trabajar con productos colombianos.

**Verificado:** `OFF_FIELDS` (`OpenFoodFactsService.kt:59-61`) **no pide `countries_tags`**, y
`OffProductDto` (`OpenFoodFactsDto.kt:62-73`) **no tiene el campo**. Hoy la app no pide a OFF
de qué país es cada producto, y por lo tanto no puede ni filtrar ni mostrar esa información.

**Se agrega como paso 1-bis:** sumar `countries_tags` a `OFF_FIELDS` y el campo al DTO
(`@Json(name = "countries_tags") val countriesTags: String?`).

**Sobre el tag:** en OFF los tags llevan prefijo de idioma, tipo `en:colombia` (el ejemplo de
Nutella devuelve `en:france,en:italy,en:united-states`). La taxonomía oficial se puede
consultar en `GET https://world.openfoodfacts.org/data/taxonomies/countries.json`.
**Hay que confirmar el tag exacto de Colombia antes de filtrar** — no lo voy a asumir.

### 6.5 Hallazgo que no venías buscando: la API v2 está deprecada

La documentación oficial de OFF marca **v2 como deprecada**: *"still supported for backward
compatibility; Migrate to v3 for new integrations"*.

**Este proyecto usa `api/v2/product/{barcode}.json`** (`OpenFoodFactsService.kt:27`).

**Mi lectura:** v2 funciona hoy, así que **no bloquea la v1**. Pero migrar a v3 después implica
rehacer el mapping, porque v3 cambia la forma de la respuesta (y v3.6 introduce un esquema de
tags nuevo). Migrar ahora, sin haber implementado nada todavía, es más barato que migrar
después.

**Decisión que propongo:** implementar los pasos sobre v2 para tener algo funcionando, y
abrir la migración a v3 como tarea propia y explícita. No mezcles ambas en el mismo cambio —
v3 toca el shape de la respuesta y el mapping a la vez, y eso hace el review imposible.
**Esto es una decisión tuya, no la tomé.**

---

## 7. Lo que falta fuera de OFF (para que la app funcione de punta a punta)

No es el foco que pediste, pero te lo dejo para que no descubras esto a mitad de la demo:

| ID | Qué falta | Impacto |
|---|---|---|
| **FF-7** | `AppNavigation.kt:78` — `authState` no se colecta; LOGIN no es start destination | El usuario entra sin loguearse |
| **FF-5** | `AppNavigation.kt:191,235` — metas y perfil no se guardan | Botones que no persisten nada |
| **ST-2** | `AppNavigation.kt:169` — entrada manual de código sin affordance | Camino sin salida |
| **FF-4** | `MetasScreen.kt:161` — la fila es superficie de sesión del esqueleto | UI que promete más de lo que da |
| **DB-7/8** | `SyncTransport` real contra Firestore + pull remoto + scheduler | La bandeja de sync se llena y **no se vacía** |
| — | `ProfileViewModel` (367 líneas) sin un solo test | Rompe la convención del repo |

**DB-7 es el más silencioso de todos:** la app funciona, escribe en la bandeja, y nunca
sincroniza. No se rompe — se acumula basura. difficult de detectar en QA manual.

---

## 8. Comandos de verificación

```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :app:testDebugUnitTest --rerun-tasks --console=plain

$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :app:assembleDebug --console=plain

# Contar tests reales (no confíes en el output del gradle)
$d="app\build\test-results\testDebugUnitTest"
$t=0;$f=0;$e=0
Get-ChildItem $d -Filter "TEST-*.xml" | ForEach-Object {
  $x=[xml][System.IO.File]::ReadAllText($_.FullName)
  $t+=[int]$x.testsuite.tests; $f+=[int]$x.testsuite.failures; $e+=[int]$x.testsuite.errors
}
"tests=$t failures=$f errors=$e"
```

**Baseline a superar: `tests=150 failures=0 errors=0`.**

---

## 9. Resumen para decidir

**Si querés una app que se pueda mostrar:** pasos 1 → 7 y 8. Eso mata la mentira del producto
falso y hace que un escaneo real persista.

**Si querés la base bien construida:** los 10 pasos, en orden.

**Lo que yo NO haría todavía:** tocar OFF-4 (límites de tasa) y OFF-6 (licencias) hasta que
OFF-2 funcione. Son decisiones de diseño y no te van a bloquear una demo.

Una advertencia honesta: los pasos 1-3 son de bajo riesgo y alto valor — el test de
MockWebServer es el que te va a ahorrar el dolor de cabeza después. No lo saltees aunque
"suene a testeito", porque ese test es el que te va a decir si el `status = 0` lo estás
tratando bien.
