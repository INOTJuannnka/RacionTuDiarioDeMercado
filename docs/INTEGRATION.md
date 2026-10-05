# Referencia de integración

Material de referencia externo verificado para los dos backends con los que habla esta app.
Todo lo de acá fue contrastado contra las fuentes primarias; nada está inferido de memoria. Si
encontrás algo que lo contradiga, gana la fuente — corregí este archivo en el mismo commit.

---

## 1. Open Food Facts

### 1.1 URLs base

| Propósito                | URL                                                |
|--------------------------|----------------------------------------------------|
| Producción (mundial)     | `https://world.openfoodfacts.org/`                  |
| Test / staging           | `https://world.openfoodfacts.net/`                  |
| Robotoff (predicciones)  | `https://robotoff.openfoodfacts.org/`               |

La app usa la instancia mundial. Los paths de `OpenFoodFactsService` son relativos
(`api/v2/product/...`) y la URL base viene de
`BuildConfig.OPEN_FOOD_FACTS_BASE_URL`, así que cambiar de entorno es cambiar un campo.

### 1.2 Producto suelto — v2

```
GET https://world.openfoodfacts.org/api/v2/product/{barcode}.json
```

**El header `User-Agent` es obligatorio.** Un request sin un `User-Agent` adecuado se trata como
tráfico de bot y se bloquea. Formato requerido:

```
AppName/Version (contact)
```

Por ejemplo:

```
RacionTuDiarioDeMercado/1.0 (contact@example.com)
```

El valor se construye en `BuildConfig.OPEN_FOOD_FACTS_USER_AGENT` en `app/build.gradle.kts` y lo
adjunta el interceptor de OkHttp en `di/AppContainer.kt`. El contacto tiene que ser una dirección
real y monitoreada — los términos de la API piden una forma de localizarte, y un
`contact@example.com` de placeholder hay que reemplazarlo antes de publicar.

Pasar `?fields=` recorta la respuesta a las claves que el código lee. El objeto de producto
completo tiene decenas de claves; la app pide:

```
code,product_name,brands,quantity,serving_quantity,serving_size,categories,
ingredients_text,nutrition_grades,image_front_url,nutriments
```

### 1.3 Sobre de respuesta — encontrado vs. no encontrado

Este es el detalle de OFF más importante, y es una trampa:

**Producto encontrado → HTTP 200**
```json
{
  "code": "3017620422003",
  "status": 1,
  "status_verbose": "product found",
  "product": { "product_name": "...", "nutriments": { ... } }
}
```

**Producto NO encontrado → también HTTP 200**
```json
{
  "code": "0000000000000",
  "status": 0,
  "status_verbose": "product not found",
  "product": null
}
```

No hay 404. `status` es `1` en un acierto y `0` en un fallo, y `product` es `null` cuando no
aparece. Cualquier código que mire solo el status HTTP y después desreferencie
`body.product!!` crashea en el caso real más común: un código de barras que simplemente no está
en la base. Hay que mirar el **body**.

### 1.4 Nombres de campo en `nutriments`

Todos los valores son **por 100 g**. La nomenclatura no es consistente, y no es una errata:

| Campo de dominio | Clave JSON              | Notas                            |
|------------------|-------------------------|----------------------------------|
| energy           | `energy-kcal_100g`      | **guion** antes de `kcal`        |
| carbohydrates    | `carbohydrates_100g`    | **plural**                       |
| protein          | `proteins_100g`         | **plural**                       |
| fat              | `fat_100g`              | singular — el que se sale        |
| sugars           | `sugars_100g`           |                                  |
| fiber            | `fiber_100g`            | ortografía inglesa              |
| sodium           | `sodium_100g`           | **gramos**, no miligramos        |

El sodio en particular es fácil de errar por tres órdenes de magnitud: `sodium_100g` está en
gramos. Los valores de 100 g suelen estar presentes mientras que `sugars_100g`, `fiber_100g` y
`sodium_100g` son `null` en productos antiguos colaborativos, y por eso todos los campos del DTO
son nullable.

### 1.5 Búsqueda de texto libre — v2 no la soporta

**Open Food Facts v2 no tiene búsqueda de texto libre.** Los endpoints de búsqueda de v2 solo
hacen filtrado por facetas y etiquetas. El texto libre existe únicamente en el endpoint CGI
legacy:

