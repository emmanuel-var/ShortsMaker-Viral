package com.shortsmaker.viral.domain

import java.net.URI

enum class Platform(val label: String) {
    YOUTUBE("YouTube"),
    TWITCH("Twitch"),
    KICK("Kick"),
}

enum class LinkKind { VIDEO, VOD, CLIP, LIVE }

/**
 * Enlace a un contenido de una plataforma. La app NUNCA descarga ese contenido: sólo lee metadatos públicos
 * (título, duración y, en YouTube, el "heatmap"). El video lo aporta siempre el usuario desde su dispositivo.
 */
data class MediaLink(val platform: Platform, val kind: LinkKind, val id: String, val canonicalUrl: String)

object MediaLinks {
    private val TWITCH_HOSTS = setOf("twitch.tv", "www.twitch.tv", "m.twitch.tv", "go.twitch.tv")
    private const val TWITCH_CLIPS_HOST = "clips.twitch.tv"
    private val KICK_HOSTS = setOf("kick.com", "www.kick.com")

    private val TWITCH_CHANNEL = Regex("^[A-Za-z0-9_]{3,25}$")
    private val KICK_CHANNEL = Regex("^[A-Za-z0-9_-]{3,25}$")
    private val DIGITS = Regex("^\\d{4,15}$")
    private val SLUG = Regex("^[A-Za-z0-9_-]{8,100}$")
    private val UUID = Regex("^[0-9a-fA-F-]{20,40}$")

    /** Rutas de Twitch que no son un canal. */
    private val TWITCH_RESERVED = setOf(
        "videos", "directory", "downloads", "settings", "p", "jobs", "turbo", "subscriptions", "friends", "wallet",
        "drops", "search", "popout", "moderator", "embed", "store", "prime", "inventory", "team", "login", "signup",
    )
    private val KICK_RESERVED = setOf(
        "video", "videos", "categories", "category", "search", "browse", "dashboard", "following", "terms-of-service",
        "privacy-policy", "community-guidelines", "support", "api", "clips", "login", "signup",
    )

    /** Devuelve el enlace normalizado o null si no es un enlace válido de YouTube, Twitch o Kick. */
    fun parse(input: String): MediaLink? {
        val trimmed = input.trim()
        if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() }) return null

        YouTubeUrl.extractVideoId(trimmed)?.let {
            return MediaLink(Platform.YOUTUBE, LinkKind.VIDEO, it, YouTubeUrl.canonicalUrl(it))
        }

        val uri = try { URI(if (trimmed.contains("://")) trimmed else "https://$trimmed") } catch (_: Exception) { return null }
        if (uri.scheme != "https" && uri.scheme != "http") return null
        val host = uri.host?.lowercase() ?: return null
        val segments = (uri.path ?: "").split('/').filter { it.isNotEmpty() }

        return when {
            host in TWITCH_HOSTS -> parseTwitch(segments)
            host == TWITCH_CLIPS_HOST -> segments.singleOrNull()?.takeIf { SLUG.matches(it) }?.let {
                MediaLink(Platform.TWITCH, LinkKind.CLIP, it, "https://clips.twitch.tv/$it")
            }
            host in KICK_HOSTS -> parseKick(segments, uri.rawQuery)
            else -> null
        }
    }

    private fun parseTwitch(seg: List<String>): MediaLink? {
        if (seg.size == 2 && seg[0] == "videos" && DIGITS.matches(seg[1])) {
            return MediaLink(Platform.TWITCH, LinkKind.VOD, seg[1], "https://www.twitch.tv/videos/${seg[1]}")
        }
        if (seg.size == 3 && TWITCH_CHANNEL.matches(seg[0]) && seg[1] == "clip" && SLUG.matches(seg[2])) {
            return MediaLink(Platform.TWITCH, LinkKind.CLIP, seg[2], "https://www.twitch.tv/${seg[0]}/clip/${seg[2]}")
        }
        if (seg.size == 1 && TWITCH_CHANNEL.matches(seg[0]) && seg[0].lowercase() !in TWITCH_RESERVED) {
            return MediaLink(Platform.TWITCH, LinkKind.LIVE, seg[0].lowercase(), "https://www.twitch.tv/${seg[0].lowercase()}")
        }
        return null
    }

    private fun parseKick(seg: List<String>, rawQuery: String?): MediaLink? {
        // Formato actual de VOD: kick.com/video/<uuid>
        if (seg.size == 2 && seg[0] == "video" && UUID.matches(seg[1])) {
            return MediaLink(Platform.KICK, LinkKind.VOD, seg[1], "https://kick.com/video/${seg[1]}")
        }
        // Formato antiguo: kick.com/<canal>/videos/<uuid>
        if (seg.size == 3 && KICK_CHANNEL.matches(seg[0]) && seg[1] == "videos" && UUID.matches(seg[2])) {
            return MediaLink(Platform.KICK, LinkKind.VOD, seg[2], "https://kick.com/video/${seg[2]}")
        }
        if (seg.size == 3 && KICK_CHANNEL.matches(seg[0]) && seg[1] == "clips" && SLUG.matches(seg[2])) {
            return MediaLink(Platform.KICK, LinkKind.CLIP, seg[2], "https://kick.com/${seg[0]}/clips/${seg[2]}")
        }
        if (seg.size == 1 && KICK_CHANNEL.matches(seg[0]) && seg[0].lowercase() !in KICK_RESERVED) {
            val clip = rawQuery?.split('&')?.map { it.split('=', limit = 2) }?.firstOrNull { it[0] == "clip" }?.getOrNull(1)
            if (clip != null && SLUG.matches(clip)) {
                return MediaLink(Platform.KICK, LinkKind.CLIP, clip, "https://kick.com/${seg[0]}?clip=$clip")
            }
            return MediaLink(Platform.KICK, LinkKind.LIVE, seg[0].lowercase(), "https://kick.com/${seg[0].lowercase()}")
        }
        return null
    }
}

