# Google Sign-In + identidad visual del login

## Objective

Agregar un botón "Continuar con Google" al flujo de login y darle a la pantalla una identidad
visual acorde con la app: hoy es un formulario pelado sobre el crema del tema, sin ninguna
estructura, sin la tarjeta oscura que ancla el resto de las pantallas y sin el botón de Google
que hace que el flujo se vea terminado.

## Problem

Dos problemas independientes:

1. **No hay ingreso con Google.** Las tres librerías (`androidx.credentials`,
   `androidx.credentials.play.services.auth`, `googleid`) están declaradas y cableadas en
   `app/build.gradle.kts` desde DB-1, con el comentario "DECLARED BUT UNUSED, on purpose"
   porque la estrategia ratificada era anonymous-first + Email/Password. El plugin de
   google-services ya está aplicado y `R.string.default_web_client_id` ya se genera
   (`app/build/generated/res/processDebugGoogleServices/values/values.xml`). Falta todo el
   camino: launcher de Credential Manager, comando de dominio, implementación y botón.

2. **La pantalla se ve genérica.** Ya usa los roles del tema, así que los colores "son" los de
   la app — pero es la única pantalla sin ancla visual: sin `inverseSurface`, sin tarjeta, sin
   la forma `shapes.large` de 16dp que sí usa `PrimaryButton`, y con el `Button` de M3 con su
   forma por defecto. El resultado es legible pero plano, y sin una jerarquía que separe
   "identidad de la app" de "credenciales".

## Why

El usuario que llega al login ya decidió que quiere usar la app; el proveedor de identidad no
debería ser una barrera. Google es el camino con menos fricción y es el esperado en Android.

Sobre los colores: la paleta ya está definida (`ui/theme/Color.kt`, seed naranja `#E05A2B`,
superficie crema `#F7F2E9`) y el resto de la app la consume con componentes compartidos en
`ui/components/AppComponents.kt`. El login no los usa — reconstruye los suyos. La corrección no
es inventar una paleta nueva, es **dejar de saltarse el sistema de diseño que ya existe**.

## Scope

**Dentro:**
- `AuthRepository.signInWithGoogle(idToken)`: comando de dominio + KDoc.
- `FirebaseAuthRepository`: implementación. **Link si hay sesión anónima, sign-in si no** (ver
  "Decisiones").
- `LoginViewModel`: acción de Google, estado de carga propio, y mapeo de los marcadores de
  `AuthErrorMarkers` que hoy solo maneja `ProfileViewModel`.
- `LoginScreen`: botón de Google, separador "o continuá con", y rework visual.
- `GoogleSignInLauncher` + `GoogleSignInButton`: launcher de Credential Manager y botón de marca.
- `res/drawable/ic_google_g.xml`: el logo oficial como vector.
- `AppNavigation`: cablear el launcher en `Routes.LOGIN`.
- `AppComponents`: tres cambios aditivos — `PrimaryButton` gana `enabled`/`isLoading` (con default,
  los 6 call sites existentes no cambian), y se agregan `LabeledDivider` y `AuthHeroBlock`.
- Tests de `LoginViewModel` para el camino de Google.

**Fuera (no tocar):**
- `Routes.REGISTRO` / `RegisterScreen` — el pedido es el login. El registro queda como está.
- FF-7 (gatear la ruta de arranque por `AuthState`) — sigue siendo `MainActivity`'s.
- `ProfileScreen` / `ProfileViewModel` — ya tienen su propio camino de "reclamar cuenta".
- El schema de ROOM y cualquier otra pantalla.

## Decisiones

**D1 — Link si hay sesión anónima, sign-in si no.** Es la decisión con más consecuencias de
esta tarea. `signInWithCredential` sobre una sesión anónima **destruye el `uid` y todos los
documentos escritos bajo él**, server-side e irreversible — exactamente el modo de fallo que
`AuthRepository.signOut` documenta como `WARNING`. Como la estrategia ratified es anonymous-first,
el caso "tengo diario y ahora entro con Google" no es un borde: es el camino normal. Con
`linkWithCredential` el `uid` se conserva, ningún documento se mueve, y no hace falta merge.

El costo es un caso de error más: si la cuenta de Google ya está registrada, el link falla con
`ERROR_CREDENTIAL_ALREADY_IN_USE`, que ya tiene marcador en `AuthErrorMarkers` y ya tiene
mensaje honesto en `ProfileViewModel`. O sea: el caso difícil del link ya estaba modelado.