```
GET https://world.openfoodfacts.org/cgi/search.pl
    ?search_terms=arepa
    &search_simple=1
    &action=process
    &json=1
    &page=1
    &page_size=20
```

Forma de la respuesta:

```json
{
  "count": 42,
  "page": 1,
  "page_size": 20,
  "products": [ { "code": "...", "product_name": "..." } ]
}
```

Por eso `OpenFoodFactsService` habla con dos generaciones distintas de la API: v2 para leer el
producto, v1 para buscar. No es un descuido, y ningún flag de v2 lo arregla.

`search_simple=1` desactiva el paso de corrector ortográfico y re-ranking: más rápido y más
predecible para un type-ahead con debounce.

### 1.6 Límites de tasa

| Endpoint            | Límite              |
|---------------------|---------------------|
| Lectura de producto (v2) | **15 requests/min** |
| Búsqueda            | **10 requests/min** |

Pasarse del límite devuelve **HTTP 503**. La app tiene que mapear eso a
`AppError.RateLimited` y mostrar un estado de "esperá un momento", no un error de servidor
genérico.

Dos consecuencias fáciles de errar:

- **El search-as-you-type está explícitamente prohibido.** El presupuesto de búsqueda es de
  ~10 requests por minuto — unas tres búsquedas. Disparar un request por tecla lo agota en
  menos de un segundo, deja la IP rate-limitada y puede hacer que bloqueen la app. Hacé
  debounce y exigí un submit explícito.
- Lecturas de producto y búsquedas tienen presupuestos **separados**, así que un loop de
  escaneo que lee un código de barras por frame es la otra forma de bloquearse rápido.
  Escaneá una vez y frená.

### 1.7 Imágenes

La imagen frontal es `image_front_url`, una URL HTTPS directa a un host de imágenes estáticas
(`images.openfoodfacts.org` y sus espejos). No hay parámetro de resize — la URL sirve un solo
tamaño, así que el downscale del lado del cliente es tarea de la app (Coil lo hace).

Las URLs de imagen son frecuentemente `null` en productos incompletos. `FoodProduct.emoji` existe
precisamente para que una fila siempre tenga algo visual, sin que el dominio dependa de la red.

### 1.8 Licencia y atribución

| Asset | Licencia | Obligación |
|-------|----------|------------|
| Datos de producto (nombre, marcas, categorías, nutriments) | **ODbL** (Open Database License) | Share-alike + atribución si redistribuís |
| Imágenes de producto | **CC-BY-SA** | Atribución + share-alike |
| La base de datos entera | ODbL | La atribución a Open Food Facts es **obligatoria** |

Obligaciones prácticas antes de publicar:

1. **La atribución es obligatoria.** "Data from Open Food Facts" con un link a
   `https://world.openfoodfacts.org` tiene que estar visible donde se muestren datos de
   producto. La app ya linkea a una política de fuentes de datos desde la pantalla de
   onboarding — ese es el lugar natural.
2. **El share-alike es real.** ODbL y CC-BY-SA exigen que las bases derivadas y
   públicamente distribuidas se liberen bajo los mismos términos. Este es el argumento más
   fuerte en contra de cachear respuestas de OFF en tu propio Firestore y después exponer eso
   como un dataset propietario.
3. **Hay que llenar el formulario de uso de la API.** OFF pide que las apps registren su uso
   para poder contactarlas por límites de tasa o cambios rompientes. Hacelo antes de publicar.
4. **Los datos de producto son colaborativos y a veces están mal.** Tratá cada valor como una
   afirmación provista por el usuario, no como un hecho. Eso es un problema de UI tanto como de
   datos.

---

## 2. Firebase Cloud Firestore

**Rol en la arquitectura (desde la Fase 2 del roadmap):** Firestore es la *proyección remota /
capa de sync*. La fuente de verdad en el dispositivo es ROOM (offline-first, requisito de
entrega). Todo lo que sigue sigue siendo cierto de Firestore en sí; lo que cambia es quién es
la autoridad. Ver §2.8.

### 2.1 Layout de colecciones que usa esta app

```
users/{uid}
  ├─ profile                 -> UserProfile fields
  ├─ goals                   -> NutritionGoals fields
  ├─ onboarding/completed    -> { completed: bool }
  └─ days/{yyyy-MM-dd}       -> { kcal, carbsG, proteinG, fatG, updatedAt }   <- running totals
     └─ entries/{entryId}    -> individual DiaryEntry
```

