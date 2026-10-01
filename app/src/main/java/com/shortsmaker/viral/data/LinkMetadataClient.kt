package com.shortsmaker.viral.data

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebView
import android.webkit.WebViewClient
import com.shortsmaker.viral.domain.MediaLink
import com.shortsmaker.viral.domain.PageMetaParser
import com.shortsmaker.viral.domain.Platform
import com.shortsmaker.viral.domain.SourceMeta
import com.shortsmaker.viral.domain.YouTubeHtmlParser
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
 * Lee los datos PÚBLICOS de la página de un enlace (YouTube, Twitch o Kick) directamente desde el dispositivo,
 * sin servidores intermedios ni API de pago:
 *  - YouTube: título, duración y "heatmap" de momentos más reproducidos.
 *  - Twitch / Kick: título y duración si aparecen en el HTML/DOM. Estas plataformas NO exponen un heatmap en el
 *    DOM, así que `heatmap` queda vacío y el análisis usa el algoritmo local sobre la transcripción.
 *
 * 1) Petición HTTP directa. 2) Si no hay datos útiles, WebView invisible que lee el DOM ya renderizado.
 * Todo es "best effort": devuelve `null` si falla, y la app sigue con el análisis local de texto.
 *
 * IMPORTANTE: la app NO descarga videos ni directos (política de Google Play / términos de las plataformas).
 */
class LinkMetadataClient(private val context: Context, private val http: OkHttpClient) {

    suspend fun fetch(link: MediaLink): SourceMeta? {
        val direct = fetchDirect(link)
        if (direct.isUseful(link)) return direct
        val rendered = fetchWithWebView(link)
        return when {
            rendered == null -> direct
            direct == null -> rendered
            else -> rendered.copy(
                title = rendered.title ?: direct.title,
                durationMs = rendered.durationMs ?: direct.durationMs,
                heatmap = rendered.heatmap.ifEmpty { direct.heatmap },
            )
        }
    }

    /** YouTube sólo se da por bueno con heatmap; Twitch/Kick con un título real. */
    private fun SourceMeta?.isUseful(link: MediaLink) = when {
        this == null -> false
        link.platform == Platform.YOUTUBE -> heatmap.isNotEmpty()
        else -> title != null
    }

    private fun parse(link: MediaLink, html: String): SourceMeta = when (link.platform) {
        Platform.YOUTUBE -> YouTubeHtmlParser.parse(link.id, html)
        Platform.TWITCH, Platform.KICK -> PageMetaParser.parse(html).let { SourceMeta(link.id, it.title, it.durationMs, emptyList()) }
    }

    private suspend fun fetchDirect(link: MediaLink): SourceMeta? = withContext(Dispatchers.IO) {
        val builder = Request.Builder()
            .url(link.canonicalUrl + if (link.platform == Platform.YOUTUBE) "&hl=en" else "")
            .header("User-Agent", DESKTOP_UA)
            .header("Accept-Language", "en-US,en;q=0.9")
        if (link.platform == Platform.YOUTUBE) builder.header("Cookie", "CONSENT=YES+cb; SOCS=CAI")
        try {
            http.newCall(builder.build()).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val html = resp.body?.string() ?: return@use null
                parse(link, html)
            }
        } catch (_: IOException) {
            null
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun fetchWithWebView(link: MediaLink): SourceMeta? = withTimeoutOrNull(WEBVIEW_TIMEOUT_MS) {
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine<SourceMeta?> { cont ->
                val webView = try { WebView(context) } catch (_: Exception) { cont.resume(null); return@suspendCancellableCoroutine }
                var finished = false

                fun finish(meta: SourceMeta?) {
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
                        // Las SPA (Twitch/Kick) y el heatmap de YouTube tardan unos instantes en aparecer en el DOM.
                        view.postDelayed({
                            if (finished) return@postDelayed
                            view.evaluateJavascript("(function(){return document.documentElement.outerHTML;})()") { raw ->
                                val html = try { Json.parseToJsonElement(raw).jsonPrimitive.content } catch (_: Exception) { "" }
                                finish(if (html.isEmpty()) null else parse(link, html))
                            }
                        }, RENDER_WAIT_MS)
                    }
                }
                cont.invokeOnCancellation { webView.post { finish(null) } }
                webView.loadUrl(link.canonicalUrl + if (link.platform == Platform.YOUTUBE) "&hl=en" else "")
            }
        }
    }

    private companion object {
        const val WEBVIEW_TIMEOUT_MS = 25_000L
        const val RENDER_WAIT_MS = 3_000L
        const val DESKTOP_UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
    }
}
