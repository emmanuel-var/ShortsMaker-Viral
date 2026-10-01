package com.shortsmaker.viral.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalFlowTest {

    /** Frases de [n] palabras separadas por pausas de 0.8 s; `wps` palabras por segundo. */
    private fun transcript(
        totalSeconds: Int,
        base: (sentenceIdx: Int, wordIdx: Int) -> String = { s, w -> "tema${s}x$w" },
        wpsAt: (Long) -> Float = { 2f },
        wordsPerSentence: Int = 8,
    ): List<WordTiming> {
        val out = mutableListOf<WordTiming>()
        var t = 0L
        var s = 0
        while (t < totalSeconds * 1000L) {
            val step = (1000f / wpsAt(t)).toLong()
            for (w in 0 until wordsPerSentence) {
                out += WordTiming(base(s, w), t, t + step - 30)
                t += step
            }
            t += 800
            s++
        }
        return out
    }

    // ---------------------------------------------------------------- LocalTextClipGenerator

    @Test fun `el generador local prefiere el tramo denso con preguntas y temas repetidos`() {
        val hot = 120_000L..170_000L
        val words = transcript(
            totalSeconds = 300,
            wpsAt = { if (it in hot) 4f else 2f },
            base = { _, w -> "relleno$w${(w * 31) % 7}" },
        ).mapIndexed { i, w ->
            // En el tramo caliente: cada frase empieza con "cómo" y repite "bitcoin"; se añade un gancho.
            if (w.startMs in hot) {
                val pos = i % 8
                w.copy(text = when (pos) { 0 -> "cómo"; 3 -> "bitcoin"; 5 -> "bitcoin"; 6 -> "secreto"; else -> w.text })
            } else w
        }
        val clips = LocalTextClipGenerator.generate(words, 300_000, Language.ES)
        assertTrue(clips.isNotEmpty())
        val best = clips.maxByOrNull { it.viralScore }!!
        assertTrue("best=${best.startMs}-${best.endMs}", best.startMs < 170_000 && best.endMs > 120_000)
        assertTrue(best.durationMs in 14_000..62_000)
        assertEquals(0, best.heatScore) // sin heatmap
        assertTrue(best.keywords.contains("bitcoin"))
        // el resto de clips (rellenos) puntúan menos
        assertTrue(clips.filter { it !== best }.all { it.viralScore <= best.viralScore })
    }

    @Test fun `sin solapes, con limites de duracion y puntajes validos`() {
        val clips = LocalTextClipGenerator.generate(transcript(400), 400_000, Language.EN)
        assertTrue(clips.size in 1..8)
        clips.forEach {
            assertTrue(it.durationMs in 14_000..62_000)
            assertTrue(it.viralScore in 30..99)
            assertTrue(it.startMs >= 0 && it.endMs <= 400_000)
        }
        clips.sortedBy { it.startMs }.zipWithNext().forEach { (a, b) ->
            assertTrue(a.endMs <= b.startMs + 0.25 * minOf(a.durationMs, b.durationMs))
        }
    }

    @Test fun `transcripcion corta devuelve vacio y el analizador cae a ventanas uniformes`() {
        assertTrue(LocalTextClipGenerator.generate(emptyList(), 100_000, Language.ES).isEmpty())
        val clips = ClipAnalyzer.suggest(emptyList(), emptyList(), 90_000, Language.ES)
        assertTrue(clips.isNotEmpty())
    }

    @Test fun `ClipAnalyzer usa el generador local cuando no hay heatmap`() {
        val words = transcript(200)
        val viaAnalyzer = ClipAnalyzer.suggest(words, emptyList(), 200_000, Language.ES)
        val direct = LocalTextClipGenerator.generate(words, 200_000, Language.ES)
        assertEquals(direct, viaAnalyzer)
        // con heatmap sigue la ruta de YouTube (heatScore > 0)
        val heat = (0 until 40).map { HeatPoint(it * 5000L, it * 5000L + 5000, if (it in 20..27) 1f else 0.1f) }
        assertTrue(ClipAnalyzer.suggest(words, heat, 200_000, Language.ES).first().heatScore > 0)
    }

    // ---------------------------------------------------------------- MediaLinks

    @Test fun `acepta enlaces de YouTube, Twitch y Kick`() {
        assertEquals(Platform.YOUTUBE, MediaLinks.parse("https://youtu.be/dQw4w9WgXcQ")?.platform)

        val vod = MediaLinks.parse("https://www.twitch.tv/videos/2012345678?t=1h2m")!!
        assertEquals(Platform.TWITCH, vod.platform); assertEquals(LinkKind.VOD, vod.kind)
        assertEquals("https://www.twitch.tv/videos/2012345678", vod.canonicalUrl)

        val live = MediaLinks.parse("twitch.tv/Ibai")!!
        assertEquals(LinkKind.LIVE, live.kind); assertEquals("ibai", live.id)

        assertEquals(LinkKind.CLIP, MediaLinks.parse("https://clips.twitch.tv/FunnyAwesomeClip-abc123XYZ")?.kind)
        assertEquals(LinkKind.CLIP, MediaLinks.parse("https://www.twitch.tv/ibai/clip/FunnyAwesomeClip-abc123XYZ")?.kind)

        val kickVod = MediaLinks.parse("https://kick.com/video/6e1b2d3c-1111-4a2b-9c3d-123456789abc")!!
        assertEquals(Platform.KICK, kickVod.platform); assertEquals(LinkKind.VOD, kickVod.kind)
        assertEquals(LinkKind.VOD, MediaLinks.parse("https://kick.com/xqc/videos/6e1b2d3c-1111-4a2b-9c3d-123456789abc")?.kind)
        assertEquals(LinkKind.LIVE, MediaLinks.parse("https://kick.com/xqc")?.kind)
        assertEquals(LinkKind.CLIP, MediaLinks.parse("https://kick.com/xqc/clips/clip_01HZABCDEFG")?.kind)
        assertEquals(LinkKind.CLIP, MediaLinks.parse("https://kick.com/xqc?clip=clip_01HZABCDEFG")?.kind)
    }

    @Test fun `rechaza enlaces no soportados o sospechosos`() {
        listOf(
            "", "hola", "https://twitch.tv/videos/abc", "https://twitch.tv/directory", "https://www.twitch.tv/settings",
            "https://twitch.tv.evil.com/videos/123456", "https://evil.com/twitch.tv/videos/123456",
            "https://kick.com/categories", "https://kick.com/video/zz", "https://notkick.com/xqc",
            "https://vimeo.com/123456", "ftp://twitch.tv/ibai",
        ).forEach { assertNull(it, MediaLinks.parse(it)) }
    }

    // ---------------------------------------------------------------- PageMetaParser

    @Test fun `extrae titulo y duracion de paginas de Twitch y Kick`() {
        val twitch = """<html><head><title>Stream épico - Twitch</title>
            <meta property="og:title" content="Maratón &amp; &quot;speedrun&quot; | Twitch">
            <script type="application/ld+json">{"@type":"VideoObject","duration":"PT1H2M3S"}</script></head></html>"""
        val m = PageMetaParser.parse(twitch)
        assertEquals("Maratón & \"speedrun\"", m.title)
        assertEquals((3600 + 120 + 3) * 1000L, m.durationMs)

        // content antes de property + duración en og:video:duration
        val kick = """<meta content="Charla nocturna" name="og:title"><meta property="og:video:duration" content="754">"""
        val k = PageMetaParser.parse(kick)
        assertEquals("Charla nocturna", k.title); assertEquals(754_000L, k.durationMs)
    }

    @Test fun `ignora titulos genericos y muros anti bots`() {
        assertNull(PageMetaParser.parse("<title>Twitch</title>").title)
        assertNull(PageMetaParser.parse("<title>Just a moment...</title>").title)
        assertNull(PageMetaParser.parse("<html></html>").title)
        assertNull(PageMetaParser.parse("<html></html>").durationMs)
        assertEquals(90_000L, PageMetaParser.parseIsoDuration("PT1M30S"))
        assertNull(PageMetaParser.parseIsoDuration("xx"))
    }

    // ---------------------------------------------------------------- Modo manual

    @Test fun `el clip manual cubre el video completo hasta el maximo`() {
        fun project(duration: Long) = Project(
            id = "p", name = "n", createdAt = 0, updatedAt = 0, sourceFile = "s.mp4", durationMs = duration,
            width = 1920, height = 1080, language = "es",
            words = listOf(WordTiming("hola", 0, 400), WordTiming("mundo", 500, 900)),
        )
        val short = ManualClip.create(project(90_000), 123)
        assertEquals(0L, short.startMs); assertEquals(90_000L, short.endMs)
        assertTrue(short.manual); assertEquals("manual_123", short.id); assertEquals("hola mundo", short.preview)
        assertEquals(ClipRange.MAX_MS, ManualClip.create(project(7_200_000), 1).endMs)
    }

    @Test fun `el proyecto antiguo sin campos nuevos se sigue leyendo`() {
        val json = """{"id":"a","name":"n","createdAt":1,"updatedAt":2,"sourceFile":"s.mp4","durationMs":10,"width":1,"height":1,"language":"es","youtubeUrl":"x"}"""
        val p = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.decodeFromString(Project.serializer(), json)
        assertNull(p.sourceUrl); assertTrue(p.faceCoverage.isEmpty()); assertFalse(p.hasHeatmap); assertNotNull(p.clips)
    }
}
