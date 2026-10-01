package com.shortsmaker.viral

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.shortsmaker.viral.ui.AppNavigation
import com.shortsmaker.viral.ui.theme.ShortsMakerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Consentimiento (UMP) antes de pedir anuncios.
        (application as ShortsMakerApp).container.ads.gatherConsent(this)
        setContent {
            ShortsMakerTheme {
                AppNavigation()
            }
        }
    }
}
