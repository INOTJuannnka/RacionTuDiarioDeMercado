# Persistencia real de diario y metas en Firebase

## Objetivo

Que la lista del diario y las metas del usuario sobrevivan al cambio de vista, al process death y al reinicio de la app, y que ambas escriban sus datos en Firestore usando el outbox que ya existe.

## Problema

El usuario reporta que el diario y las metas "se reinician cada que se cambia de vista". Verificado en código: **la UI nunca consulta ninguna fuente persistente.**

- `AppNavigation.kt:187` compone la lista como `PreviewData.meals + navResult.confirmedEntries`, donde `navResult` es un `remember { mutableStateOf(...) }` que vive en la composición. Cero repositorio, cero ViewModel.
- `InicioScreen` es un composable puro que recibe `meals: List<DiaryEntry>` como parámetro. No hay ViewModel de diario en el proyecto.
- No hay ViewModel de metas. `MetasScreen` recibe un callback `onSave` y nada más.
- `FirestoreGoalsRepository.observeGoals()` está implementado pero **no tiene ni un consumidor**: las metas se escriben a `users/{uid}/goals` y nunca se releen.

Agregado a esto, el estado del trabajo previo:

- `FirestoreDiaryRepository` es un stub: sus 5 métodos (`observeDay`, `observeWeek`, `addEntry`, `deleteEntry`, `observeHistory`) hacen `throw NotImplementedError`. Es un `Error`, no un `Exception`, así que ningún `try/catch` de Exception lo detiene. Está sin instanciar en `AppContainer`.
- `DiarySyncManager` está definido pero nunca instanciado ni arrancado. `SyncTransport` es una `interface` sin implementación.
- No existe `firestore.rules` en el repo. Sin reglas, todo read/write falla por defecto en un proyecto real.

## Por qué opción B (Room como fuente de verdad)

El proyecto ya diseñado la infraestructura offline-first: `LocalDiaryRepository` transaccional, tabla `sync_outbox`, `MIGRATION_1_2`, `DiarySyncManager.drain()`, `SyncTransport`. Todo existe y está cableado en `AppContainer` como `diaryRepository`. Terminar ese diseño cuesta menos trabajo total que reemplazarlo, y hace que el reset-on-view-change se resuelva de raíz porque Room responde al instante y sobrevive process death.

## Alcance autorizado

### Fase 1 — la UI lee de la fuente persistente

NUEVOS:
- `app/src/main/java/com/racion/diariomercado/ui/inicio/InicioViewModel.kt`
- `app/src/main/java/com/racion/diariomercado/ui/metas/MetasViewModel.kt`
- `app/src/test/java/com/racion/diariomercado/ui/inicio/InicioViewModelTest.kt`
- `app/src/test/java/com/racion/diariomercado/ui/metas/MetasViewModelTest.kt`

MODIFICADOS:
- `app/src/main/java/com/racion/diariomercado/ui/screens/InicioScreen.kt`
- `app/src/main/java/com/racion/diariomercado/ui/screens/MetasScreen.kt`
- `app/src/main/java/com/racion/diariomercado/ui/navigation/AppNavigation.kt`

### Fase 2 — Subir a Firestore vía outbox

NUEVOS:
- `app/src/main/java/com/racion/diariomercado/data/sync/FirestoreSyncTransport.kt`
- `app/src/test/java/com/racion/diariomercado/data/sync/FirestoreSyncTransportTest.kt`
- `firestore.rules`

MODIFICADOS:
- `app/src/main/java/com/racion/diariomercado/di/AppContainer.kt`
- `app/src/main/java/com/racion/diariomercado/RacionApplication.kt`

## Fuera de alcance

- No se toca `FirestoreDiaryRepository`. En opción B no es necesaria. Queda comunicado como bomba armada a eliminar en otra pasada.
- No se toca nada bajo `data/openfood/`, `ui/agregar/`, `ui/escaner/`.
- No se borra ni se commitea código ajeno.
- La documentación del repo y el deploy de `firestore.rules` quedan diferidos por decisión explícita del usuario.

## Contrato de dominio (no negociable)

```kotlin
interface DiaryRepository {
    fun observeDay(date: LocalDate): Flow<DailySummary>
    fun observeWeek(weekStart: LocalDate): Flow<WeeklyReport>
    suspend fun addEntry(entry: DiaryEntry): AppResult<Unit>
    suspend fun deleteEntry(entryId: String): AppResult<Unit>
    suspend fun recentScans(limit: Int = 5): AppResult<List<DiaryEntry>>
}

interface GoalsRepository {
    fun observeGoals(): Flow<NutritionGoals>
    suspend fun saveGoals(goals: NutritionGoals): AppResult<Unit>
}
```

## Criterios de aceptación

- [ ] `InicioViewModel` expone la lista del día desde `diaryRepository.observeDay(...)`, no desde `PreviewData` ni desde estado de navegación.
- [ ] `MetasViewModel` expone las metas desde `goalsRepository.observeGoals()`, que hoy no tiene consumidor.
- [ ] Cambiar de vista y volver no altera el contenido: el estado se rehidrata del Flow, no de un `remember`.
- [ ] Un error de lectura produce un estado de error renderizable, no una lista vacía silenciosa.
- [ ] La escritura de metas pasa por el ViewModel, no por un callback que llama al repositorio desde la navegación.
- [ ] `SyncTransport` tiene una implementación Firestore real.
- [ ] `DiarySyncManager` se instancia en `AppContainer` y se arranca una vez que existe uid.
- [ ] `firestore.rules` escrito y revisado (el deploy es del usuario).

## Checks aplicables

```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat :app:testDebugUnitTest --rerun-tasks --console=plain
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat :app:assembleDebug --console=plain
```

Baseline conocido antes de empezar: 255 tests, 0 fallos.

## Progreso

- [x] Fase 1: `InicioViewModel` + `MetasViewModel` observan repositorios; UI rehidrata del Flow; PreviewData eliminado de rutas reales; 277 tests verdes.
- [ ] Fase 2: `FirestoreSyncTransport` + `DiarySyncManager` cableado + `firestore.rules`.

## Próximo paso

Fase 1: escribir `InicioViewModel` y `MetasViewModel`, y cablearlos en `AppNavigation` reemplazando la fuente `PreviewData`/`NavResult`.