**Estado de implementación a 4 oct 2026.** De ese layout, `profile`, `goals` y
`onboarding/completed` están implementados y cableados (`FirestoreProfileRepository`,
`FirestoreGoalsRepository`). `days/{yyyy-MM-dd}` y `entries/{entryId}` son diseño aprobado pero
**no existen**: `FirestoreDiaryRepository` es un stub cuyos métodos `addEntry`, `deleteEntry` y
`recentScans` tiran `NotImplementedError`. ROOM sigue siendo la fuente de verdad del diario.

- El `uid` es el uid de Firebase Auth. Con auth anónimo es un id aleatorio que sobrevive
  reinicios de la app pero **no** "borrar datos de la app" ni una reinstalación.
- La clave `yyyy-MM-dd` es un `LocalDate.toString()` lexicográfico, así que `days/` ya está en
  orden cronológico y una semana es una consulta de rango simple.
- La separación `profile` / `goals` es deliberada. El last-write-wins de Firestore es por
  **documento**, no por campo, así que compartir un solo documento dejaría que un "guardar
  metas" sobrescribiera en silencio un peso que el usuario acaba de escribir.
- El flag de onboarding es un **subdocumento** porque el consentimiento tiene que poder
  escribirse solo. Un usuario que acepta el aviso y después falla al guardar su perfil igual
  tiene que quedar registrado como consentido.

### 2.2 Reglas de seguridad — deny por defecto

Nada es público. Cada regla tiene que verificar propiedad:

```
match /users/{userId} {
  allow read, write: if request.auth != null && request.auth.uid == userId;

  match /days/{dayId} {
    allow read, write: if request.auth != null && request.auth.uid == userId;
    match /entries/{entryId} {
      allow read, write: if request.auth != null && request.auth.uid == userId;
    }
  }
}
```

El deny por defecto es lo que hace que una regla faltante sea segura. Una regla que no
escribiste bloquea, no permite.

> **Hueco abierto y bloqueante para producción.** El repo **no tiene `firestore.rules` ni
> `firebase.json`**. El código ya escribe a Firestore (`saveProfile`, `saveGoals`,
> `completeOnboarding`) sin reglas versionadas que lo gobiernen. El bloque de arriba es la
> especificación prevista, no algo aplicado. Hasta que exista y esté desplegado, el estado de
> seguridad real de esos datos depende de lo que haya configurado a mano en la consola.

### 2.3 `PersistentCacheSettings` vs el `setPersistenceEnabled` deprecado

La persistencia offline está **on por defecto**. El Firestore moderno la configura mediante
`PersistentCacheSettings`:

```kotlin
val settings = firestoreSettings {
    setLocalCacheSettings(PersistentCacheSettings.newBuilder().build())
}
firestore.setFirestoreSettings(settings)
```

El `setPersistenceEnabled(true)` viejo está **deprecado** y es un no-op en versiones recientes
del SDK de Android. No lo llames — el "arreglo" que parece estar haciendo algo no está haciendo
nada.

### 2.4 La trampa: los settings van antes de cualquier otro call

`setFirestoreSettings()` **tiene que correr antes de cualquier otro call sobre esa instancia
de Firestore** — incluyendo un snapshot listener, un `get()`, o una lectura simple. Llamarlo
después tira:

```
java.lang.IllegalStateException: Firestore has already been started.
```

Esto es un fallo de **runtime**, no de compilación, así que el compilador no ayuda. Por eso
pertenece a `RacionApplication.onCreate()` y no adentro de un repositorio lazy: un repositorio
puede construirse después de que otro código ya haya tocado Firestore, y para entonces ya
tarde. Configurá una vez, al arranque, en un solo lugar.

**Lo que hace esta app, y por qué.** `RacionApplication` llama `initializeFirestoreIfReady(...)`
después de `bootstrapAnonymousSession()`, y **no** llama `setFirestoreSettings` en absoluto. Los
dos constraints apuntan en direcciones distintas y por eso la tentación es cablear ambos:

- `setFirestoreSettings` tiene que correr antes de cualquier otro call sobre la instancia.
- El sign-in anónimo tiene que correr antes de cualquier lectura, porque las reglas de
  seguridad están keyeadas en un `currentUser` que todavía no existe.