**D2 — el token lo pide la pantalla, no el repositorio.** Credential Manager necesita un
`Activity` para mostrar su UI. Un repositorio que guarde una `Activity` es una fuga, y
`FirebaseAuthRepository` hoy no toma **ningún** argumento de constructor justamente para no
depender del orden de inicialización de Firebase (ver su KDoc de clase). El launcher vive en la
capa de UI y le pasa un `String` al repositorio. Domain no importa nada de Compose ni de
Credential Manager, que es la dirección de dependencias que el proyecto ya defiende.

**D3 — el botón de Google no se tiñe.** Las guidelines de marca de Google prohíben usar el color
de la app en el botón de Google: tiene que ir sobre blanco (o superficie oscura en dark mode)
con el logo y un borde. Por eso es `surfaceContainerLowest` + `outline`, no `primary`.

**D4 — `GetSignInWithGoogleOption`, y se aceptan los dos tipos de credential.** La API pública de
`androidx.credentials:1.3.0` es `suspend getCredential(context, request)`; no hay
`getPendingIntent` como API pública, así que el launcher es `rememberGoogleSignInLauncher(...)`,
que devuelve el trigger y deja la pantalla sin statelessness rota.

Entre `GetSignInWithGoogleOption` y `GetGoogleIdOption` se eligió el primero: trae el bottom sheet
de marca y maneja internamente el fallback de "este dispositivo todavía no tiene una cuenta de
Google autorizada". `GetGoogleIdOption` obliga a manejar ese dos pasos a mano
(`setFilterByAuthorizedAccounts(true)` → `NoCredentialException` → `false`), y hacerlo mal
significa que un usuario recurrente ve una lista de cuentas vacía.

Dos restricciones no obvias que salieron de leer el código de `googleid:1.1.1` y de
`CredentialProviderGetSignInIntentController`:

1. El controller **rechaza** cualquier request cuya cantidad de credential options no sea
   exactamente 1 (`GetCredentialUnsupportedException`). Por eso se agrega una sola opción, y
   agregar una segunda en el futuro exige un segundo request.
2. La credential devuelta es un `CustomCredential` cuyo `type` puede ser
   `TYPE_GOOGLE_ID_TOKEN_CREDENTIAL` **o** `TYPE_GOOGLE_ID_TOKEN_SIWG_CREDENTIAL` según qué
   opción la produjo. Se aceptan ambos: comparar contra uno solo haría que la extracción fallara
   el día que alguien cambie la opción, y el síntoma se leería como "Google está roto" en vez de
   "se compares contra un string viejo".

**D5 — `FAILED` reusa la rama del token vacío.** El launcher reporta "volvió la hoja pero no se
pudo leer un token" entregando el mismo `String` vacío que entregaría un launch fallido. Así hay
un mensaje y un solo camino de código, y `AppNavigation` mapea tres desenlaces a dos métodos del
ViewModel en vez de a tres. El test afirma que en ese caso no se manda ninguna request.

**D6 — `isGoogleInProgress` sube en el TAP, no cuando llega el token.** La ventana que necesita
los botones deshabilitados es la del selector de cuenta sobre la pantalla; cuando existe un token
esa ventana ya se cerró. Por eso `onGoogleSignInRequested()` se dispara junto al
`googleSignInLauncher()` en `AppNavigation`, no dentro del callback.

## Constraints

- Generated artifacts (copy, KDoc, identificadores) en inglés salvo el copy de cara al usuario,
  que va en español rioplatense — la convención ya declarada en `LoginViewModel`.
- No agregar dependencias: las tres de Google Sign-In ya están en el catálogo y cableadas.
- No tocar `google-services.json`.
- No agregar `Hilt`, ni credential auto-config, ni un `GoogleSignInOptions` hardcodeado.

## Checklist

- [x] T1 · RED: tests de `LoginViewModel` para el camino de Google (falla por contrato).
- [x] T2 · GREEN: `AuthRepository.signInWithGoogle` + impl en `FirebaseAuthRepository` (D1).
- [x] T3 · GREEN: acción de Google en `LoginViewModel` + marcadores de `AuthErrorMarkers`.
- [x] T4 · Logo de Google como vector en `res/drawable/ic_google_g.xml`.
- [x] T5 · `GoogleSignInLauncher` + `GoogleSignInButton` (D3, D4).
- [x] T6 · Rework visual de `LoginScreen` con componentes compartidos.
- [x] T7 · Cablear el launcher en `AppNavigation` (`Routes.LOGIN`) (D5, D6).
- [x] T8 · Verificación: `assembleDebug` + `testDebugUnitTest`.

