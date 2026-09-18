package com.example.myapplication

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.nutriapp.ui.navigation.AppNavigation
import com.nutriapp.ui.navigation.Routes
import com.nutriapp.ui.theme.NutriAppTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val prefs = getSharedPreferences("ration_prefs", Context.MODE_PRIVATE)
        val startRoute = if (prefs.getBoolean("onboarding_done", false)) Routes.INICIO else Routes.AVISO
        setContent {
            NutriAppTheme {
                AppNavigation(
                    startRoute = startRoute,
                    onOnboardingDone = {
                        prefs.edit().putBoolean("onboarding_done", true).apply()
                    }
                )
            }
        }
    }
}