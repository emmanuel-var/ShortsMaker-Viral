package com.shortsmaker.viral

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shortsmaker.viral.ui.AppNavigation
import com.shortsmaker.viral.ui.ProvideAppLanguage
import com.shortsmaker.viral.ui.theme.ShortsMakerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Consentimiento (UMP) antes de pedir anuncios.
        (application as ShortsMakerApp).container.ads.gatherConsent(this)
        val settings = (application as ShortsMakerApp).container.settings
        setContent {
            val current by settings.settings.collectAsStateWithLifecycle()
            ProvideAppLanguage(current.uiLanguage) {
                ShortsMakerTheme {
                    AppNavigation()
                }
            }
        }
    }
}
