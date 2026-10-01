package com.shortsmaker.viral.ui.common

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.BitmapFactory
import android.view.WindowManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.shortsmaker.viral.AppContainer
import com.shortsmaker.viral.ShortsMakerApp
import com.shortsmaker.viral.data.MediaUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

fun Context.findActivity(): Activity? {
    var c = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

val Context.container: AppContainer get() = (applicationContext as ShortsMakerApp).container

/** Crea un ViewModel con acceso al contenedor de dependencias de la app. */
@Composable
inline fun <reified VM : ViewModel> appViewModel(key: String? = null, crossinline build: (AppContainer) -> VM): VM {
    val container = LocalContext.current.container
    return viewModel(
        key = key,
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = build(container) as T
        },
    )
}

/** Mantiene la pantalla encendida mientras la composición esté activa (procesos largos). */
@Composable
fun KeepScreenOn() {
    val activity = LocalContext.current.findActivity()
    DisposableEffect(activity) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
}

@Composable
fun rememberFileBitmap(file: File?): State<ImageBitmap?> = produceState<ImageBitmap?>(null, file?.path) {
    value = withContext(Dispatchers.IO) {
        file?.takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path)?.asImageBitmap() }
    }
}

@Composable
fun rememberVideoFrame(video: File?, timeMs: Long, maxWidth: Int = 480): State<ImageBitmap?> =
    produceState<ImageBitmap?>(null, video?.path, timeMs) {
        value = withContext(Dispatchers.IO) {
            video?.takeIf { it.exists() }?.let { MediaUtils.frameAt(it, timeMs, maxWidth)?.asImageBitmap() }
        }
    }

@Composable
fun BitmapOrPlaceholder(bitmap: ImageBitmap?, modifier: Modifier = Modifier) {
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
        if (bitmap != null) {
            Image(bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

/** Insignia circular con el puntaje viral (verde alto, ámbar medio, rojo bajo). */
@Composable
fun ScoreBadge(score: Int, size: Dp = 52.dp, modifier: Modifier = Modifier) {
    val color = when {
        score >= 80 -> Color(0xFF1DB954)
        score >= 60 -> Color(0xFFFFA000)
        else -> Color(0xFFE53935)
    }
    Box(modifier.size(size).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
        Text("$score", color = Color.White, fontWeight = FontWeight.Black, fontSize = (size.value * 0.38f).sp)
    }
}
