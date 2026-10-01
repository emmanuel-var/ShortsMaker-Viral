package com.shortsmaker.viral

import android.content.Intent
import android.os.Bundle
import androidx.compose.runtime.mutableStateOf
import com.shortsmaker.viral.work.AnalysisNotifications
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shortsmaker.viral.ui.AppNavigation
import com.shortsmaker.viral.ui.ProvideAppLanguage
import com.shortsmaker.viral.ui.theme.ShortsMakerTheme

class MainActivity : ComponentActivity() {
    private val openProject = mutableStateOf<String?>(null)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openProject.value = intent.getStringExtra(AnalysisNotifications.EXTRA_OPEN_PROJECT)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Consentimiento (UMP) antes de pedir anuncios.
        (application as ShortsMakerApp).container.ads.gatherConsent(this)
        if (savedInstanceState == null) openProject.value = intent?.getStringExtra(AnalysisNotifications.EXTRA_OPEN_PROJECT)
        val settings = (application as ShortsMakerApp).container.settings
        setContent {
            val current by settings.settings.collectAsStateWithLifecycle()
            ProvideAppLanguage(current.uiLanguage) {
                ShortsMakerTheme {
                    AppNavigation(openProjectId = openProject.value, onOpenProjectHandled = { openProject.value = null })
                }
            }
        }
    }
}
