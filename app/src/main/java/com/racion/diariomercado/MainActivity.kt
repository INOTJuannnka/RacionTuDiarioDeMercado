package com.racion.diariomercado

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.racion.diariomercado.ui.navigation.AppNavigation
import com.racion.diariomercado.ui.navigation.Routes
import com.racion.diariomercado.ui.theme.NutriAppTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // TODO(FF-5): this SharedPreferences flag is the wrong owner for the onboarding state.
        // It is per-install, so it is silently lost on "clear app data", is not shared across
        // a user's devices, and is unreachable from any repository. Move the read and the write
        // behind ProfileRepository.observeOnboardingCompleted() /
        // ProfileRepository.completeOnboarding() (users/{uid}/onboarding/completed).
        //
        // Note the consequence for this method: the start route can no longer be a local `val`
        // computed before setContent. It has to be a `collectAsStateWithLifecycle()` inside the
        // composition, and the NavHost has to be keyed on the first emission, or the app will
        // flash "aviso" at an already-onboarded user.
        val prefs = getSharedPreferences("ration_prefs", Context.MODE_PRIVATE)
        val onboardingDone = prefs.getBoolean("onboarding_done", false)
        val startRoute = if (onboardingDone) Routes.LOGIN else Routes.AVISO

        setContent {
            NutriAppTheme {
                AppNavigation(
                    startRoute = startRoute,
                    onOnboardingDone = {
                        // TODO(FF-5): replace with profileRepository.completeOnboarding()
                        prefs.edit().putBoolean("onboarding_done", true).apply()
                    }
                )
            }
        }
    }
}
