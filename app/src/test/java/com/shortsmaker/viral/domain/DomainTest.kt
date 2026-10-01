package com.shortsmaker.viral.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DomainTest {

    // ---- YouTube

    @Test fun `extrae el id de distintos formatos de enlace`() {
        val id = "dQw4w9WgXcQ"
        assertEquals(id, YouTubeUrl.extractVideoId("https://www.youtube.com/watch?v=$id"))
        assertEquals(id, YouTubeUrl.extractVideoId("https://www.youtube.com/watch?feature=share&v=$id&t=10"))
        assertEquals(id, YouTubeUrl.extractVideoId("https://youtu.be/$id?si=abc"))
        assertEquals(id, YouTubeUrl.extractVideoId("youtube.com/shorts/$id"))
        assertEquals(id, YouTubeUrl.extractVideoId("https://m.youtube.com/embed/$id"))
    }

    @Test fun `rechaza enlaces invalidos`() {
        assertNull(YouTubeUrl.extractVideoId(""))
        assertNull(YouTubeUrl.extractVideoId("hola mundo"))
        assertNull(YouTubeUrl.extractVideoId("https://evil.com/watch?v=dQw4w9WgXcQ"))
        assertNull(YouTubeUrl.extractVideoId("https://www.youtube.com/watch?v=corto"))
        assertNull(YouTubeUrl.extractVideoId("https://www.youtube.com/feed/subscriptions"))
    }

    @Test fun `parsea heatmap en formato antiguo y nuevo`() {
        val old = """..."heatMarkers":[{"heatMarkerRenderer":{"timeRangeStartMillis":0,"markerDurationMillis":4000,"heatMarkerIntensityScoreNormalized":0.25}},{"heatMarkerRenderer":{"timeRangeStartMillis":4000,"markerDurationMillis":4000,"heatMarkerIntensityScoreNormalized":1.0}}]..."""
        val parsed = YouTubeHtmlParser.parseHeatmap(old)
        assertEquals(2, parsed.size)
        assertEquals(4000L, parsed[1].startMs)
        assertEquals(1f, parsed[1].value, 0.001f)

        val new = """"markers":[{"startMillis":"0","durationMillis":"5000","intensityScoreNormalized":0.5},{"startMillis":"5000","durationMillis":"5000","intensityScoreNormalized":0.9}]"""
        assertEquals(2, YouTubeHtmlParser.parseHeatmap(new).size)
        assertTrue(YouTubeHtmlParser.parseHeatmap("<html></html>").isEmpty())
    }

    @Test fun `parsea titulo y duracion`() {
        val html = """<meta property="og:title" content="Mi &quot;video&quot; &amp; m&#39;as"> "lengthSeconds":"125" """
        val meta = YouTubeHtmlParser.parse("dQw4w9WgXcQ", html)
        assertEquals("Mi \"video\" & m'as", meta.title)
        assertEquals(125_000L, meta.durationMs)
    }

    // ---- Vosk

    @Test fun `parsea resultado de vosk`() {
        val json = """{"result":[{"conf":1.0,"end":1.5,"start":0.5,"word":"hola"},{"conf":1.0,"end":2.0,"start":1.6,"word":"mundo"}],"text":"hola mundo"}"""
        val words = VoskResult.parseWords(json)
        assertEquals(2, words.size)
        assertEquals(WordTiming("hola", 500, 1500), words[0])
        assertTrue(VoskResult.parseWords("{}").isEmpty())
        assertTrue(VoskResult.parseWords("no json").isEmpty())
    }

    // ---- Subtítulos

    private fun speech(seconds: Int, wps: Int = 3, text: (Int) -> String = { "palabra$it" }): List<WordTiming> {
        val step = 1000L / wps
        return (0 until seconds * wps).map { WordTiming(text(it), it * step, it * step + step - 40) }
    }

    @Test fun `agrupa palabras en bloques cortos`() {
        val cues = CueBuilder.build(speech(20))
        assertTrue(cues.isNotEmpty())
        assertTrue(cues.all { it.words.size <= 4 })
        assertTrue(cues.zipWithNext().all { (a, b) -> a.endMs <= b.startMs })
    }

    @Test fun `editar texto conserva o reparte tiempos`() {
        val cue = SubtitleCue(0, 3000, listOf(WordTiming("hla", 0, 1000), WordTiming("mundo", 1000, 3000)))
        val same = cue.withText("hola mundo")
        assertEquals(1000L, same.words[0].endMs)
        val more = cue.withText("hola a todo el mundo")
        assertEquals(5, more.words.size)
        assertEquals(3000L, more.words.last().endMs)
        assertTrue(cue.withText("   ").words.isEmpty())
    }

    // ---- Palabras clave / título

    @Test fun `palabras clave por frecuencia`() {
        val text = "el dinero es importante y el dinero cambia la vida porque el negocio genera dinero y negocio".split(" ")
        val kws = TextAnalysis.topKeywords(text, Language.ES, 2)
        assertEquals(listOf("dinero", "negocio"), kws)
        val title = TitleGenerator.generate(text, kws, Language.ES, 3, 1)
        assertTrue(title.contains("Dinero y Negocio"))
    }

    // ---- Algoritmo viral

    @Test fun `sugiere clips sin solapar y respeta duraciones`() {
        val words = speech(300)
        val clips = ClipAnalyzer.suggest(words, emptyList(), 300_000, Language.ES)
        assertTrue(clips.isNotEmpty())
        assertTrue(clips.size <= 8)
        clips.forEach {
            assertTrue("dur=${it.durationMs}", it.durationMs in 14_000..62_000)
            assertTrue(it.viralScore in 30..99)
        }
        val sorted = clips.sortedBy { it.startMs }
        sorted.zipWithNext().forEach { (a, b) -> assertTrue(a.endMs <= b.startMs + 0.25 * minOf(a.durationMs, b.durationMs)) }
    }

    @Test fun `el heatmap atrae los clips hacia el momento mas visto`() {
        val words = speech(300)
        val heat = (0 until 60).map { HeatPoint(it * 5000L, it * 5000L + 5000, if (it in 40..47) 1f else 0.1f) }
        val best = ClipAnalyzer.suggest(words, heat, 300_000, Language.ES).first()
        assertTrue("start=${best.startMs}", best.startMs in 170_000..235_000)
        assertTrue(best.heatScore > 60)
    }

    @Test fun `palabras gancho suben el puntaje`() {
        val boring = speech(120)
        val hooky = speech(120) { if (it in 100..160) "secreto" else "palabra$it" }
        val a = ClipAnalyzer.suggest(boring, emptyList(), 120_000, Language.ES).maxOf { it.viralScore }
        val b = ClipAnalyzer.suggest(hooky, emptyList(), 120_000, Language.ES).maxOf { it.viralScore }
        assertTrue("$a < $b", b > a)
    }

    @Test fun `sin transcripcion usa ventanas uniformes`() {
        val clips = ClipAnalyzer.suggest(emptyList(), emptyList(), 100_000, Language.EN)
        assertTrue(clips.isNotEmpty())
        val short = ClipAnalyzer.suggest(emptyList(), emptyList(), 8_000, Language.EN)
        assertEquals(1, short.size)
        assertEquals(8_000L, short[0].durationMs)
    }

    // ---- Encuadre

    @Test fun `encuadre centra el rostro y respeta limites`() {
        // 1920x1080 -> ventana 9:16 ocupa 0.316 del ancho.
        val centered = FramingMath.transform(1920, 1080, Framing(), 0.5f, 0.5f)
        assertEquals(0f, centered.tx, 1e-4f)
        val right = FramingMath.transform(1920, 1080, Framing(), 0.9f, null)
        assertTrue(right.tx < 0f)
        val edge = FramingMath.transform(1920, 1080, Framing(), 1.0f, null)
        assertEquals(-(1f - 0.31640625f), edge.tx, 1e-3f) // no se sale del fotograma
        val manual = FramingMath.transform(1920, 1080, Framing(autoTrack = false, offsetX = -1f), 0.9f, null)
        assertTrue(manual.tx > 0f)
        val zoom = FramingMath.transform(1920, 1080, Framing(zoom = 2f), 0.5f, 0.5f)
        assertEquals(2f, zoom.scale, 0f)
    }

    @Test fun `video vertical no se desplaza`() {
        val t = FramingMath.transform(1080, 1920, Framing(), 0.8f, 0.5f)
        assertEquals(0f, t.tx, 1e-4f)
    }

    @Test fun `trayectoria de rostro interpola y suaviza`() {
        val track = FaceTrack(listOf(FacePoint(0, 0.3f, 0.5f), FacePoint(1000, 0.31f, 0.5f), FacePoint(2000, 0.8f, 0.5f)))
        assertNotNull(track.xAt(500))
        assertEquals(0.3f, track.xAt(0)!!, 1e-4f)
        assertTrue(track.xAt(1000)!! < 0.35f) // zona muerta: ignora el temblor
        assertTrue(track.xAt(5000)!! > 0.3f)  // acaba persiguiendo
        assertNull(FaceTrack(emptyList()).xAt(10))
    }
}