/** Metadatos públicos leídos de un enlace. `heatmap` sólo existe en YouTube; Twitch/Kick no lo exponen en el DOM. */
data class SourceMeta(
    val id: String,
    val title: String?,
    val durationMs: Long?,
    val heatmap: List<HeatPoint>,
)

data class PageMeta(val title: String?, val durationMs: Long?)

/** Extrae título y duración del HTML (etiquetas Open Graph, `<title>` y JSON-LD) de cualquier página de video. */
object PageMetaParser {
    private val META_PROP_FIRST = { name: String ->
        Regex("<meta\\s+[^>]*?(?:property|name)=[\"']${Regex.escape(name)}[\"'][^>]*?content=[\"']([^\"']*)[\"']", RegexOption.IGNORE_CASE)
    }
    private val META_CONTENT_FIRST = { name: String ->
        Regex("<meta\\s+[^>]*?content=[\"']([^\"']*)[\"'][^>]*?(?:property|name)=[\"']${Regex.escape(name)}[\"']", RegexOption.IGNORE_CASE)
    }
    private val TITLE_TAG = Regex("<title[^>]*>([^<]*)</title>", RegexOption.IGNORE_CASE)
    private val LD_DURATION = Regex("\"duration\"\\s*:\\s*\"(P[^\"]+)\"")
    private val ISO = Regex("^P(?:(\\d+)D)?T?(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+(?:\\.\\d+)?)S)?$")

    /** Títulos que devuelven los sitios cuando muestran una pantalla genérica o un muro anti-bots. */
    private val GENERIC = setOf(
        "twitch", "kick", "kick.com", "just a moment...", "attention required! | cloudflare", "access denied",
        "twitch.tv", "403 forbidden", "error", "redirecting...",
    )
    private val SITE_SUFFIX = Regex("\\s*[-|–—]\\s*(?:twitch|kick(?:\\.com)?)\\s*$", RegexOption.IGNORE_CASE)

    fun parse(html: String): PageMeta {
        val raw = meta(html, "og:title") ?: meta(html, "twitter:title") ?: TITLE_TAG.find(html)?.groupValues?.get(1)
        val title = raw?.let(::decode)?.replace(SITE_SUFFIX, "")?.trim()
            ?.takeIf { it.isNotBlank() && it.lowercase() !in GENERIC }

        val seconds = meta(html, "og:video:duration")?.toLongOrNull() ?: meta(html, "video:duration")?.toLongOrNull()
        val duration = seconds?.times(1000) ?: LD_DURATION.find(html)?.groupValues?.get(1)?.let(::parseIsoDuration)
        return PageMeta(title, duration?.takeIf { it > 0 })
    }

    fun parseIsoDuration(iso: String): Long? {
        val m = ISO.matchEntire(iso) ?: return null
        val d = m.groupValues[1].toLongOrNull() ?: 0
        val h = m.groupValues[2].toLongOrNull() ?: 0
        val min = m.groupValues[3].toLongOrNull() ?: 0
        val s = m.groupValues[4].toDoubleOrNull() ?: 0.0
        return (((d * 24 + h) * 60 + min) * 60 * 1000 + (s * 1000).toLong())
    }

    private fun meta(html: String, name: String): String? =
        META_PROP_FIRST(name).find(html)?.groupValues?.get(1) ?: META_CONTENT_FIRST(name).find(html)?.groupValues?.get(1)

    private fun decode(s: String): String = s
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&#x27;", "'")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
}
