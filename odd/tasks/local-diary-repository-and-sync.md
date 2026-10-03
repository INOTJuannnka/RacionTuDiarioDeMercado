# Local Diary Repository + Sync — roadmap de ejecución

## Objetivo

Cerrar el gap entre "los DAOs existen" y "la app persiste". Hoy `AppContainer` expone
`authRepository` y nada más; los cuatro repositorios restantes son un bloque `TODO`. Sin un
`DiaryRepository` local, `AppNavigation.kt:203` sigue siendo `TODO(FF-6): persist through
DiaryRepository.addEntry(...)` y **la app no guarda nada**.

## Problema

- `RationDatabase` v1 existe con 4 entities y 4 DAOs probados (91 tests verdes).
- `DiaryRepository` (dominio) está declarado y **nadie lo implementa**. `FirestoreDiaryRepository`
  lanza `NotImplementedError`, que es un `Error` y no un `Exception`: un `catch (e: Exception)`
  en un call site no lo atrapa.
- Room no se inicializa en ningún lado.
- No hay sync: `DB-7` sin empezar.

## Por qué el orden es este

El orden lo fija una dependencia dura, no una preferencia: **la implementación local es el
prerrequisito del sync**, porque el outbox se encola *desde* el write path local. Implementar
DB-7 sin `LocalDiaryRepository` significa inventar un segundo write path que después se borra.

## Alcance

### Slice 1 — Desbloquear a Brayan (HECHO)
`com.squareup.okhttp3:mockwebserver` en el catálogo + `testImplementation`. Verificado en
`debugUnitTestRuntimeClasspath`.

Archivos: `gradle/libs.versions.toml`, `app/build.gradle.kts`.

### Slice 2 — `LocalDiaryRepository` + wiring (HECHO)
Que la app persista de verdad. Sin cambio de schema: es puramente mapping + agregación + DI.

- Proyección Room que une `diary_entries` con `food_products` (el `DiaryEntry` de dominio
  necesita `product.name`, y `DiaryEntryEntity` no lo tiene — su KDoc ya avisa que ese join es
  "a mapping layer, not here").
- Mappers entity → dominio.
- `observeDay` / `observeWeek` con los tres labels en español que los modelos prometen
  (`dateLabel` "Mié 25 Ago", `weekRangeLabel` "19–25 AGO", `bestDayLabel` una letra).
- `macroSplit` en porcentajes enteros; `activeStreakDays`; `days` siempre con 7 barras.
- `combine` de entries + totales `SUM` + `goalKcal` de `GoalsDao`.
- Garantía de los contratos: **nunca completa, nunca tira**; write sin red se acepta local.
- Exponer en `AppContainer` (archivo de Julian por Sprint-1 §1.1).

Archivos: `data/local/DiaryEntryWithProduct.kt`, `data/local/LocalDiaryRepository.kt`,
`data/local/dao/DiaryDao.kt` (4 queries nuevas), `di/AppContainer.kt`,
`test/.../LocalDiaryRepositoryTest.kt`.

Verificado: 26 tests nuevos verdes, suite completa **117 tests / 0 fallos / 0 errores / 0
skipped** (91 baseline + 26), `assembleDebug` BUILD SUCCESSFUL.

### Slice 3 — DB-7 `DiarySyncManager` (HECHO salvo el lado remoto)
- Tabla `sync_outbox` + entity + DAO. **HECHO.**
- **Migración v1 → v2** con su `Migration` object y testeada con `MigrationTestHelper` contra el
  `1.json` commiteado. **HECHO.**
- `SyncTransport` como seam inyectable (el lado Firestore se implementa aparte). **Interfaz HECHA;
  la implementación Firestore NO.**
- Drenaje idempotente sobre `entryId`. **HECHO.**

Archivos: `data/local/entity/SyncOutboxEntity.kt`, `data/local/dao/SyncOutboxDao.kt`,
`data/local/RacionDatabase.kt` (v2 + `MIGRATION_1_2`), `data/sync/SyncTransport.kt`,
`data/sync/DiarySyncManager.kt`, `data/local/LocalDiaryRepository.kt` (enqueue transaccional),
`data/local/dao/DiaryDao.kt` (`withProductById`), `di/AppContainer.kt`, `app/build.gradle.kts`
(assets de schemas), 4 archivos de test nuevos.

`app/schemas/.../2.json` exportado, `identityHash efdeb8232e08b1fb2ef095bbe6d86c37`.

Verificado: suite completa **150 tests / 0 fallos / 0 errores / 0 skipped** (117 → 150),
`assembleDebug` BUILD SUCCESSFUL.

**Lo que DB-7 todavía NO es:** el `SyncTransport` real contra Firestore (`pushAdd`/`increment`,
`pushDelete`/`decrement`), el pull de cambios remotos a ROOM (DB-8), y el scheduler que llama a
`drain()`. Sin el transporte real el `DiarySyncManager` no tiene a quién empujar; la bandeja
funciona pero el sync no llega a ninguna parte. Por eso DB-7 sigue **sin marcar** en el roadmap.

