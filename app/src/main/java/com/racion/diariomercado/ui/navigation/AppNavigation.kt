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
import com.racion.diariomercado.domain.repository.AuthState
import com.racion.diariomercado.domain.repository.SessionDataReassigner
import com.racion.diariomercado.ui.components.NavDestination
import com.racion.diariomercado.ui.screens.AgregarScreen
import com.racion.diariomercado.ui.screens.AgregarViewModel
import com.racion.diariomercado.ui.screens.AvisoScreen
import com.racion.diariomercado.ui.screens.ConfirmarScreen
import com.racion.diariomercado.ui.screens.EscanerScreen
import com.racion.diariomercado.ui.screens.EscanerViewModel
import com.racion.diariomercado.ui.screens.InformeScreen
import com.racion.diariomercado.ui.screens.InicioScreen
import com.racion.diariomercado.ui.screens.MetasScreen
import com.racion.diariomercado.ui.screens.PerfilDeportivoScreen
import com.racion.diariomercado.ui.screens.auth.GoogleSignInOutcome
import com.racion.diariomercado.ui.screens.auth.LoginScreen
import com.racion.diariomercado.ui.screens.auth.LoginViewModel
import com.racion.diariomercado.ui.screens.auth.ProfileScreen
import com.racion.diariomercado.ui.metas.MetasViewModel
import com.racion.diariomercado.ui.metas.MetasUiState
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

private class NavResult {
    var pendingProduct by mutableStateOf<FoodProduct?>(null)
    var confirmedEntries by mutableStateOf<List<DiaryEntry>>(emptyList())
        private set

    fun addConfirmedEntry(entry: DiaryEntry) {
        confirmedEntries = confirmedEntries + entry
    }
}

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
            val merged = (PreviewData.meals + navResult.confirmedEntries)
                .sortedBy { it.loggedAtEpochMillis }
            InicioScreen(
                meals = merged,
                consumed = merged.fold(Nutrition()) { acc, entry -> acc + entry.totalNutrition },
                onAddMeal = { navController.navigate(Routes.AGREGAR) },
                onOpenReport = { navController.navigate(Routes.INFORME) },
                onNavigate = ::selectTab
            )
        }
        composable(Routes.AGREGAR) {
            val container = rememberAppContainer()
            val viewModel: AgregarViewModel = viewModel(
                factory = viewModelFactory {
                    initializer {
                        AgregarViewModel(container.openFoodFactsCatalogRepository)
                    }
                }
            )
            AgregarScreen(
                viewModel = viewModel,
                onFoodClick = { product ->
                    navResult.pendingProduct = product
                    navController.navigate(Routes.CONFIRMAR)
                },
                onNavigate = ::selectTab
            )
        }
        composable(Routes.ESCANEAR) {
            val container = rememberAppContainer()
            val context = LocalContext.current
            val viewModel: EscanerViewModel = viewModel(
                factory = viewModelFactory {
                    initializer {
                        EscanerViewModel(
                            catalogRepository = container.openFoodFactsCatalogRepository,
                            context = context
                        )
                    }
                }
            )
            EscanerScreen(
                viewModel = viewModel,
                onManualEntry = { navController.navigate(Routes.AGREGAR) },
                onScanResult = { product ->
                    navResult.pendingProduct = product
                    navController.navigate(Routes.CONFIRMAR)
                },
                onNavigate = ::selectTab
            )
        }
        composable(Routes.PERFIL) {
            val container = rememberAppContainer()
            val viewModel: MetasViewModel = viewModel(
                factory = viewModelFactory {
                    initializer {
                        MetasViewModel(container.goalsRepository, container.authRepository)
                    }
                }
            )
            val uiState = viewModel.uiState.collectAsStateWithLifecycle()
            val currentState = uiState.value
            val goals = when (currentState) {
                is MetasUiState.Loading -> PreviewData.goals
                is MetasUiState.Content -> currentState.goals
            }
            val canSave = when (currentState) {
                is MetasUiState.Loading -> false
                is MetasUiState.Content -> currentState.canSave
            }
            val authState = container.authRepository.authState.collectAsStateWithLifecycle(
                initialValue = AuthState.Unauthenticated
            ).value
            MetasScreen(
                goals = goals,
                onSave = { goals ->
                    if (canSave) {
                        scope.launch { viewModel.onSave(goals) }
                    }
                },
                authState = authState,
                onOpenLogin = { navController.navigate(Routes.CUENTA) },
                onClaimAccount = { navController.navigate(Routes.CUENTA) },
                onSignOut = { scope.launch { container.authRepository.signOut() } },
                onNavigate = ::selectTab
            )
        }
        composable(Routes.CONFIRMAR) {
            val product = navResult.pendingProduct ?: PreviewData.featuredProduct
            ConfirmarScreen(
                product = product,
                mealSlots = listOf(MealSlot.DESAYUNO, MealSlot.ALMUERZO, MealSlot.SNACK),
                onAddToDiary = { units, mealSlot ->
                    navResult.addConfirmedEntry(
                        DiaryEntry(
                            id = "local-${System.currentTimeMillis()}",
                            product = product,
                            servings = units,
                            mealSlot = mealSlot,
                            loggedAtEpochMillis = System.currentTimeMillis(),
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
                    scope.launch {
                        container.profileRepository.saveProfile(
                            UserProfile(
                                userId = container.authRepository.currentUid.orEmpty(),
                                displayName = "",
                                sportFocus = focus,
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

        composable(Routes.LOGIN) {
            val container = rememberAppContainer()
            val authRepository = container.authRepository
            val viewModel: LoginViewModel = viewModel(
                factory = viewModelFactory {
                    initializer {
                        LoginViewModel(authRepository, container.sessionDataReassigner)
                    }
                }
            )
            val state = viewModel.uiState.collectAsStateWithLifecycle().value

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

            LaunchedEffect(state.isLoggedIn) {
                if (state.isLoggedIn) {
                    navController.navigate(Routes.INICIO) {
                        popUpTo(navController.graph.findStartDestination().id) { inclusive = true }
                        launchSingleTop = true
                    }
                }
            }

            LoginScreen(
                state = state,
                onEmailChange = viewModel::updateEmail,
                onPasswordChange = viewModel::updatePassword,
                onSignIn = viewModel::onSignIn,
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
            val state = viewModel.uiState.collectAsStateWithLifecycle().value

            LaunchedEffect(state.isLoggedIn) {
                if (state.isLoggedIn) {
                    navController.navigate(Routes.INICIO) {
                        popUpTo(navController.graph.findStartDestination().id) { inclusive = true }
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
                onBackToLogin = { navController.popBackStack() },
                onErrorShown = viewModel::onErrorShown
            )
        }

        composable(Routes.CUENTA) {
            val container = rememberAppContainer()
            val authRepository = container.authRepository
            val viewModel: ProfileViewModel = viewModel(
                factory = viewModelFactory {
                    initializer {
                        ProfileViewModel(authRepository, container.sessionDataReassigner)
                    }
                }
            )
            val state = viewModel.uiState.collectAsStateWithLifecycle().value

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