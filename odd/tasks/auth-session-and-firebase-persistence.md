# Sesión real + persistencia Firestore de perfil y metas

## Objetivo

Tres cosas que el usuario reportó en la misma sesión:

1. Al entrar con Google desde una cuenta anónima a una cuenta **ya existente**, la app queda
   clavada en "creando sesión" y al volver atrás lo manda a otra cuenta anónima.
2. Que al iniciar sesión lo mande **a su sesión**, no a un limbo.
3. Que las **configs y objetivos del usuario** se guarden en Firebase.
4. Que el ingreso desde cuenta anónima use el login de **fondo oscuro**.

## Problema

### 1. El login se cuelga — causa raíz confirmada

`AppNavigation.kt:267` y `:322` construyen el ViewModel así:

```kotlin
val viewModel: LoginViewModel = remember { LoginViewModel(authRepository, container.sessionDataReassigner) }
```

`Routes.CUENTA` en la línea 370 sí usa `viewModel(factory = viewModelFactory { ... })`. Esos dos
se quedaron con `remember` de una depuración y nunca se corrigieron.

`remember` scopea el ViewModel a la **composición**, no al `NavBackStackEntry`. Al navegar, la
composición se destruye y se reconstruye un ViewModel nuevo. `pendingGoogleIdToken` es un **campo**
del ViewModel (`LoginViewModel.kt:223` lo escribe, `:244` lo lee), así que:

- `onGoogleMergeConfirmed()` entra por el `?: return` de la línea 244 → **no-op silencioso**. El
  diálogo se cierra, no ocurre nada, y el usuario queda esperando.
- El `viewModelScope` del ViewModel destruido se cancela, así que un `signInWithGoogle` en vuelo
  nunca completa su `_uiState.update { isGoogleInProgress = false }` → el flag queda congelado y la
  pantalla muestra el estado de carga para siempre.

El "vuelve a otra cuenta anónima" es la otra cara: `RacionApplication.kt:117` llama
`signInAnonymously()` en cada arranque, y si `currentUser` quedó en `null` Firebase mina un `uid`
anónimo **nuevo**.

### 2. No existe patrón Firestore: todo es stub

`FirestoreProfileRepository` (4 métodos), `FirestoreGoalsRepository` (2) y `FirestoreDiaryRepository`
(5) hacen `throw NotImplementedError`. No hay ni una lectura ni una escritura real. La ruta de
documentos ya está documentada en el KDoc de cada uno (`users/{uid}/profile`, `users/{uid}/goals`,
`users/{uid}/onboarding/completed`), así que la intención está; falta el código.

`AppContainer.kt:233-234` ya tiene escrito el cableado esperado, comentado.

### 3. La tarjeta oscura se invierte a crema

`AuthHeroBlock` usa `inverseSurface` + `inverseOnSurface`. En tema claro eso da `#1C1A16` con texto
crema — correcto. En tema oscuro los roles M3 se invierten y la tarjeta queda **crema con texto
oscuro**. El usuario pidió fondo oscuro siempre.

## Decisiones

**D1 — `viewModel(factory = ...)`, no `remember`.** Es lo que ya hace `Routes.CUENTA` en la misma
función. No es una preferencia de estilo: con `remember` el `idToken` pendiente se pierde y el
camino de cuenta existente es literalmente inalcanzable.

**D2 — Los colores del hero se salen del esquema M3.** No se pueden usar `inverseSurface`/`inverseOnSurface`
porque la semántica M3 de "inverse" es justo lo que hay que anular. Se agregan en `Color.kt` un
par de colores por esquema y `Theme.kt` los publica por `CompositionLocal`..light mode queda
**exactamente igual** (`#1C1A16` + crema) para no cambiar lo que ya está bien; dark mode pasa de
crema a un oscuro elevado (`#332E26`), que es legible sobre el fondo oscuro `#1C1A16`. Un solo valor
fijo no sirve: sobre crema tiene que ser claramente oscuro, y sobre `#1C1A16` tiene que ser
claramente *más claro*, y un valor no puede ser ambas cosas.