class ClipRangeTest {
    @Test fun `respeta minimo maximo y limites`() {
        assertEquals(8_000L to 10_000L, ClipRange.clamp(9_500, 10_000, true, 60_000))
        assertEquals(10_000L to 12_000L, ClipRange.clamp(10_000, 10_500, false, 60_000))
        assertEquals(0L to 180_000L, ClipRange.clamp(0, 300_000, false, 400_000))
        assertEquals(0L to 5_000L, ClipRange.clamp(0, 5_000, true, 5_000))
        val tiny = ClipRange.clamp(0, 1_000, false, 1_000)
        assertEquals(0L to 1_000L, tiny)
    }
}

class LanguageTest {
    @Test fun `los 9 idiomas tienen modelo de voz propio y codigo unico`() {
        assertEquals(9, Language.entries.size)
        assertEquals(9, Language.entries.map { it.code }.toSet().size)
        assertEquals(9, Language.entries.map { it.voskModel }.toSet().size)
        assertTrue(Language.entries.all { it.modelUrl.startsWith("https://alphacephei.com/vosk/models/vosk-model-small-") })
        assertEquals(Language.ZH, Language.fromCode("zh"))
        assertNull(Language.fromCode("it"))
        assertEquals(Language.EN, Language.fromDevice("it")) // idioma no soportado -> inglés
        assertEquals(Language.HI, Language.fromDevice("hi"))
    }

