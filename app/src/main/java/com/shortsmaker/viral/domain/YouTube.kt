package com.shortsmaker.viral.domain

import java.net.URI

object YouTubeUrl {
    private val ID = Regex("^[A-Za-z0-9_-]{11}$")
    private val HOSTS = setOf("youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com", "youtu.be", "www.youtu.be")

    /** Devuelve el ID de 11 caracteres si `input` es un enlace válido de YouTube; si no, null. */
    fun extractVideoId(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() }) return null
        val withScheme = if (trimmed.contains("://")) trimmed else "https://$trimmed"
        val uri = try { URI(withScheme) } catch (_: Exception) { return null }
        if (uri.scheme != "https" && uri.scheme != "http") return null
        val host = uri.host?.lowercase() ?: return null
        if (host !in HOSTS) return null
        val segments = (uri.path ?: "").split('/').filter { it.isNotEmpty() }

        val candidate = when {
            host.endsWith("youtu.be") -> segments.firstOrNull()
            segments.firstOrNull() == "watch" -> queryParam(uri.rawQuery, "v")
            segments.firstOrNull() in setOf("shorts", "embed", "live", "v") -> segments.getOrNull(1)
            else -> null
        }
        return candidate?.takeIf { ID.matches(it) }
    }

    fun canonicalUrl(videoId: String) = "https://www.youtube.com/watch?v=$videoId"

    private fun queryParam(rawQuery: String?, key: String): String? =
        rawQuery?.split('&')?.map { it.split('=', limit = 2) }?.firstOrNull { it[0] == key }?.getOrNull(1)
}

/**
 * Extrae el título, la duración y el "heatmap" (momentos más reproducidos) del HTML de la página de un video.
 * Soporta el formato antiguo (`heatMarkerRenderer`) y el nuevo (`macroMarkersListEntity`).
 */
object YouTubeHtmlParser {
    private val OLD_MARKER = Regex(
        "\"timeRangeStartMillis\":\\s*(\\d+)\\s*,\\s*\"markerDurationMillis\":\\s*(\\d+)\\s*,\\s*\"heatMarkerIntensityScoreNormalized\":\\s*(-?[0-9.]+(?:[eE][-+]?\\d+)?)",
    )
    private val NEW_MARKER = Regex(
        "\"startMillis\":\\s*\"?(\\d+)\"?\\s*,\\s*\"durationMillis\":\\s*\"?(\\d+)\"?\\s*,\\s*\"intensityScoreNormalized\":\\s*(-?[0-9.]+(?:[eE][-+]?\\d+)?)",
    )
    private val OG_TITLE = Regex("<meta\\s+property=\"og:title\"\\s+content=\"([^\"]*)\"")
    private val LENGTH = Regex("\"lengthSeconds\":\\s*\"(\\d+)\"")

    fun parse(videoId: String, html: String): SourceMeta {
        val title = OG_TITLE.find(html)?.groupValues?.get(1)?.let(::decodeEntities)?.takeIf { it.isNotBlank() }
        val duration = LENGTH.find(html)?.groupValues?.get(1)?.toLongOrNull()?.times(1000)
        return SourceMeta(videoId, title, duration, parseHeatmap(html))
    }

    fun parseHeatmap(html: String): List<HeatPoint> {
        val matches = OLD_MARKER.findAll(html).toList().ifEmpty { NEW_MARKER.findAll(html).toList() }
        return matches.mapNotNull { m ->
            val start = m.groupValues[1].toLongOrNull() ?: return@mapNotNull null
            val dur = m.groupValues[2].toLongOrNull() ?: return@mapNotNull null
            val value = m.groupValues[3].toFloatOrNull() ?: return@mapNotNull null
            if (dur <= 0) null else HeatPoint(start, start + dur, value.coerceIn(0f, 1f))
        }.distinctBy { it.startMs }.sortedBy { it.startMs }
    }

    /** Reescala el heatmap si el archivo local no dura exactamente lo mismo que el video de YouTube. */
    fun rescale(points: List<HeatPoint>, fromDurationMs: Long?, toDurationMs: Long): List<HeatPoint> {
        if (fromDurationMs == null || fromDurationMs <= 0 || toDurationMs <= 0) return points
        val k = toDurationMs.toDouble() / fromDurationMs
        if (kotlin.math.abs(k - 1.0) < 0.002) return points
        return points.map { HeatPoint((it.startMs * k).toLong(), (it.endMs * k).toLong(), it.value) }
    }

    private fun decodeEntities(s: String): String = s
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&#x27;", "'")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
}
