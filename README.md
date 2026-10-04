# 🥗 Ración — Tu Diario de Mercado

App Android (Kotlin + Jetpack Compose) para llevar el registro diario de tu alimentación: escaneá el código de barras de lo que compraste en el mercado, sumalo a tu diario, y seguí el balance entre lo que consumiste y tus metas.

## ✨ Funcionalidades

- **Inicio** · Resumen del día: calorías consumidas vs. meta, macros (carbohidratos, proteínas, grasas) y la lista de comidas del día.
- **Escáner** · Lector de código de barras con marco de escaneo y láser animado. Podés ingresar el código manualmente si la cámara no lee.
- **Manual** · Agregá alimentos a mano si no están etiquetados (entrada manual de código).
- **Confirmar** · Revisá y confirmá el alimento antes de sumarlo a tu diario.
- **Informe** · Resumen semanal: promedio diario de kcal, tu mejor día, racha activa, distribución de macros (donut) y calorías por día (barras).
- **Metas (Perfil)** · Definí y ajustá tus objetivos diarios de consumo.
- **Perfil deportivo** · Configurá tu perfil de deportista al dar de alta la app.
- **Aviso** · Pantalla de aviso/consentimiento al primer inicio (onboarding).

## 🛠️ Stack

- **Lenguaje:** Kotlin
- **UI:** Jetpack Compose + Material 3 (Material You)
- **Navegación:** Navigation Compose
- **Gradle:** Kotlin DSL con version catalog (`libs.versions.toml`)
- **minSdk:** 30 · **targetSdk / compileSdk:** 37

## 📦 Requisitos

- Android Studio (última versión estable) con SDK 37
- JDK 17+
- Gradle (wrapper incluido)

## 🚀 Cómo correrlo

1. Cloná el repo:

   ```bash
   git clone https://github.com/INOTJuannnka/RacionTuDiarioDeMercado.git
   ```

2. Abrí la carpeta en Android Studio.
3. Esperá que Gradle sincronice.
4. Corré la configuración `app` en un emulador o dispositivo con Android 11 (API 30) o superior.

## 🏗️ Estructura

El paquete de la app es `com.racion.diariomercado`. La capa de datos está **parcialmente**
conectada: las escrituras de perfil, metas y consentimiento van a Firestore, pero las
pantallas siguen alimentándose de `ui/preview/PreviewData` para leer. Corren completas sin
backend.

```
app/src/main/java/com/racion/diariomercado/
├─ MainActivity.kt        # Activity + punto de entrada
├─ RacionApplication.kt   # Application:dueña del contenedor de dependencias
├─ core/                  # AppResult / AppError (taxonomía de fallos)
├─ di/                    # AppContainer: DI manual (sin Hilt)
├─ domain/
│  ├─ model/              # Nutrition, FoodProduct, DiaryEntry, DailySummary, WeeklyReport,
│  │                      #   NutritionGoals, UserProfile + enums (MealSlot, DayOfWeek, SportFocus)
│  └─ repository/         # Interfaces: FoodCatalog, Diary, Goals, Profile
├─ data/                  # Implementaciones
│  ├─ local/              # ROOM: DAO, entidades, converters, outbox de sync
│  ├─ openfood/           # Retrofit service, DTOs Moshi, repositorio del catálogo
│  │  └─ dto/
│  └─ firebase/           # Auth + Goals + Profile implementados; Diary es stub
└─ ui/
   ├─ components/         # AppComponents: MealRow, MacroStat, OptionPill, DarkStatCard, etc.
   ├─ navigation/         # AppNavigation y rutas
   ├─ preview/            # PreviewData: todos los datos fake centralizados
   ├─ screens/            # Pantallas: Inicio, Agregar, Escaner, Confirmar, Informe, Metas,
   │                      #   PerfilDeportivo, Aviso
   └─ theme/              # Color, Theme y Tipografía

docs/
├─ ROADMAP.md             # Checklist por fases (FF-N / OFF-N / BC-N / ST-N / Q-N)
├─ SPRINT-1.md            # Plan del sprint 1, con reparto por lane y dueño
└─ INTEGRATION.md         # Referencia verificada de Open Food Facts y Firestore

 odd/tasks/               # Un doc por feature: decisiones, evidencia, pendientes
```

**Regla de dependencia:** `ui` → `domain` → nada. `data` implementa `domain` y conoce `domain`.
Ningún archivo de `domain/` importa Compose, Android ni `kotlinx`. Los repositorios son
`internal`: nadie fuera de `data/` sabe que existe Open Food Facts o Firestore.

## 🗺️ Estado del proyecto / Próximos pasos

La app es, hoy, **UI + tema + navegación funcionando sobre datos fake, con escritura real a
Firestore para perfil y metas**. El camino de escritura existe y está cableado; el de lectura
todavía no: `observeProfile` y `observeGoals` no tienen consumidores, así que las pantallas
siguen mostrando `PreviewData`. El diario sigue enteramente en ROOM
(`FirestoreDiaryRepository` es un stub que tira `NotImplementedError`).

Para seguir:

1. **[`docs/ROADMAP.md`](docs/ROADMAP.md)** — el checklist ordenado por fases. La Fase 2
   (Firebase) está parcialmente hecha: falta el gate de onboarding en `MainActivity` (FF-7), las
   reglas de seguridad (FF-5) y el sync del diario (DB-7/DB-8). La Fase 3 (Open Food Facts) es
   la que sigue más completa. Cada tarea tiene un ID (`FF-N`, `OFF-N`) que podés buscar con
   `grep` en los `TODO` del código.
2. **[`docs/INTEGRATION.md`](docs/INTEGRATION.md)** — la referencia externa verificada
   (endpoints de Open Food Facts, límites de tasa, nombres exactos de los campos `nutriments`,
   límites y trampas de Firestore). Está para que nadie tenga que volver a deducirla.

> ⚠️ **Antes de publicar:** el repo **no tiene `firestore.rules`**. La app ya escribe a
> Firestore sin reglas versionadas que lo gobiernen. La especificación de reglas está en
> `docs/INTEGRATION.md` §2.2.

Lo primero que falla si se saltea, y conviene saber de antemano:

- **Open Food Facts bloquea requests sin `User-Agent` válido** (`AppName/Version (contact)`).
  Sin eso la API devuelve 403/503 y no hay resultados.
- **Un producto inexistente devuelve HTTP 200**, con `status: 0` y `product: null`. Hay que
  mirar el body, no solo el código HTTP.
- **La búsqueda de texto libre solo existe en el endpoint v1** (`cgi/search.pl`); la v2 no la
  soporta. Y está prohibido el search-as-you-type: el límite es de 10 requests/minuto.

Decisiones ya tomadas: **Firestore** (no Realtime Database) como backend remoto, **anónimo
primero** como estrategia de cuenta, **DI manual** sin Hilt, y **no cachear** respuestas de OFF
en Firestore. El detalle y el porqué están en `docs/INTEGRATION.md` §1.8 y en los `odd/tasks/`.

## 📄 Licencia

Código fuente: © 2025 INOTJuannnka. Todos los derechos reservados. Proyecto personal, sin
licencia abierta.

**Datos de terceros:** los datos de productos provienen de
[Open Food Facts](https://world.openfoodfacts.org) y están bajo
[ODbL](https://opendatacommons.org/licenses/odbl/1-0/); las imágenes de productos bajo CC-BY-SA.
Atribución a Open Food Facts obligatoria. Ver `docs/INTEGRATION.md`.