Como ningún default necesita cambiar — la persistencia offline ya está on, y
`setPersistenceEnabled` está deprecado e ignorado — la app arranca sin tocar settings y se
evita el ordenamiento por completo. Si alguna vez hace falta setearlos, el lugar sigue siendo
`RacionApplication.onCreate()` y no un repositorio.

### 2.5 Throughput de escritura: ~1 escritura/seg por documento

Firestore permite alrededor de **una escritura por segundo por documento**. Un contador muy
escrito es la forma clásica de topear ese techo y empezar a ver errores `ABORTED` /
contención.

Por eso el diseño mantiene un **total acumulado por día** y nunca reescribe un único documento
compartido de "totales". Al escribir una entrada:

1. `set` del subdocumento de la entrada — un documento *nuevo*, así que no aplica el límite
   por documento.
2. `FieldValue.increment(...)` sobre los cuatro totales en `days/{date}`, más
   `FieldValue.serverTimestamp()`.

El `increment` se aplica **del lado del servidor**, así que dos entradas registradas al mismo
instante desde dos dispositivos se combinan bien. Un read-modify-write del total perdería una
de las dos.

### 2.6 Las transacciones fallan sin conexión

Un `runTransaction { ... }` que lee y después escribe necesita que el **servidor** arbitre. Sin
conectividad tira en vez de encolarse. Esta app se usa en un mercado con señal intermitente,
así que una transacción que custodie el total del día no es una opción. Las llamadas
individuales de `set` e `increment` sí se encolan localmente y se vacían cuando vuelve la
conexión, que es el comportamiento que el diario realmente quiere.

### 2.7 Las consultas agregadas son solo de servidor

El `AggregateField.sum` de Firestore **no puede correr contra la caché local**. Ese es el
hecho que forzó todo el diseño de agregado diario:

| Enfoque                                   | Por qué no sirve acá                                |
|-------------------------------------------|-----------------------------------------------------|
| `sum` del lado del cliente sobre las entradas de la semana | Descarga todas las entradas de la semana en cada apertura de pantalla |
| `AggregateField.sum`                      | Solo servidor; inútil offline, un round trip, sin caché |
| Transacción del cliente que lleva un total | Falla offline (§2.6) y topea ~1 escritura/seg por doc (§2.5) |
| **Total acumulado por documento de día**  | Una lectura de 7 documentos chicos, `increment` combina concurrentemente |

El trade-off que acepta la última fila: borrar una entrada necesita un **decrement
compensatorio**, y el documento del día es una caché desnormalizada que un job de reparación
tiene que poder reconstruir desde la subcolección de entradas. Escribí ese job de reparación
antes del primer usuario, no después del primer reporte de bug.

### 2.8 Sync offline-first con ROOM

ROOM es la fuente de verdad en el dispositivo; Firestore es la proyección de sync/backup. Esto
es a la vez un requisito de entrega (base local + servicio online) y la forma correcta para una
app de mercado con señal intermitente.

- Los totales locales del día se calculan con `SUM` en ROOM (roadmap `DB-4`). El documento
  remoto `days/{date}` mantiene semántica de `FieldValue.increment` para que dos dispositivos
  sincronizando el mismo día combinen bien.
- El sync es idempotente sobre el id de la entrada: primero se suben las escrituras locales
  (entrada + increments) cuando vuelve la conectividad, después se bajan los cambios remotos.
  Un pull **nunca debe borrar filas locales que todavía no se subieron** — ese es el bug de
  sync que destruye datos en silencio.
- Política de conflicto: last-write-wins por entrada con server timestamps. Sin merge dentro de
  una misma entrada.
- Los totales de `days/{date}` siguen siendo una caché desnormalizada reparable desde
  `entries` — el job de reparación existe y también corre localmente desde ROOM.

---

## 3. Links rápidos

- Documentación de la API de Open Food Facts: <https://openfoodfacts.github.io/openfoodfacts-server/api/>
- Licencia de datos de Open Food Facts: <https://world.openfoodfacts.org/data>
- Límites de tasa / términos de Open Food Facts: <https://world.openfoodfacts.org/terms-of-use>
- Modelo de datos de Firestore: <https://firebase.google.com/docs/firestore/data-model>
- Persistencia offline de Firestore: <https://firebase.google.com/docs/firestore/manage-data/enable-offline>
- Consultas y agregación de Firestore: <https://firebase.google.com/docs/firestore/query-data/aggregation-queries>
- Reglas de seguridad de Firestore: <https://firebase.google.com/docs/firestore/security/rules-structure>