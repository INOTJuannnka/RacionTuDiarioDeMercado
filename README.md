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

```
app/src/main/java/
├─ com/nutriapp/ui/
│  ├─ components/   # Componentes Compose reutilizables (AppComponents, bars, etc.)
│  ├─ navigation/   # Navegación y rutas de la app (AppNavigation)
│  ├─ screens/      # Pantallas: Inicio, Agregar, Escaner, Confirmar, Informe, Metas, Perfil
│  ├─ theme/        # Color, Theme y Tipografía
│  └─ AppNavigation.kt
├─ com/example/myapplication/   # MainActivity + entry point de CompatActivity
└─ res/             # Recursos: drawables, mipmaps, values, xml de backup
```

## 📄 Licencia

Proyecto personal. Todos los derechos reservados (all rights reserved).
