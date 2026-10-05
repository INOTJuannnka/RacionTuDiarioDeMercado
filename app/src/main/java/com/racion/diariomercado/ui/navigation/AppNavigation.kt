package com.racion.diariomercado.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.racion.diariomercado.RacionApplication
import com.racion.diariomercado.domain.model.DiaryEntry
import com.racion.diariomercado.domain.model.FoodProduct
import com.racion.diariomercado.domain.model.MealSlot
import com.racion.diariomercado.domain.model.Nutrition
import com.racion.diariomercado.domain.model.UserProfile
import com.racion.diariomercado.domain.repository.AuthRepository
import com.racion.diariomercado.domain.repository.SessionDataReassigner
import com.racion.diariomercado.ui.components.NavDestination
import com.racion.diariomercado.ui.screens.AgregarScreen
import com.racion.diariomercado.ui.screens.AvisoScreen
import com.racion.diariomercado.ui.screens.ConfirmarScreen
import com.racion.diariomercado.ui.screens.EscanerScreen
import com.racion.diariomercado.ui.screens.InformeScreen
import com.racion.diariomercado.ui.screens.InicioScreen
import com.racion.diariomercado.ui.screens.MetasScreen
import com.racion.diariomercado.ui.screens.PerfilDeportivoScreen
import com.racion.diariomercado.ui.screens.auth.GoogleSignInOutcome
import com.racion.diariomercado.ui.screens.auth.LoginScreen
import com.racion.diariomercado.ui.screens.auth.LoginViewModel
import com.racion.diariomercado.ui.screens.auth.ProfileScreen
import com.racion.diariomercado.ui.screens.auth.ProfileViewModel
import com.racion.diariomercado.ui.screens.auth.RegisterScreen
import com.racion.diariomercado.ui.screens.auth.rememberGoogleSignInLauncher
import com.racion.diariomercado.ui.preview.PreviewData
import kotlinx.coroutines.launch

/** Rutas de navegación de la app como strings simples. */
object Routes {
    const val INICIO = "inicio"
    const val AGREGAR = "agregar"
    const val ESCANEAR = "escanear"
    const val PERFIL = "perfil"
    const val CONFIRMAR = "confirmar"
    const val INFORME = "informe"
    const val PERFIL_DEPORTIVO = "perfil-deportivo"
    const val AVISO = "aviso"
    const val LOGIN = "login"
    const val REGISTRO = "registro"
    /** Account section: the three-state auth branch. See `ProfileScreen`. */
    const val CUENTA = "cuenta"
}

/** Mapea un destino de la barra inferior a su ruta (null si no tiene pestaña). */
private fun NavDestination.tabRoute(): String? = when (this) {
    NavDestination.Inicio -> Routes.INICIO
    NavDestination.Diario -> Routes.AGREGAR
    NavDestination.Escanear -> Routes.ESCANEAR
    NavDestination.Perfil -> Routes.PERFIL
}

/**
 * Navegación raíz de la app. Cada pantalla renderiza su propio Scaffold y
 * AppBottomBar internamente, así que aquí NO se agrega un Scaffold externo.
 *
 * ## The pending-product seam
 * [NavResult] is a plain `mutableStateOf` holder that carries a tapped product from
 * "Agregar" to "Confirmar", and every entry confirmed in "Confirmar" back to "Inicio".
 *
 * ## Auth screens are reachable but NOT gating (FF-7)
 * [Routes.LOGIN] and [Routes.REGISTRO] exist and are fully wired, but the app still starts at
 * [Routes.INICIO] (or [Routes.AVISO]) for a user with no session at all. That is intentional for
 * the skeleton: gating [startRoute] on [com.racion.diariomercado.domain.repository.AuthState]
 * cannot be done correctly from a plain `val` computed before `setContent`, exactly for the
 * reason `MainActivity.onCreate` documents — the first emission of a cold `authState` flow has to
 * arrive before the graph can be built, or the app flashes "aviso" at an already-onboarded user.
 * TODO(FF-7): collect `authState` in the composition and build the NavHost on the first
 * emission, then make LOGIN the start destination for an unauthenticated user.
 *
 * [NavResult.confirmedEntries] ACCUMULATES: it used to be a single nullable
 * `confirmedEntry` that was overwritten on every confirm, so the second add silently dropped the
 * first one from both the "Inicio" list and the header total — a data-loss bug with no error and
 * no way for the user to notice. It is a list now so that failure mode is not expressible.
 *
 * TODO(ST-1): this is a TEMPORARY seam, not an architecture. It is kept in memory only, so it
 * is lost on process death and it cannot survive a configuration change of the entry that owns
 * it. Replace it with a shared ViewModel scoped to the activity (or a
 * `SavedStateHandle`) before there is any real data behind it — at which point
 * `DiaryRepository.observeDay` becomes the single source of truth and this list disappears
 * entirely. Navigation arguments (`SavedStateHandle` on the destination) are the intended end
 * state, not a `remember` in the graph composable.
 */
