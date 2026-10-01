package com.shortsmaker.viral.ads

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.LoadAdError
import com.shortsmaker.viral.BuildConfig

/**
 * Banner adaptable anclado en la parte inferior. Reserva exactamente el alto del anuncio (sin tapar contenido)
 * y avisa con [onLoadedChange] cuando hay un anuncio visible para que la pantalla ajuste los márgenes.
 */
@Composable
fun BannerAd(onLoadedChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val config = LocalConfiguration.current
    val widthDp = config.screenWidthDp
    val adSize = remember(widthDp, config.orientation) {
        AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(context, widthDp)
    }
    var loaded by remember { mutableStateOf(false) }

    val adView = remember(adSize) {
        AdView(context).apply {
            adUnitId = BuildConfig.ADMOB_BANNER_ID
            setAdSize(adSize)
            adListener = object : AdListener() {
                override fun onAdLoaded() { loaded = true; onLoadedChange(true) }
                override fun onAdFailedToLoad(error: LoadAdError) { loaded = false; onLoadedChange(false) }
            }
            loadAd(AdRequest.Builder().build())
        }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { adView.pause() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { adView.resume() }
    DisposableEffect(adView) {
        onDispose {
            onLoadedChange(false)
            adView.destroy()
        }
    }

    // Mientras no haya anuncio no se reserva espacio (no se muestran huecos vacíos).
    Box(modifier.fillMaxWidth().height(if (loaded) adSize.height.dp else 0.dp)) {
        AndroidView(factory = { adView }, modifier = Modifier.fillMaxWidth())
    }
}