**D3 — `currentUid: () -> String?` en vez de `AuthRepository`.** Los repos de Firestore
necesitan el `uid` y nada más de auth. Una lambda es más angosta que la interfaz completa y no los
ata a una superficie que no usan. `AppContainer` los arma como
`FirestoreGoalsRepository(firestore = { FirebaseFirestore.getInstance() }, currentUid = { authRepository.currentUid })`.

**Corregido durante la implementación:** los dos parámetros son **obligatorios, sin defaults**. El
draft inicial les ponía default (`= { FirebaseAuth.getInstance().currentUser?.uid }`) y eso le daba
a cada repositorio su propia respuesta a "¿quién es el usuario?", en paralelo a la del container.
Sin default, `AppContainer` queda como el único lugar que decide de dónde sale la sesión, que es
justo lo que se pidió al evitar el `AuthRepository` completo.

Lo mismo aplica a `firestore`: es un provider, no el handle. Pasar `FirebaseFirestore.getInstance()`
en el constructor sube el bug de ordenamiento un nivel — el container resolvería la instancia
mientras se construye la UI, y eso tira `IllegalStateException` si `FirebaseApp` no está listo.
Es la misma lección que `FirebaseAuthRepository` ya aprendió (ver el KDoc de `authRepository`).

**D4 — El mapeo documento↔dominio va en funciones puras y testeables.** Acá está el error que
duele: `SportFocus` y `DayOfWeek` se persisten por **nombre**, no por ordinal ni por etiqueta de UI.
Un mapeo mal hecho no rompe el build, rompe los datos guardados. Es la única parte con resultado
esperado claro, así que es la única con test determinista.

**D5 — La llamada a Firestore no se testea en JVM, y el documento lo dice.** No hay emulador
configurado, no hay mockito, y `kotlinx-coroutines-play-services` no está en el catálogo
(`awaitTask` está hecho a mano en `data/firebase/TaskAwait.kt`). Agregar un emulador es una tarea
propia. Se cubre lo que se puede: el mapeo tiene test, y el resto se verifica por compilación
y lectura estructural. Se declara explícitamente como no verificado en runtime.

**Corregido durante la implementación — D5 se partió en dos suites, y el motivo es una trampa real.**
El mapeo de errores *no* se puede testear en JVM pelado, y no por falta de emulador:
`FirebaseFirestoreException.Code` construye su `SparseArray` de estados en un inicializador estático
que llama `android.util.SparseArray.get`. En JVM eso tira
`RuntimeException: Method get in android.util.SparseArray not mocked`, que se manifiesta como
`ExceptionInInitializerError` — un error que *se lee* como un bug de Firestore y es un runtime de
Android faltando.

Entonces:

- `FirestoreMappingTest` — mapeo puro, **sin Robolectric**, tier rápido. No lleva ni un import del SDK.
- `FirestoreErrorMappingTest` — traducción de códigos, **con `@RunWith(RobolectricTestRunner::class)`**,
  siguiendo las 7 suites de DAO que el proyecto ya corre así.

Los dos archivos no se fusionan. El mapeo puro no necesita Android, y mezclarlo obligaría a pagar
Robolectric en 21 tests que no lo necesitan.

**D6 — `awaitTask` se comparte.** Está `private` en `FirebaseAuthRepository` y lo usan 8 call sites
de ese archivo. Los repos nuevos necesitan el mismo puente `Task` → `suspend`, así que se mueve a
`data/firebase/TaskAwait.kt` como `internal` en vez de copiarlo tres veces.

**D7 — El mapeo va en su propio archivo, no adentro del repositorio.** `FirestoreMapping.kt` no
importa nada de Firebase salvo el enum de errores. Cada repositorio queda thin: resuelve la
referencia, entrega el snapshot, traduce el fallo. El motivo es que el repositorio **no se puede
testear** (no hay emulador) pero el mapeo **sí**, y separarlos es lo que hace posible tener 27 tests
en vez de 0.

