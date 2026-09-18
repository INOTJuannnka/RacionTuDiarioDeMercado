package com.nutriapp.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.nutriapp.ui.components.NavDestination
import com.nutriapp.ui.screens.AgregarScreen
import com.nutriapp.ui.screens.AvisoScreen
import com.nutriapp.ui.screens.ConfirmarScreen
import com.nutriapp.ui.screens.EscanerScreen
import com.nutriapp.ui.screens.InformeScreen
import com.nutriapp.ui.screens.InicioScreen
import com.nutriapp.ui.screens.MetasScreen
import com.nutriapp.ui.screens.PerfilDeportivoScreen

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
 */
@Composable
fun AppNavigation(
    startRoute: String = Routes.INICIO,
    onOnboardingDone: () -> Unit = {}
) {
    val navController = rememberNavController()

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
            InicioScreen(
                onAddMeal = { navController.navigate(Routes.AGREGAR) },
                onOpenReport = { navController.navigate(Routes.INFORME) },
                onNavigate = ::selectTab
            )
        }
        composable(Routes.AGREGAR) {
            AgregarScreen(
                onFoodClick = { navController.navigate(Routes.CONFIRMAR) },
                onAddMeal = { navController.navigate(Routes.CONFIRMAR) },
                onNavigate = ::selectTab
            )
        }
        composable(Routes.ESCANEAR) {
            EscanerScreen(
                onManualEntry = { navController.navigate(Routes.AGREGAR) },
                onScanResult = { navController.navigate(Routes.CONFIRMAR) },
                onNavigate = ::selectTab
            )
        }
        composable(Routes.PERFIL) {
            MetasScreen(
                onSave = {
                    navController.navigate(Routes.INICIO) {
                        popUpTo(navController.graph.findStartDestination().id)
                        launchSingleTop = true
                    }
                },
                onNavigate = ::selectTab
            )
        }
        composable(Routes.CONFIRMAR) {
            ConfirmarScreen(
                onAddToDiary = { _, _ ->
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
            PerfilDeportivoScreen(
                onContinue = { navController.navigate(Routes.PERFIL) },
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
    }
}