    @Test fun `palabras clave y titulos en idiomas sin espacios y devanagari`() {
        val ru = "деньги важны деньги растут бизнес приносит деньги бизнес".split(" ")
        assertEquals(listOf("деньги", "бизнес"), TextAnalysis.topKeywords(ru, Language.RU, 2))
        val zh = "金钱 很 重要 金钱 改变 生活 生活 金钱".split(" ")
        val zhKw = TextAnalysis.topKeywords(zh, Language.ZH, 2)
        assertEquals(listOf("金钱", "生活"), zhKw)
        assertTrue(TitleGenerator.generate(zh, zhKw, Language.ZH, 0, 1).contains("金钱和生活"))
        val hi = "पैसा ज़रूरी है पैसा बढ़ता है व्यापार पैसा व्यापार".split(" ")
        assertEquals("पैसा", TextAnalysis.topKeywords(hi, Language.HI, 1).first()) // conserva las vocales (marcas)
        assertTrue(TextAnalysis.hookHits(listOf("秘密"), Language.JA) == 1)
    }

    @Test fun `subtitulos anchos usan bloques mas cortos`() {
        val words = (0 until 24).map { WordTiming("我们们们", it * 300L, it * 300L + 250) }
        val normal = CueBuilder.build(words)
        val wide = CueBuilder.build(words, wide = true)
        assertTrue(wide.size > normal.size)
    }
}