**D8 — Sin sesión es un valor, no una excepción.** Cuando no hay `uid`:
`observeGoals` emite defaults y **completa**; `observeProfile` emite `null`; `observeOnboardingCompleted`
emite `false`; las escrituras devuelven `AppResult.Failure`. Completar el flow es correcto acá y en
el resto no: estos tres flujos representan un valor que *no depende de una red que pueda reconectar*
—sin sesión no va a cambiar sin que el usuario actué— y un `Flow` que nunca completa mantiene vivo
un listener para siempre. La restricción "no completan" de la sección de abajo se lee como "no
completan **por un error**"; esta es la excepción y está anotada en el código.

El `Failure` usa `AppError.Server(code = null, message = ...)`. La taxonomía de `AppResult` no tiene
un bucket `Unauthenticated`, y las alternativas eran tomar `AppError.Network` — actively
engañoso, porque la causa común es una security rule o un sign-in anónimo fallido, no una conexión
muerta— o agregar una variante a un `sealed interface` compartido, que rompe todos los `when`
exhaustivos de la app. Queda como follow-up declarado, no metido de contrabando acá.

**D9 — La instancia de Firestore se resuelve una vez en `RacionApplication`, después del sign-in
anónimo.** `setFirestoreSettings()` tiene que correr antes de *cualquier* otro call sobre la
instancia, y `bootstrapAnonymousSession()` tiene que correr antes que cualquier lectura de Firestore
porque las security rules están keyeadas en un `currentUser` que todavía no existe. Los dos
constraints apuntan en direcciones distintas y `initializeFirestoreIfReady` es donde convergen.

No se llama `setFirestoreSettings(...)` porque ningún default necesita cambiar: la persistencia
offline ya está on vía `PersistentCacheSettings`, y `setPersistenceEnabled()` está deprecado e
ignorado. Agregar esa llamada "por completitud" sería código muerto que aparenta configuración.

Los repositorios siguen llamando `getInstance()` lazy. No es una segunda instancia —Firebase es
dueño del singleton— es la misma lección de `FirebaseAuthRepository`: el eager de acá arregla el
**orden**, el lazy mantiene el **fallo** pegado al call que lo necesita.

**D10 — Las escrituras de las pantallas son fire-and-forget, y el fallo no se reporta todavía.**
`MetasScreen.onSave` y `PerfilDeportivoScreen.onContinue` lanzan el write y navegan de inmediato.
Bloquear la navegación en un round-trip de red dejaría al usuario clavado en la pantalla esperando
señal, y los datos ya están en el objeto local que la pantalla cargó.

El costo real: **una escritura caída es invisible**, porque este grafo no tiene seam de snackbar.
Eso no es un descuido, es una limitación marcada en el código con el lugar exacto donde tiene que
aterrizar el seam. Agregar un toast o un `SnackbarHostState` acá sería alcance nuevo y no hay
decisión de producto sobre cuál de los dos.

**D11 — `snapshot.getData()`, no `snapshot.data()`.** Kotlin resuelve el nombre pelado `data` al
`kotlin.data` de la stdlib (un `DeepRecursiveFunction`), que no es un método del snapshot. Compila
a un tipo `R` y el error aparece como `Argument type mismatch`, muy lejos de la causa. Va anotado
en los tres call sites.


## Restricciones

- Los `Flow` de observabilidad **no lanzan y no completan**: una lectura fallida es una emisión
  con el valor por defecto (`null` para perfil, defaults para metas, `false` para onboarding).
- No tocar el schema de ROOM ni `SessionDataReassigner`.
- No agregar dependencias.
- Los literales hexadecimales viven en `Color.kt`, nunca en `AppComponents.kt` ni en las pantallas.
- El copy de usuario va en español rioplatense; identificadores, KDoc y comentarios en inglés.

## Alcance autorizado