## Fuera de alcance (y por qué)

- **`AppNavigation.kt` / `MainActivity.kt` / `PreviewData.kt`**: UI y navegación son de Daniel.
  El `TODO(FF-6)` de `AppNavigation:203` se queda; cablear la pantalla es su tarea o una
  coordinación posterior.
- **`RacionApplication.kt`**: es de Daniel (FF-2 inicializa Firebase ahí). Room se inicializa
  desde `AppContainer`, que es de Julian y ya recibe el `Context`. Así no se cruzan los owners.
- **`FirestoreDiaryRepository`**: se implementa después del sync, y no en este lote.

## Decisiones de diseño (tomadas, no pendientes)

### D1 — El write path es local-first, sin excepciones
`addEntry` escribe en Room y devuelve éxito. Nunca espera la red. El roadmap lo fija
("toda escritura va primero a la base local") y el contrato de `DiaryRepository` lo repite
("a write that cannot reach the network must still be accepted locally (queued), not rejected").
Un repository que rechaza la escritura porque no hay señal le está denying al usuario el
único momento en que la app funciona.

### D2 — El outbox es una tabla propia, no una columna dirty-flag
El roadmap permite cualquiera de las dos. La tabla propia gana por una razón concreta: **el
delete necesita los valores de nutrición de la fila borrada** para aplicar el `decrement`
compensatorio en Firestore. Con hard-delete esos valores ya no están; con soft-delete la
tombstone sigue sujetando la FK `RESTRICT` y bloquea el prune de la caché para siempre. Una
fila de outbox guarda op + payload y deja `diary_entries` con su semántica actual — los 19
tests de `DiaryDaoTest` conservan su significado sin reinterpretarlos.

### D3 — La migración v2 es el deliverable oculto de DB-7
DB-6 pide "estrategia de migración desde el primer día" y la v1 no tenía `Migration` object
porque no hay predecesor. Agregar el outbox **obliga** a escribir la primera migración real, y
eso es exactamente lo que DB-6 quería probar: que la fontanería funciona antes de necesitarla
en producción, no durante un incidente.

### D4 — `activeStreakDays` se calcula dentro de la semana pedida, anclado en el último día con datos
El KDoc de `FirestoreDiaryRepository` dice "walking backwards from the most recent day that has
any entry" sin acotar la ventana. Para un *informe semanal* la ventana es la semana: un streak
que cruza el lunes arranca en un día que la pantalla ni muestra.

**Corregido durante la implementación:** la primera versión contaba hacia atrás desde el
**domingo** de la semana y devolvía 0 en cuanto el domingo estaba vacío. Eso convierte una semana
pasada normal — logged de lunes a miércoles y nada más — en "0 días activos". El ancla es el
último día *con entradas*: da los 3 que el usuario se ganó, y para la semana en curso se trunca
sola en hoy porque hoy es el último día que puede tener entradas. Un hueco antes del ancla sigue
cortando la racha (Lun/Mar, Jue/Vie = 2, no 4). Test:
`observeWeek counts the streak back from the last logged day`.

### D6 — `addEntry` escribe producto + entrada en una transacción
`diary_entries.productBarcode` es FK `RESTRICT`. Una implementación que solo upsertea la entrada
tira `SQLiteConstraintException` para cada producto que el teléfono no tenía cacheado — o sea,
para casi todo lo que el usuario escanea. No es un edge case: es el camino común. El test
`addEntry writes the product row the foreign key requires` no presiembra el catálogo justamente
para que muerda.

### D7 — Se quitó el punto final de las abreviaturas CLDR
CLDR devuelve "mar." / "ago." / "sept." en español. En pantalla eso renderiza "Mar. 25 Ago." y
—uppercased— "19–25 AGO.", donde el punto final se lee como typo. Se quita el sufijo `"."` en
la mapping layer, no cambiando locale ni patrón, para no perder la forma puntuada por si algún
día hace falta.

### D8 — `AppContainer` sin `fallbackToDestructiveMigrationOnDowngrade()`
Se consideró y se rechazó. Existe para un test que abre una v1 como v2 sin `Migration`; meter un
`DROP` en el wiring de producción para acomodar un test hipotético invierte la prioridad, en un
codebase donde `RacionDatabase` ya declaró la migración destructiva como bug. El test de DB-7
construye su propia base contra el `1.json` commiteado y no necesita ayuda de acá. Cuando la
`MIGRATION_1_2` exista, este builder gana `.addMigrations(...)` — y es el único cambio de DB-7
sobre este archivo.

## Desviaciones y notas honestas

- **Orden implementation-first en el Slice 2.** No se observed RED para
  `LocalDiaryRepository`: la continuación de sesión ya traía el diseño cerrado y se escribió la
  implementación antes que los tests. Los 26 tests se escribieron después contra la
  implementación, así que su valor como detección de errores de diseño es menor que si hubieran
  fallado primero. Queda compensado por los tres casos donde el test **sí** encontró defectos:
  la racha anclada en domingo (D4), y dos expectativas mías que resultaron wrong por arithmetic y
  por CLDR ("sep" vs "sept") — esas correcciones fueron al test, no al código.