Los dos tests de `PROVIDER_UNAVAILABLE` y de token ilegible (D5) se escribieron después de T5,
cuando el launcher reveló que emite dos desenlaces que el ViewModel todavía no manejaba. Se
escribieron antes del método y se observó el RED (`Unresolved reference
'onGoogleSignInProviderUnavailable'`), como el resto.

## Criterios de aceptación

- El botón aparece en `LoginScreen` y llama a `AuthRepository.signInWithGoogle`.
- Un idToken válido produce `AuthState.Authenticated` vía `authState`; el flag `isLoggedIn`
  navega a Inicio como hoy.
- Cancelar el selector de Google **no** muestra error ni deja el spinner.
- Con sesión anónima, el `uid` se conserva (link, no swap).
- La pantalla no declara colores hexadecimales: todo sale de `MaterialTheme.colorScheme` /
  `AppComponents`.
- `:app:testDebugUnitTest` verde.

## Checks

- `:app:assembleDebug` — compila debug (incluye `processDebugGoogleServices`).
- `:app:testDebugUnitTest` — suite JVM completa.
- No hay test de Compose en el módulo (`androidTest` no tiene pruebas de UI todavía), así que la
  parte visual se cubre con `@Preview` y la verificación estructural de que el archivo no
  contiene literales de color.

## Estado

**Implementado y verificado en código.** Sin commit: el working tree mezcla esta feature con la de
OpenFoodFacts (ver abajo), así que un `git commit -a` metería `ksp(libs.squareup.moshi.kotlin.codegen)`
y los cambios de OFF adentro de este trabajo.

## Verificación

- `:app:assembleDebug` → BUILD SUCCESSFUL. APK: `app-debug.apk`, 107.543.770 bytes.
- `:app:compileDebugKotlin` → limpio. Los únicos warnings son preexistentes de
  `data/openfood/dto/OpenFoodFactsDto.kt` (anotaciones `@Json` sobre parámetros), ajenos a esta
  feature.
- `:app:testDebugUnitTest --tests "*LoginViewModelTest*"` → BUILD SUCCESSFUL, 19 tests,
  0 failures. Incluye los 8 tests del camino de Google (6 originales + `aDeviceWithoutGoogleOffersTheEmailPathInsteadOfAskingForARetry`
  y `anUnreadableCredentialIsReportedThroughTheEmptyTokenBranch`), confirmados por nombre en
  `app/build/test-results/testDebugUnitTest/TEST-...LoginViewModelTest.xml`.
- `:app:testDebugUnitTest` (suite completa) → BUILD SUCCESSFUL, 12 clases, **159 tests,
  0 failures, 0 errors**.
- Estructural: `LoginScreen.kt`, `GoogleSignIn.kt` y `AppComponents.kt` no contienen ni un literal
  hexadecimal ni un `Color(0x...)`. Los únicos hex del feature están en
  `res/drawable/ic_google_g.xml`, que es el logo de marca y por definición no sigue el tema.

### Lo que NO está verificado

- El intercambio real contra Firebase. Requiere los pasos de consola de abajo; el código solo
  demuestra que el comando, el mapeo de errores y la navegación están cableados.
- La apariencia. No hay test de Compose en el módulo, así que el rework visual está cubierto por
  tres `@Preview` (`LoginScreenPreview`, `LoginScreenErrorPreview`, `LoginScreenGooglePreview`) y
  por el chequeo estructural de arriba. Un humano tiene que mirarlo en un emulador.

## Working tree compartido

`git status` muestra, además de esta feature, cambios de
`odd/tasks/openfoodfacts-integration.md`: `app/build.gradle.kts`,
`data/openfood/OpenFoodFactsService.kt`, `data/openfood/dto/OpenFoodFactsDto.kt` y
`test/.../MoshiAdapterTest.kt`. Ninguno es de este trabajo. Antes de commitear hay que stagear por
ruta, no con `-a`.

## Next steps

- **Pasos de consola que el código NO puede hacer** (mismo clase que FF-3/J6): habilitar el
  proveedor Google en Authentication → Sign-in method, y registrar el SHA-1 de debug
  `5C:D2:AE:21:D6:7F:8F:D2:A7:39:8F:D8:3B:37:9A:B3:25:61:4C:B0` en la app Android del
  proyecto Firebase. Sin el segundo, Google devuelve `DEVELOPER_ERROR` (ApiException status 10).
- Probar en emulador con una cuenta de Google real antes de dar esto por cerrado.
- Decidir si `Routes.REGISTRO` recibe el mismo launcher. Quedó fuera de scope a propósito.