```
app/src/main/java/com/racion/diariomercado/ui/navigation/AppNavigation.kt
app/src/main/java/com/racion/diariomercado/ui/theme/Color.kt
app/src/main/java/com/racion/diariomercado/ui/theme/Theme.kt
app/src/main/java/com/racion/diariomercado/ui/components/AppComponents.kt
app/src/main/java/com/racion/diariomercado/data/firebase/TaskAwait.kt            (nuevo)
app/src/main/java/com/racion/diariomercado/data/firebase/FirestoreMapping.kt      (nuevo, D7)
app/src/main/java/com/racion/diariomercado/data/firebase/FirestoreCalls.kt        (nuevo, D7)
app/src/main/java/com/racion/diariomercado/data/firebase/FirestoreProfileRepository.kt
app/src/main/java/com/racion/diariomercado/data/firebase/FirestoreGoalsRepository.kt
app/src/main/java/com/racion/diariomercado/data/firebase/FirebaseAuthRepository.kt
app/src/main/java/com/racion/diariomercado/di/AppContainer.kt
app/src/main/java/com/racion/diariomercado/RacionApplication.kt
app/src/test/java/com/racion/diariomercado/data/firebase/FirestoreMappingTest.kt        (nuevo, D5)
app/src/test/java/com/racion/diariomercado/data/firebase/FirestoreErrorMappingTest.kt   (nuevo, D5)
odd/tasks/auth-session-and-firebase-persistence.md                                 (este archivo)
```

`FirestoreMapping.kt` y `FirestoreCalls.kt` no estaban en el alcance original y no se anticiparon:
aparecieron cuando se entendió que el repositorio no es testeable y el mapeo sí (D7), que es
justamente la razón por la que hay 27 tests en este bloque. `FirestoreCalls.kt` existe para que
`runFirestoreWrite` y la falla de "sin sesión" no se dupliquen entre los dos repos — son contrato de
`AppResult`, no de un repositorio.

El nombre que el draft decía, `FirestoreDocumentMappingTest.kt`, se partió en dos suites por D5.

## Tareas

- [x] **T0 — Verificar la causa raíz antes de escribir.** `pendingGoogleIdToken` es campo del
  ViewModel; `Routes.CUENTA` ya usa la factory correcta. Evidencia en `## Verificación`.
- [x] **T1 — Scoping del ViewModel.** `remember` → `viewModel(factory = ...)` en LOGIN y REGISTRO.
  Verificado leyendo `AppNavigation.kt:327` y `:388`, y que `LoginViewModel.kt:255` pone
  `isLoggedIn = true` en el success de `onGoogleMergeConfirmed`, que es lo que dispara el
  `LaunchedEffect` de `AppNavigation.kt:358`.
- [x] **T2 — Hero siempre oscuro.** Colores por esquema en `Color.kt`, `CompositionLocal` en
  `Theme.kt`, `AuthHeroBlock` deja de invertir.
- [x] **T3 — `awaitTask` compartido.** `private` en `FirebaseAuthRepository` → `internal` en
  `TaskAwait.kt`. Los tres imports de coroutines que quedaron sin uso se eliminaron y el import de
  `Task` se conserva a propósito: el KDoc de la clase lo linkea.
- [x] **T4 — `FirestoreGoalsRepository` real.** 2 métodos, mapeo puro testeado, `snapshots()` +
  `catch` + `distinctUntilChanged`.
- [x] **T5 — `FirestoreProfileRepository` real.** 4 métodos, mapeo puro testeado, mismo shape.
- [x] **T6 — Cableado.** `initializeFirestoreIfReady` en `RacionApplication`,
  `goalsRepository` / `profileRepository` en `AppContainer`, y `MetasScreen.onSave` /
  `PerfilDeportivoScreen.onContinue` dejan de ser TODOs.
- [x] **T7 — Verificación.** RED antes de GREEN en el mapeo, suite completa, `assembleDebug`.

### Ruta elegida por tarea

T0-T3, T6 y T7 se hicieron **inline**: son 1-3 archivos ya entendidos por la exploración previa, sin
decisión de diseño abierta. T4 y T5 son 2 archivos no triviales cada uno, cada uno tocando dominio
+ datos + tests, así que dispararon el writer trigger y cada uno se hizo como una unidad coherente
mapeo + repositorio + sus tests. T7 es un per-action worker: los checks corren frescos, sin cambiar
la ruta de implementación.

## Verificación

**RED observado antes de GREEN**, las dos veces, y no un RED inventado:

| Momento | Comando | Resultado |
| --- | --- | --- |
| Mapeo, antes de implementar | `:app:testDebugUnitTest --tests "*FirestoreMappingTest*"` | `compileDebugUnitTestKotlin FAILED` — `Unresolved reference 'goalsFromDocument'` ×8 y `toGoalsDocument` ×3 |
| Mapeo, después | mismo | `BUILD SUCCESSFUL` |
| Errores, primer intento | `:app:testDebugUnitTest --tests "*FirestoreMappingTest*"` | `tests completed, 4 failed` — `ExceptionInInitializerError` |
| Errores, causa raíz | lectura del XML | `Caused by: RuntimeException: Method get in android.util.SparseArray not mocked` at `FirebaseFirestoreException$Code.buildStatusList` |
| Errores, con Robolectric | `:app:testDebugUnitTest --tests "*Firestore*"` | `BUILD SUCCESSFUL` |

**Suite completa y APK:**

```
:app:testDebugUnitTest   ->  BUILD SUCCESSFUL
:app:assembleDebug       ->  BUILD SUCCESSFUL
tests=216 failures=0 errors=0
```

**Baseline 189 → 216.** Los 27 tests nuevos son exactamente los dos archivos nuevos:

```
FirestoreMappingTest:        tests=21 failures=0 errors=0   (sin Robolectric)
FirestoreErrorMappingTest:   tests=6  failures=0 errors=0   (con Robolectric)
```

Ninguna suite preexistente cambió de conteo: los 189 siguen siendo 189.

**Criterios de aceptación, uno por uno:**

| Criterio | Estado | Cómo se verificó |
| --- | --- | --- |
| Confirmar "Entrar a esa cuenta" completa el ingreso | cubierto por T1 | `AppNavigation.kt:327` usa la factory; `LoginViewModel.kt:244` lee el token del **campo** del ViewModel, que ahora sobrevive la recomposición |
| Navega a `INICIO` | verificado por lectura | `LoginViewModel.kt:255` → `isLoggedIn = true` → `LaunchedEffect` en `AppNavigation.kt:358`. El `LaunchedEffect` ya existía; lo que faltaba era el estado al que reacciona |
| El flag de carga no queda prendido | cubierto por T1 | el `viewModelScope` del ViewModel viejo se cancelaba con la composición; con la factory pertenece al `NavBackStackEntry` |
| `saveGoals` escribe `users/{uid}/goals` | compilación + lectura | `FirestoreGoalsRepository.goalsReference()` |
| `saveProfile` escribe con `sportFocus` por **nombre** | **test** | `FirestoreMappingTest`: `active days are persisted by enum name`, `sport focus is persisted by enum name` |
| `completeOnboarding` independiente de `saveProfile` | compilación + lectura | dos `DocumentReference`s distintas; el write de onboarding escribe un solo campo |
| Documento ausente: `null` / defaults / `false` | **test** | 3 tests dedicados, uno por cada valor por defecto |
| Ningún observador lanza | **test** para los defaults, lectura para `.catch` | el `catch` está en los tres; sin emulador no hay test de excepción real |
| El hero se ve oscuro en claro **y** en oscuro | compilación + lectura | `AuthHeroBlock` ya no referencia `inverseSurface` |
| Suite y APK verdes | **ejecutado** | ver arriba |

**Lo que NO está verificado, sin adornos:** ninguna escritura se probó contra Firestore real. No
hay emulador, no hay mockito, y `observeGoals` / `observeProfile` no se ejecutaron nunca contra un
`DocumentSnapshot`. Lo que sí se ejecutó es el mapeo que decide qué se guarda y cómo se lee. La
conexión entre ambos —que el `getData()` que llega tenga la forma que el mapeo espera— está
verificada por tipos y por lectura, no en runtime.


## Criterios de aceptación

- Con sesión anónima y cuenta Google existente: confirmar "Entrar a esa cuenta" **completa** el
  ingreso y navega a `INICIO`. Hoy eso es un no-op silencioso.
- Un login que se completa no deja al usuario con el flag de carga prendido.
- `saveGoals` escribe `users/{uid}/goals`; un observador posterior lo lee sin write-back.
- `saveProfile` escribe `users/{uid}/profile` con `sportFocus` por **nombre**.
- `completeOnboarding` es independiente de `saveProfile`: un write de perfil fallido no
  des-consienta al usuario.