- El `catch` de los flujos cumple "nunca tira" pero **no** "nunca completa": emite el fallback y
  después termina. El KDoc lo dice explícitamente en vez de prometer algo que no cumple. En la
  práctica `InvalidationTracker` de Room no falla una query como falla una lectura de red.

### D5 — `SyncTransport` es un seam, no Firebase
El sync se testea en JVM sin emulator ni red. `DiarySyncManager` depende de una interfaz; la
implementación Firestore se inyecta después. Un `DiarySyncManager` que llama a
`FirebaseFirestore.getInstance()` por dentro es intestable y por eso no se escribe ahora.

## Criterios de aceptación

- `assembleDebug` y `testDebugUnitTest --rerun` en verde.
- Cero tests salteados. Baseline de 91 no baja.
- `app/schemas/` incluye la v2 y la v1 sigue commiteada como línea base.
- Ningún archivo de Daniel ni de Brayan tocado.
- Nada commiteado: la revisión del usuario precede al commit.

### D9 — La migración v2 se testea con `MigrationTestHelper` leyendo la v1 real, y eso cuesta los schemas en el APK de debug
Los cuatro constructores de `MigrationTestHelper` en Room 2.8.5 cargan el schema vía
`context.assets.open("$assetsFolder/$version.json")`; el parámetro `File` del constructor con
driver es **la base**, no el directorio de schemas. Y Robolectric 4.17 no tiene opción `assets` en
`@Config`: su directorio de assets sale sólo de `android_merged_assets`, que AGP escribe apuntando
al merge de la **variante**. Medido: `sourceSets { test { assets } }` compila y no hace nada; con
`debug` el archivo aparece. Ése es el motivo de la línea de `sourceSets` en `app/build.gradle.kts`.

Es un costo real y asumido a conciencia: los dos JSON de schema viajan en el APK de debug (~30 KB).
La alternativa — leer el `1.json` del disco a mano — no empaqueta nada **y no valida nada**: se
pierde la validación de schema de Room contra la v2, que es justo lo que D3 pedía demostrar.

Efecto secundario descubriéndolo: `runMigrationsAndValidate` de Room **no verifica la identidad de
los índices**. Una sonda de mutación renombró el índice y la suite siguió verde; retipar una columna
sí la rompe. El índice de la cola tiene su propia aserción contra `sqlite_master`, que en su
primera versión devolvió `sqlite_autoindex_sync_outbox_1` en vez del índice nombrado.

### D10 — `deleteEntry` lee la fila antes de borrarla, y por eso existe `DiaryDao.withProductById`
`DiaryRepository.deleteEntry(entryId)` sólo recibe un id, pero D2 exige que la fila de outbox
lleve la nutrición de la fila borrada para aplicar el `decrement` compensatorio. El repositorio
entonces tiene que **leer** la entrada — y unir `food_products` para la identidad del producto —
antes de borrar, y hacerlo en la misma transacción que el delete y el enqueue.

`DiaryDao` no tenía ninguna lectura por id: todas iban por `dayKey` o por "N recientes". Por eso
la query nueva trae la proyección joined completa, no un recorte.

### D11 — El coalescing del outbox es la PK, no una regla en Kotlin
`sync_outbox` usa `entryId` como PRIMARY KEY y `@Upsert`. Eso colapsa UPSERT→UPSERT,
DELETE→DELETE y UPSERT→DELETE (⇒ DELETE, la última escritura gana) en **un** statement atómico. La
alternativa — leer y decidir en Kotlin — es un check-then-insert con race entre dos escrituras
concurrentes de la misma entrada, y el outbox es exactamente el lugar donde esa race produce una
entrada subida dos veces o un delete perdido.

Sin FK hacia `diary_entries`: una fila DELETE se encola para una entrada que ya no existe, y una
FK la rechazaría.

### D12 — `LocalDiaryRepository` recibe `now: () -> Long`
Sin un reloj inyectado, "oldest pending first" sólo se puede probar por suerte de milisegundos. Con
el reloj inyectado el orden del drenaje es una aserción, no una lotería.

## Estado final de la tarea

- Slice 1: **HECHO** — `mockwebserver:4.12.0` en `debugUnitTestRuntimeClasspath`.
- Slice 2: **HECHO** — `assembleDebug` verde.
- Slice 3: **HECHO salvo el lado remoto** — bandeja, migración y drenaje implementados y testeados.

Suite completa: **150 tests / 0 fallos / 0 errores / 0 skipped** (91 → 117 → 150).
Nada commiteado: HEAD sigue en `3a94710`.

## Próximo paso

La revisión del usuario precede al commit. Después de commitear, la continuación natural de esta
misma línea es `SyncTransport` contra Firestore (`pushAdd`/`increment`, `pushDelete`/`decrement`) y
el pull de DB-8, que es lo que le falta a DB-7 para poder marcarse.