private class NavResult {
    /** The product the user tapped, read by the "Confirmar" screen. */
    var pendingProduct by mutableStateOf<FoodProduct?>(null)

    /**
     * Every entry the user has confirmed in this session, oldest first.
     *
     * Append-only by construction: there is no setter that can replace the list, so no call site
     * can truncate a previous confirmation.
     */
    var confirmedEntries by mutableStateOf<List<DiaryEntry>>(emptyList())
        private set

    fun addConfirmedEntry(entry: DiaryEntry) {
        confirmedEntries = confirmedEntries + entry
    }
}

/**
 * The application container, reached from the composition.
 *
 * `AppContainer` is a class owned by [RacionApplication], not a singleton object, so a composable
 * that needs a repository has to walk [LocalContext] up to the application. That walk is
 * deliberately confined to this one function: it is the seam FF-7 replaces, because the moment
 * the graph is built from a `ViewModel` factory with a real scope there is no reason for a
 * composable to know how the container is obtained at all.
 */
@Composable
private fun rememberAppContainer(): com.racion.diariomercado.di.AppContainer {
    val application = LocalContext.current.applicationContext as RacionApplication
    return application.container
}

@Composable
fun AppNavigation(
    startRoute: String = Routes.INICIO,
    onOnboardingDone: () -> Unit = {}
) {
    val navController = rememberNavController()
    val navResult = remember { NavResult() }

    // FF-5: the scope that carries the goals and profile writes launched from the screens below.
    // It is tied to the composition, so a write in flight is cancelled if the graph goes away —
    // which is the honest behaviour here, because there is no outbox for these two writes yet and
    // the objects they carry are still held by the screens that produced them.
    val scope = rememberCoroutineScope()

    fun selectTab(dest: NavDestination) {
        val route = dest.tabRoute() ?: return
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    NavHost(navController = navController, startDestination = startRoute) {
        composable(Routes.INICIO) {
            // Every confirmation is merged in, not just the latest one, and the merge is sorted so
            // the "Lo de hoy" list stays in the order things were actually eaten. The header total
            // folds the SAME merged list, so the number can never disagree with the rows.
            val merged = (PreviewData.meals + navResult.confirmedEntries)
                .sortedBy { it.loggedAtEpochMillis }
            InicioScreen(
                // The confirmed entries are appended locally so the user sees their adds land
                // immediately. Once DiaryRepository is real this is replaced by observing it.
                meals = merged,
                consumed = merged.fold(Nutrition()) { acc, entry -> acc + entry.totalNutrition },
                onAddMeal = { navController.navigate(Routes.AGREGAR) },
                onOpenReport = { navController.navigate(Routes.INFORME) },
                onNavigate = ::selectTab
            )
        }
        composable(Routes.AGREGAR) {
            AgregarScreen(
                onFoodClick = { product ->
                    navResult.pendingProduct = product
                    navController.navigate(Routes.CONFIRMAR)
                },
                // TODO(ST-2): manual entry has no affordance on this screen yet, so there is no
                // callback to wire. When it exists it must land on a dedicated screen, NOT on
                // ESCANEAR: Escanear already routes its own "Ingresar código manualmente" row back
                // here, so pointing both at each other would ping-pong forever.
                onNavigate = ::selectTab
            )
        }
        composable(Routes.ESCANEAR) {
            EscanerScreen(
                onManualEntry = { navController.navigate(Routes.AGREGAR) },
                // TODO(BC-1): a real scan resolves a barcode through FoodCatalogRepository and
                // lands in the same navResult.pendingProduct seam.
                onScanResult = {
                    navResult.pendingProduct = PreviewData.featuredProduct
                    navController.navigate(Routes.CONFIRMAR)
                },
                onNavigate = ::selectTab
            )
        }
        composable(Routes.PERFIL) {
            val container = rememberAppContainer()
            MetasScreen(
                onSave = { goals ->
                    // FF-5: the write is launched and the screen moves on immediately, on purpose.
                    // Blocking the navigation on a network round trip would hold the user on this
                    // screen while the radio comes up, and the goals are already in the local
                    // object the screen loaded from — nothing is lost if this is slow.
                    //
                    // The failure is not reported yet, and that is a real gap rather than an
                    // oversight: there is no snackbar seam in this graph to report it through, and
                    // inventing one is FF-7's block. Until then a dropped write is invisible, which
                    // is why this comment marks the exact place the seam has to land.
                    scope.launch { container.goalsRepository.saveGoals(goals) }
                    navController.navigate(Routes.INICIO) {
                        popUpTo(navController.graph.findStartDestination().id)
                        launchSingleTop = true
                    }
                },
                onOpenLogin = { navController.navigate(Routes.CUENTA) },
                onNavigate = ::selectTab
            )
        }
        composable(Routes.CONFIRMAR) {
            val product = navResult.pendingProduct ?: PreviewData.featuredProduct
            ConfirmarScreen(
                product = product,
                mealSlots = listOf(MealSlot.DESAYUNO, MealSlot.ALMUERZO, MealSlot.SNACK),
                onAddToDiary = { units, mealSlot ->
                    // TODO(FF-6): persist through DiaryRepository.addEntry(...). The id is
                    // generated here so the write can be retried idempotently offline.
                    navResult.addConfirmedEntry(
                        DiaryEntry(
                            id = "local-${System.currentTimeMillis()}",
                            product = product,
                            servings = units,
                            mealSlot = mealSlot,
                            loggedAtEpochMillis = System.currentTimeMillis(),
                            // Same canonical method the "Confirmar" screen previewed this with, so
                            // the kcal the user saw is the kcal that lands in the diary.
                            totalNutrition = product.nutritionForUnits(units)
                        )
                    )
                    navController.navigate(Routes.INICIO) {
                        popUpTo(navController.graph.findStartDestination().id)
                        launchSingleTop = true
                    }
                },
                onNavigate = ::selectTab
            )
        }
        composable(Routes.INFORME) {
            InformeScreen(onNavigate = ::selectTab)
        }
        composable(Routes.PERFIL_DEPORTIVO) {
            val container = rememberAppContainer()
            PerfilDeportivoScreen(
                onContinue = { focus ->
                    // FF-5: same fire-and-forget shape as the goals save above, and the same
                    // unreported-failure gap — see the note there before adding a second copy of
                    // the workaround.
                    //
                    // `userId` is read from the session so it is not a placeholder, but
                    // FirestoreProfileRepository ignores it on write: the document path is the
                    // authoritative identity and the field is never stored (see
                    // UserProfile.toProfileDocument). Passing a real value here keeps the object
                    // honest for anything that reads it before the write lands.
                    scope.launch {
                        container.profileRepository.saveProfile(
                            UserProfile(
                                userId = container.authRepository.currentUid.orEmpty(),
                                displayName = "",
                                sportFocus = focus,
                                // The screen collects no weight, so the domain default stands.
                                // A later screen owns this field and will own its own value.
                                // The id here is the placeholder the domain requires and nothing
                                // reads: the write derives the real one from the document path.
                                currentWeightKg = UserProfile(userId = "").currentWeightKg
                            )
                        )
                    }
                    navController.navigate(Routes.PERFIL)
                },
                onNavigate = ::selectTab
            )
        }
        composable(Routes.AVISO) {
            AvisoScreen(
                onAccept = {
                    onOnboardingDone()
                    navController.navigate(Routes.PERFIL_DEPORTIVO) {
                        popUpTo(Routes.AVISO) { inclusive = true }
                    }
                },
                onViewPolicy = {}
            )
        }

        // FF-4: `FirebaseAuthRepository` is real now, so both of these screens reach the provider instead of
// landing on a stub. Until the Anonymous / Email providers are enabled in the Firebase console
// (J6), BOTH still end on the "Ocurrió un error inesperado" branch — that is the honest report of a
// provider that rejects the call, not a wiring problem. No change is needed here when J6 lands.
composable(Routes.LOGIN) {
            // Resolved here, NOT inside `initializer {}`. That block is a plain `() -> ViewModel`,
            // so a @Composable call in it does not compile — and hoisting it also means the
            // container is read once per composition instead of once per factory invocation.
            val container = rememberAppContainer()
            val authRepository = container.authRepository
            // MANDATORY: the factory, NEVER `remember { LoginViewModel(...) }`. Both spellings
            // compile, which is exactly why the wrong one is dangerous.
            //
            // `remember` scopes the ViewModel to the COMPOSITION. `viewModel()` scopes it to the
            // `NavBackStackEntry`, which is the thing that actually represents "this destination".
            // The difference is invisible until the pending Google sign-in is involved:
            // `pendingGoogleIdToken` is a FIELD of the ViewModel (written when the chosen account
            // already exists, read back on confirm). Leave this destination and the composition is
            // disposed, the back stack entry keeps its saved state, and a brand new ViewModel is
            // built with a `null` token. `onGoogleMergeConfirmed()` then returns on its
            // `?: return`: the dialog closes, nothing happens, and the user is left on a spinner
            // that no coroutine owns any more, because the previous `viewModelScope` was cancelled
            // along with the composition. The account-existing path becomes unreachable and the
            // failure is completely silent.
            //
            // This is not hypothetical — it shipped, and it is what "se queda creando sesión" was.
            // `Routes.CUENTA` below always used the factory, which is why the identical flow worked
            // there and failed only here.
            val viewModel: LoginViewModel = viewModel(
                factory = viewModelFactory {
                    initializer {
                        LoginViewModel(authRepository, container.sessionDataReassigner)
                    }
                }
            )
            val state by viewModel.uiState.collectAsStateWithLifecycle()

            // The account picker needs an Activity context, so it cannot live in `LoginScreen`
            // (which is stateless by design) nor in the repository (which takes no constructor
            // argument on purpose). The graph owns the trigger, and the screen just reports the tap.
            val googleSignInLauncher = rememberGoogleSignInLauncher(
                onIdToken = viewModel::onGoogleSignIn,
                onOutcome = { outcome ->
                    when (outcome) {
                        // A dismissal is silent by design.
                        GoogleSignInOutcome.CANCELLED -> viewModel.onGoogleSignInCancelled()
                        GoogleSignInOutcome.PROVIDER_UNAVAILABLE ->
                            viewModel.onGoogleSignInProviderUnavailable()
                        // FAILED reuses the blank-token branch on purpose: the sheet came back but
                        // no readable token did, which is exactly what an empty token means. One
                        // message, one code path, and the test asserts that no request is sent.
                        GoogleSignInOutcome.FAILED -> viewModel.onGoogleSignIn("")
                    }
                }
            )

            // The success branch pops the auth flow off the back stack rather than pushing
            // INICIO on top of it: otherwise the back gesture from "Inicio" would return to the
            // login form the user just completed.
            LaunchedEffect(state.isLoggedIn) {
                if (state.isLoggedIn) {
                    navController.navigate(Routes.INICIO) {
                        popUpTo(Routes.LOGIN) { inclusive = true }
                        launchSingleTop = true
                    }
                }
            }

            LoginScreen(
                state = state,
                onEmailChange = viewModel::updateEmail,
                onPasswordChange = viewModel::updatePassword,
                onSignIn = viewModel::onSignIn,
                // The flag goes up on the TAP, not when the token comes back: the window that needs
                // the buttons disabled is the one where the account sheet is on screen, and by the
                // time a token exists that window is already closed.
                onGoogleSignIn = {
                    viewModel.onGoogleSignInRequested()
                    googleSignInLauncher()
                },
                onSignUpClick = { navController.navigate(Routes.REGISTRO) },
                onGoogleMergeConfirmed = viewModel::onGoogleMergeConfirmed,
                onGoogleMergeDismissed = viewModel::onGoogleMergeDismissed,
                onErrorShown = viewModel::onErrorShown
            )
        }
composable(Routes.REGISTRO) {
            val container = rememberAppContainer()
            val authRepository = container.authRepository
            val viewModel: LoginViewModel = viewModel(
                factory = viewModelFactory {
                    initializer {
                        LoginViewModel(authRepository, container.sessionDataReassigner)
                    }
                }
            )
            val state by viewModel.uiState.collectAsStateWithLifecycle()

            LaunchedEffect(state.isLoggedIn) {
                if (state.isLoggedIn) {
                    navController.navigate(Routes.INICIO) {
                        popUpTo(Routes.REGISTRO) { inclusive = true }
                        launchSingleTop = true
                    }
                }
            }

            RegisterScreen(
                state = state,
                onEmailChange = viewModel::updateEmail,
                onPasswordChange = viewModel::updatePassword,
                onConfirmPasswordChange = viewModel::updateConfirmPassword,
                onRegister = viewModel::onSignUp,
                // popBackStack, not navigate(LOGIN): REGISTRO was pushed on top of LOGIN, so
                // popping returns to the already-composed login form with its fields intact
                // instead of rebuilding it.
                onBackToLogin = { navController.popBackStack() },
                onErrorShown = viewModel::onErrorShown
            )
        }

        /**
         * The three-state account branch. Reached from the "Cuenta · Iniciar sesión" row on the
         * Perfil tab ([Routes.PERFIL]).
         *
         * That row used to navigate straight to [Routes.LOGIN], which is wrong now that anonymous
         * sign-in is the default entry point: most users have a session already, and the login form is
         * only one of three things this destination can be. Routing it through here first is what lets
         * the screen decide — an anonymous user gets "Reclamar tu cuenta", a signed-in one gets their
         * identity, and only a user with NO session is sent on to the login form from inside
         * [ProfileScreen]'s first branch.
         *
         * Note the label on that row still reads "Cuenta · Iniciar sesión" whatever the session is.
         * Making it dynamic means branching on [com.racion.diariomercado.domain.repository.AuthState]
         * inside `MetasScreen`, which is another contributor's file — see the TODO at
         * `MetasScreen.kt:162`. Until then the label over-promises slightly for a signed-in user and
         * the destination still does the right thing.
         */
        composable(Routes.CUENTA) {
            // Same hoisting as LOGIN/REGISTRO: `initializer {}` is a plain `() -> ViewModel`, so a
            // @Composable call inside it does not compile.
            val container = rememberAppContainer()
            val authRepository = container.authRepository
            val viewModel: ProfileViewModel = viewModel(
                factory = viewModelFactory {
                    initializer {
                        ProfileViewModel(authRepository, container.sessionDataReassigner)
                    }
                }
            )
            val state by viewModel.uiState.collectAsStateWithLifecycle()

            // Same launcher, same three-way outcome, as LOGIN — see the comment there for why the
            // account picker lives in the graph rather than in the screen. What differs is the
            // consequence, not the mechanism: LOGIN ends on a navigation, whereas here a success has
            // to close the claim sheet and let `authState` re-render the whole `ProfileScreen`
            // branch from Anonymous to Authenticated. Nothing to pop, because the user never left
            // this destination.
            val googleSignInLauncher = rememberGoogleSignInLauncher(
                onIdToken = viewModel::onGoogleSignIn,
                onOutcome = { outcome ->
                    when (outcome) {
                        GoogleSignInOutcome.CANCELLED -> viewModel.onGoogleSignInCancelled()
                        GoogleSignInOutcome.PROVIDER_UNAVAILABLE ->
                            viewModel.onGoogleSignInProviderUnavailable()
                        GoogleSignInOutcome.FAILED -> viewModel.onGoogleSignIn("")
                    }
                }
            )

            ProfileScreen(
                state = state,
                onEmailChange = viewModel::updateEmail,
                onPasswordChange = viewModel::updatePassword,
                onShowClaimForm = viewModel::onShowClaimForm,
                onDismissClaimForm = viewModel::onDismissClaimForm,
                onClaimSubmit = viewModel::onClaimSubmit,
                onClaimIntoExistingAccount = viewModel::onClaimIntoExistingAccount,
                onSwitchClaimPath = viewModel::onSwitchClaimPath,
                onGoogleSignInClick = googleSignInLauncher,
                // Navigation, not a repository call: the login form is its own destination and
                // `LoginViewModel` already owns the submit. This screen has no form for it.
                onSignInClick = { navController.navigate(Routes.LOGIN) },
                onRetrySignIn = viewModel::onRetryAnonymousSignIn,
                onSignOutClick = viewModel::onSignOutClick,
                onSignOutConfirmed = viewModel::onDestructiveSignOutConfirmed,
                onSignOutDismissed = viewModel::onDestructiveSignOutDismissed,
                onGoogleMergeConfirmed = viewModel::onGoogleMergeConfirmed,
                onGoogleMergeDismissed = viewModel::onGoogleMergeDismissed,
                onErrorShown = viewModel::onErrorShown
            )
        }
    }
}