- Documento ausente: perfil emite `null`, metas emiten defaults, onboarding emite `false`.
- Ningún método observador lanza ni completa.
- El hero se ve oscuro con el sistema en claro **y** en oscuro.
- `:app:testDebugUnitTest` verde y `:app:assembleDebug` verde.

## Checks

```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :app:testDebugUnitTest --console=plain
.\gradlew.bat :app:assembleDebug --console=plain

# conteo real
$d="app\build\test-results\testDebugUnitTest"
$t=0;$f=0;$e=0
Get-ChildItem $d -Filter "TEST-*.xml" | ForEach-Object {
  $x=[xml][System.IO.File]::ReadAllText($_.FullName)
  $t+=[int]$x.testsuite.tests; $f+=[int]$x.testsuite.failures; $e+=[int]$x.testsuite.errors
}
"tests=$t failures=$f errors=$e"
```

**Baseline: `tests=189 failures=0 errors=0`.**

## Política de tests (aplicabilidad)

**Aplicable** solo al mapeo documento↔dominio (D4): es determinista, no toca Firebase y hay
resultado esperado claro. RED antes de GREEN.

**No aplicable** a las llamadas a Firestore: no hay emulador, no hay mockito, y `FirebaseFirestore`
no se puede construir en un test JVM. La excepción queda declarada, no disimulada. La verificación
de esa parte es compilación + lectura estructural.

## Pendiente conocido (no en este bloque)

- **`FirestoreDiaryRepository` sigue siendo stub (FF-6).** Es el diario, y por diseño el diario va
  primero a ROOM: `diaryRepository` ya está cableado a `LocalDiaryRepository`. Firestore entra como
  proyección de sync detrás de `SyncTransport`, no como primer destino.
- **Sin emulador de Firestore.** Las escrituras no se verificaron contra el backend real, y los
  flujos observadores nunca corrieron contra un `DocumentSnapshot`. Bloqueado por D5.
- **Una escritura caída es invisible** (D10). `saveGoals` y `saveProfile` devuelven un `AppResult`
  que nadie mira: no hay snackbar ni toast en el grafo. El lugar exacto donde tiene que aterrizar el
  seam está anotado en los dos call sites de `AppNavigation`.
- **`AppError` no tiene un bucket `Unauthenticated`** (D8). "Sin sesión" se reporta como
  `AppError.Server(code = null, message = ...)`. Agregar la variante es un cambio chico pero toca un
  `sealed interface` compartido y todos los `when` exhaustivos de la app — no se metió de
  contrabando en este bloque.
- **Los `Flow` observadores completan cuando no hay sesión** (D8). Es intencional y está anotado,
  pero es una excepción a la restricción de `## Restricciones` y conviene revisarla cuando exista
  el seam de reintento.
- **`MainActivity` sigue leyendo el booleano de onboarding por `SharedPreferences`** en vez de
  `observeOnboardingCompleted()`. Ahora existe el repo, pero el gate de arranque (FF-7) es otro
  bloque.
- **La fila de `MetasScreen` dice "Cuenta · Iniciar sesión" para cualquier sesión** (FF-4,
  `MetasScreen.kt:162`). El destino hace lo correcto —`Routes.CUENTA` decide entre las tres
  ramas— pero la etiqueta sobre-promete para un usuario con sesión, y el `onOpenLogin` de
  `AppNavigation` es siempre no-nulo, así que la fila nunca se oculta.
- **`observeProfile` y `observeGoals` no tienen consumidor.** Los repositorios existen y están
  cableados en `AppContainer`, pero las pantallas siguen mostrando `PreviewData`. Que la pantalla
  *muestre* lo persistido es el bloque siguiente; este entrega el lado de escritura.
- **El `uid` de ROOM sigue siendo `LocalDiaryRepository.LOCAL_USER_ID`.** `RoomSessionDataReassigner`
  mueve filas entre uids, pero las escrituras nuevas no se particionan por el uid de Firebase.

