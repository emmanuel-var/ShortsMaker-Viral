package com.shortsmaker.viral.data

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebView
import android.webkit.WebViewClient
import com.shortsmaker.viral.domain.YouTubeHtmlParser
import com.shortsmaker.viral.domain.YouTubeMeta
import com.shortsmaker.viral.domain.YouTubeUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import kotlin.coroutines.resume

/**
 * Lee los datos públicos de la página de un video (título, duración y "heatmap" de momentos más reproducidos)
 * directamente desde el dispositivo del usuario, sin servidores intermedios ni API de pago.
 *
 * 1) Petición HTTP directa a la página pública. 2) Si no aparece el heatmap, carga la página en un WebView
 * invisible y lee el DOM renderizado. Todo es "best effort": si YouTube cambia su formato la app sigue
 * funcionando y sólo usa el análisis del texto.
 *
 * IMPORTANTE: la app no descarga el video de YouTube (política de Google Play / Términos de YouTube).
 */
class YouTubeClient(private val context: Context, private val http: OkHttpClient) {

    suspend fun fetch(videoId: String): YouTubeMeta? {
        val direct = fetchDirect(videoId)
        if (direct != null && direct.heatmap.isNotEmpty()) return direct
        val rendered = fetchWithWebView(videoId)
        return when {
            rendered == null -> direct
            direct == null -> rendered
            rendered.heatmap.isNotEmpty() -> rendered.copy(
                title = rendered.title ?: direct.title,
                durationMs = rendered.durationMs ?: direct.durationMs,
            )
            else -> direct
        }
    }

    private suspend fun fetchDirect(videoId: String): YouTubeMeta? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://www.youtube.com/watch?v=$videoId&hl=en")
            .header("User-Agent", DESKTOP_UA)
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Cookie", "CONSENT=YES+cb; SOCS=CAI")
            .build()
        try {
            http.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val html = resp.body?.string() ?: return@use null
                YouTubeHtmlParser.parse(videoId, html)
            }
        } catch (_: IOException) {
            null
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun fetchWithWebView(videoId: String): YouTubeMeta? = withTimeoutOrNull(WEBVIEW_TIMEOUT_MS) {
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine<YouTubeMeta?> { cont ->
                val webView = try { WebView(context) } catch (_: Exception) { cont.resume(null); return@suspendCancellableCoroutine }
                var finished = false

                fun finish(meta: YouTubeMeta?) {
                    if (finished) return
                    finished = true
                    webView.stopLoading()
                    webView.destroy()
                    if (cont.isActive) cont.resume(meta)
                }

                webView.settings.javaScriptEnabled = true
                webView.settings.domStorageEnabled = true
                webView.settings.userAgentString = DESKTOP_UA
                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String?) {
                        // El heatmap suele llegar unos instantes después de cargar la página.
                        view.postDelayed({
                            if (finished) return@postDelayed
                            view.evaluateJavascript("(function(){return document.documentElement.outerHTML;})()") { raw ->
                                val html = try { Json.parseToJsonElement(raw).jsonPrimitive.content } catch (_: Exception) { "" }
                                finish(if (html.isEmpty()) null else YouTubeHtmlParser.parse(videoId, html))
                            }
                        }, RENDER_WAIT_MS)
                    }
                }
                cont.invokeOnCancellation { webView.post { finish(null) } }
                webView.loadUrl(YouTubeUrl.canonicalUrl(videoId) + "&hl=en")
            }
        }
    }

    private companion object {
        const val WEBVIEW_TIMEOUT_MS = 25_000L
        const val RENDER_WAIT_MS = 2_500L
        const val DESKTOP_UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
    }
}
