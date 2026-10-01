package com.shortsmaker.viral.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class Phase2Test {

    // ------------------------------------------------------------------ helpers

    /** Pista de energía: base −40 dB con variación determinista; `quiet` = −70 dB; `loud` = −10 dB. */
    private fun track(
        totalMs: Long,
        loud: List<LongRange> = emptyList(),
        quiet: List<LongRange> = emptyList(),
        hop: Int = 100,
    ): AudioEnergyTrack {
        val n = (totalMs / hop).toInt()
        val db = FloatArray(n) { i ->
            val t = i.toLong() * hop
            when {
                loud.any { t in it } -> -10f + (i % 3) * 0.3f
                quiet.any { t in it } -> -70f
                else -> -40f + ((i * 37) % 11 - 5) * 0.4f
            }
        }
        return AudioEnergyTrack.fromDb(hop, db)
    }

    private fun speech(seconds: Int, wps: Int = 3): List<WordTiming> {
        val out = mutableListOf<WordTiming>()
        var t = 0L; var s = 0
        while (t < seconds * 1000L) {
            for (w in 0 until 8) { out += WordTiming("tema${s}x$w", t, t + 1000L / wps - 30); t += 1000L / wps }
            t += 800; s++
        }
        return out
    }

    // ------------------------------------------------------------------ Audio Radar

    @Test fun `el acumulador convierte PCM en dB y el perfil detecta picos`() {
        val sr = 16_000
        val acc = AudioEnergyAccumulator(sr, 100)
        fun block(amp: Int, ms: Int) = repeat(ms / 100) { acc.addSamples(ShortArray(1600) { if (it % 2 == 0) amp.toShort() else (-amp).toShort() }) }
        block(300, 20_000); block(24_000, 2_000); block(300, 20_000)
        val t = acc.finish()
        assertEquals(420, t.size)
        assertTrue(abs(t.db(10) - (20 * Math.log10(300.0 / 32768))) < 0.5)   // ≈ −40.8 dB
        assertTrue(t.db(205) > -5f)                                           // ≈ −2.7 dB
        val p = AudioProfile(t)
        assertTrue(p.isPeakAt(20_500))
        assertFalse(p.isPeakAt(5_000))
        assertTrue(p.score(19_000, 23_000) > p.score(2_000, 6_000))
    }

    @Test fun `PCM16 little endian y silencio digital`() {
        val acc = AudioEnergyAccumulator(1000, 100) // 100 muestras por trozo
        val bytes = ByteArray(200)                  // 100 muestras de silencio
        acc.addPcm16(bytes)
        val t = acc.finish()
        assertEquals(1, t.size); assertEquals(-100f, t.db(0), 0.5f)
    }

    @Test fun `detecta contraste de silencio seguido de ruido fuerte`() {
        val t = track(60_000, loud = listOf(30_000L..31_500L), quiet = listOf(27_000L..29_900L))
        val p = AudioProfile(t)
        assertEquals(1, p.contrastEvents(25_000, 35_000))
        assertEquals(0, p.contrastEvents(0, 20_000))
        // un grito sin silencio previo da pico pero NO contraste
        val p2 = AudioProfile(track(60_000, loud = listOf(30_000L..31_500L)))
        assertEquals(0, p2.contrastEvents(25_000, 35_000))
        assertTrue(p2.peakFraction(29_000, 33_000) > 0.2f)
        // el contraste puntúa más que el simple grito
        assertTrue(p.score(25_000, 35_000) > p2.score(25_000, 35_000))
    }

    @Test fun `audio plano no tiene picos`() {
        val p = AudioProfile(track(120_000))
        assertEquals(0f, p.peakFraction(0, 120_000), 0.001f)
        assertTrue(p.score(0, 60_000) < 0.2f)
    }

    @Test fun `los 5 picos mas fuertes de 4 horas estan separados`() {
        val total = 4 * 3600_000L
        val bursts = listOf(600_000L, 2_400_000L, 5_000_000L, 7_800_000L, 11_000_000L, 13_000_000L)
        val p = AudioProfile(track(total, loud = bursts.map { it..it + 3_000 }))
        val peaks = p.topPeaks(5, 120_000)
        assertEquals(5, peaks.size)
        assertTrue(peaks.zipWithNext().all { (a, b) -> b.timeMs - a.timeMs >= 120_000 })
        assertTrue(peaks.all { pk -> bursts.any { pk.timeMs in it..it + 3_100 } })
    }

    @Test fun `modo rapido planifica ventanas de 2 minutos alrededor de los picos`() {
        val total = 4 * 3600_000L
        val bursts = listOf(10_000L, 3_600_000L, 7_200_000L, 10_800_000L, 14_390_000L)
        val p = AudioProfile(track(total, loud = bursts.map { it..it + 2_000 }))
        val spans = FastModePlanner.plan(p, total)
        assertEquals(5, spans.size)
        assertTrue(spans.all { it.endMs - it.startMs == FastModePlanner.WINDOW_MS })
        assertTrue(spans.zipWithNext().all { (a, b) -> a.endMs <= b.startMs })
        assertTrue(spans.first().startMs == 0L)                    // pico al inicio: ventana recortada al límite
        assertTrue(spans.last().endMs == total)                    // pico al final
        assertTrue(spans.sumOf { it.endMs - it.startMs } == 600_000L) // 10 min de 4 h
    }

    @Test fun `el recortador previo limita el fragmento a 15 minutos`() {
        val dur = 4 * 3600_000L
        assertEquals(TimeSpan(0, 900_000), LongVideo.clampTrim(0, 2_000_000, false, dur))      // arrastró el final: se acorta
        assertEquals(TimeSpan(1_100_000, 2_000_000), LongVideo.clampTrim(0, 2_000_000, true, dur)) // arrastró el inicio
        assertEquals(TimeSpan(100_000, 105_000), LongVideo.clampTrim(100_000, 101_000, false, dur)) // mínimo 5 s
        assertEquals(TimeSpan(0, 20_000), LongVideo.clampTrim(-5, 20_000, true, dur))
        assertEquals(TimeSpan(dur - 5_000, dur), LongVideo.clampTrim(dur - 1_000, dur, true, dur))
    }

    @Test fun `fusiona tramos transcritos que se tocan`() {
        val merged = TimeSpan.merge(listOf(TimeSpan(300, 400), TimeSpan(0, 100), TimeSpan(100, 250), TimeSpan(260, 290)))
        assertEquals(listOf(TimeSpan(0, 250), TimeSpan(260, 290), TimeSpan(300, 400)), merged)
        assertTrue(TimeSpan(0, 250).covers(10, 200)); assertFalse(TimeSpan(0, 250).covers(200, 300))
    }

    @Test fun `umbral de video largo`() {
        assertFalse(LongVideo.isLong(15 * 60_000L))
        assertTrue(LongVideo.isLong(15 * 60_000L + 1))
    }

    // ------------------------------------------------------------------ Movimiento

    @Test fun `el movimiento brusco puntua mas que un rostro quieto`() {
        val still = (0..60).map { FacePoint(it * 1000L, 0.5f + (it % 2) * 0.002f, 0.5f, 0.2f) }
        val moving = (0..60).map { FacePoint(it * 1000L, 0.5f + (if (it in 20..40) (it % 2) * 0.25f else 0f), 0.5f, 0.2f + (if (it in 20..40) (it % 3) * 0.05f else 0f)) }
        val ms = MotionTrack(moving)
        assertTrue(ms.score(20_000, 40_000)!! > ms.score(0, 15_000)!!)
        assertTrue(ms.score(20_000, 40_000)!! > 0.5f)
        assertNull(MotionTrack(still).score(200_000, 210_000)) // sin muestras en ese tramo
        assertTrue(MotionTrack(emptyList()).isEmpty)
    }

    @Test fun `huecos sin rostro no cuentan como movimiento`() {
        val pts = listOf(FacePoint(0, 0.1f, 0.5f), FacePoint(10_000, 0.9f, 0.5f), FacePoint(11_000, 0.9f, 0.5f), FacePoint(12_000, 0.9f, 0.5f), FacePoint(13_000, 0.9f, 0.5f))
        assertTrue(MotionTrack(pts).score(0, 20_000)!! < 0.3f)
    }

    // ------------------------------------------------------------------ Chat

    @Test fun `el chat detecta rafagas de hype y se ignora si hay pocos mensajes`() {
        val msgs = mutableListOf<ChatMessage>()
        for (i in 0 until 120) msgs += ChatMessage(i * 5_000L + 100, "hola ${i}")             // ritmo base: 1 msg / 5 s
        for (i in 0 until 60) msgs += ChatMessage(300_000L + i * 100, if (i % 2 == 0) "LUL" else "KEKW") // ráfaga a los 5:00
        val chat = ChatActivity.from(msgs, 600_000)!!
        assertTrue(chat.score(295_000, 315_000) > 0.9f)
        assertTrue(chat.score(0, 60_000) < 0.4f)
        assertNull(ChatActivity.from(msgs.take(10), 600_000))
        assertEquals(2f, ChatHype.weight("jajajaja que bueno"), 0f)
        assertEquals(2f, ChatHype.weight("PogChamp"), 0f)
        assertEquals(1f, ChatHype.weight("buenas tardes"), 0f)
    }

    @Test fun `parsea mensajes del DOM de la repeticion del chat y devuelve vacio si no hay`() {
        val html = """
            <div class="vod-message"><span data-a-target="chat-timestamp" class="x">1:02:03</span>
              <span data-a-target="chat-message-text">jajaja</span> <img class="chat-image" alt="KEKW"></div>
            <div class="vod-message"><span data-a-target="chat-timestamp">12:34</span>
              <span data-a-target="chat-message-text">qué &amp; crack</span></div>"""
        val m = ChatDomParser.parse(html)
        assertEquals(2, m.size)
        assertEquals(3_723_000L, m[0].offsetMs); assertTrue(m[0].text.contains("jajaja") && m[0].text.contains("KEKW"))
        assertEquals(754_000L, m[1].offsetMs); assertEquals("qué & crack", m[1].text)
        assertTrue(ChatDomParser.parse("<html><body>nada</body></html>").isEmpty())
        assertNull(ChatDomParser.parseClock("xx:yy"))
    }

    // ------------------------------------------------------------------ MultimediaClipGenerator

    @Test fun `el audio vale 40 por ciento y lo que falta pasa al texto`() {
        val all = ScoreWeights.of(true, true, true)
        assertEquals(0.40f, all.audio, 1e-6f); assertEquals(0.30f, all.text, 1e-6f)
        assertEquals(1f, all.text + all.audio + all.motion + all.chat, 1e-6f)
        val audioOnly = ScoreWeights.of(true, false, false)
        assertEquals(0.40f, audioOnly.audio, 1e-6f); assertEquals(0.60f, audioOnly.text, 1e-6f)
        assertEquals(1f, ScoreWeights.of(false, false, false).text, 1e-6f)
    }

    @Test fun `un pico de audio mueve el mejor clip aunque el texto sea uniforme`() {
        val words = speech(300)
        val audio = AudioProfile(track(300_000, loud = listOf(200_000L..203_000L), quiet = listOf(197_000L..199_900L)))
        val clips = MultimediaClipGenerator.generate(MediaSignals(words, 300_000, audio), Language.ES)
        assertTrue(clips.isNotEmpty())
        val best = clips.maxByOrNull { it.viralScore }!!
        assertTrue("best=${best.startMs}-${best.endMs}", best.startMs <= 203_000 && best.endMs >= 200_000)
        assertTrue(best.audioScore > 50)
        clips.sortedBy { it.startMs }.zipWithNext().forEach { (a, b) -> assertTrue(a.endMs <= b.startMs + 0.25 * minOf(a.durationMs, b.durationMs)) }
        assertTrue(clips.all { it.durationMs in 14_000..62_000 && it.viralScore in 30..99 })
    }

    @Test fun `la rafaga del chat y el movimiento influyen en el resultado`() {
        val words = speech(300)
        val msgs = (0 until 100).map { ChatMessage(it * 3_000L, "hola") } + (0 until 80).map { ChatMessage(100_000L + it * 100, "LUL") }
        val chat = ChatActivity.from(msgs, 300_000)
        val withChat = MultimediaClipGenerator.generate(MediaSignals(words, 300_000, null, null, chat), Language.ES)
        assertTrue(withChat.maxByOrNull { it.viralScore }!!.let { it.startMs <= 108_000 && it.endMs >= 100_000 })
        assertTrue(withChat.any { it.chatScore > 50 })

        // movimiento: dos ventanas con el MISMO puntaje de texto; sólo la segunda tiene mucho movimiento del rostro
        val pts = (0..299).map { sec ->
            val moving = sec in 200..260
            FacePoint(sec * 1000L, 0.5f + if (moving) (sec % 2) * 0.3f else 0.001f * (sec % 2), 0.5f, 0.2f + if (moving) (sec % 3) * 0.06f else 0f)
        }
        val signals = MediaSignals(words, 300_000, null, MotionTrack(pts))
        val pre = listOf(
            ScoredWindow(20_000, 50_000, 0, 10, text = 0.5f, audio = null, chat = null),
            ScoredWindow(210_000, 240_000, 100, 110, text = 0.5f, audio = null, chat = null),
        )
        val clips = MultimediaClipGenerator.finalize(pre, signals, Language.ES)
        assertEquals(210_000L, clips.maxByOrNull { it.viralScore }!!.startMs)
        assertTrue(clips.first { it.startMs == 210_000L }.motionScore > 40)
        assertEquals(0, clips.first { it.startMs == 20_000L }.motionScore)
    }

    @Test fun `sin voz pero con audio se sugieren ventanas uniformes por audio`() {
        val audio = AudioProfile(track(240_000, loud = listOf(150_000L..153_000L), quiet = listOf(147_000L..149_900L)))
        val clips = MultimediaClipGenerator.generate(MediaSignals(emptyList(), 240_000, audio), Language.EN)
        assertTrue(clips.isNotEmpty())
        val best = clips.maxByOrNull { it.viralScore }!!
        assertTrue(best.startMs <= 153_000 && best.endMs >= 150_000)
        assertTrue(MultimediaClipGenerator.generate(MediaSignals(emptyList(), 240_000), Language.EN).isEmpty())
    }

    @Test fun `solo texto equivale al generador local`() {
        val words = speech(200)
        val a = MultimediaClipGenerator.generate(MediaSignals(words, 200_000), Language.ES)
        val b = LocalTextClipGenerator.generate(words, 200_000, Language.ES)
        assertEquals(b.map { it.startMs to it.endMs }, a.map { it.startMs to it.endMs })
    }

    // ------------------------------------------------------------------ Efectos de render

    @Test fun `pop in rebota y se asienta`() {
        assertEquals(0.6f, PopAnimation.scale(-5), 1e-6f)
        assertTrue(PopAnimation.scale(120) > 1.1f)           // sobrepasa
        assertEquals(1f, PopAnimation.scale(PopAnimation.DURATION_MS), 1e-6f)
        assertEquals(1f, PopAnimation.scale(2000), 0f)
        assertEquals(0f, PopAnimation.alpha(0), 0f); assertEquals(1f, PopAnimation.alpha(500), 0f)
        assertTrue((0..230 step 10).map { PopAnimation.scale(it.toLong()) }.all { it in 0.59f..1.19f })
    }

    @Test fun `punch in sube y baja suavemente`() {
        val ev = listOf(PunchEvent(10_000))
        assertEquals(1f, PunchIn.zoomAt(ev, 9_999), 0f)
        assertEquals(1f, PunchIn.zoomAt(ev, 10_000), 1e-4f)
        assertEquals(PunchIn.MAX_ZOOM, PunchIn.zoomAt(ev, 10_800), 1e-4f)
        assertTrue(PunchIn.zoomAt(ev, 11_500) < PunchIn.MAX_ZOOM)
        assertEquals(1f, PunchIn.zoomAt(ev, 11_700), 1e-4f)
        assertEquals(1f, PunchIn.zoomAt(emptyList(), 10_000), 0f)
    }

    @Test fun `plan de punch in usa picos de audio y palabras clave con separacion`() {
        val audio = AudioProfile(track(60_000, loud = listOf(20_000L..21_500L)))
        val words = listOf(
            WordTiming("hola", 1_000, 1_300), WordTiming("dinero", 5_000, 5_400), WordTiming("dinero", 6_000, 6_300),
            WordTiming("fuego", 30_000, 30_400), WordTiming("mesa", 40_000, 40_300),
        )
        val ev = PunchIn.plan(0, 60_000, audio, words)
        assertEquals(listOf(5_000L, 20_000L, 30_000L), ev.map { it.startMs }) // 6 000 se descarta por cercanía
        assertTrue(PunchIn.plan(0, 60_000, null, emptyList()).isEmpty())
    }

    @Test fun `sfx whoosh en punch in y pop en palabras clave sin amontonarse`() {
        val punches = listOf(PunchEvent(5_000), PunchEvent(30_000))
        val words = listOf(WordTiming("dinero", 5_050, 5_300), WordTiming("fuego", 12_000, 12_300), WordTiming("mesa", 15_000, 15_200))
        val sfx = SfxPlanner.plan(0, 60_000, punches, words)
        assertEquals(listOf(SfxType.WHOOSH, SfxType.POP, SfxType.WHOOSH), sfx.map { it.type })
        assertEquals(4_920L, sfx[0].timeMs)
        assertTrue(sfx.zipWithNext().all { (a, b) -> b.timeMs - a.timeMs >= 900 })
    }

    @Test fun `diccionario de enfasis pinta de rojo y pone emojis`() {
        val fuego = WordEmphasis.of("¡Fuego!")!!
        assertEquals("🔥", fuego.emoji); assertEquals(0xFFFF3B30.toInt(), fuego.color)
        val money = WordEmphasis.of("Dinero,")!!
        assertEquals("💰", money.emoji); assertNotNull(money.color)
        assertEquals("❌", WordEmphasis.of("error")?.emoji)
        assertEquals("⭐", WordEmphasis.of("mejor")?.emoji); assertNull(WordEmphasis.of("mejor")?.color)
        assertNull(WordEmphasis.of("casualidad")); assertNull(WordEmphasis.of(""))
    }

    @Test fun `barra de progreso y encuadre dividido`() {
        assertEquals(0.5f, ProgressBarMath.progress(15_000, 30_000), 1e-6f)
        assertEquals(1f, ProgressBarMath.progress(99_000, 30_000), 0f)
        assertEquals(0f, ProgressBarMath.progress(5, 0), 0f)
        // mitad superior 9:8 de un 1920x1080: ventana más ancha que la de 9:16
        val (w916, _) = FramingMath.baseWindow(1920, 1080)
        val (w98, _) = FramingMath.baseWindow(1920, 1080, FramingMath.SPLIT_ASPECT)
        assertTrue(w98 > w916 * 1.9f)
        // punch in multiplica el zoom manual y el rostro sigue centrado
        val t = FramingMath.transform(1920, 1080, Framing(zoom = 1f), 0.5f, 0.5f, extraZoom = 1.12f)
        assertEquals(1.12f, t.scale, 1e-6f); assertEquals(0f, t.tx, 1e-4f)
    }

    @Test fun `ediciones antiguas se leen con los campos nuevos por defecto`() {
        val json = """{"clipId":"c","startMs":0,"endMs":1000}"""
        val e = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.decodeFromString(ClipEdit.serializer(), json)
        assertEquals(ComposeLayout.FULL, e.layout); assertFalse(e.autoZoom); assertFalse(e.sfx); assertFalse(e.progressBar)
        assertTrue(SubtitleTemplates.byId("hormozi").popIn)
        assertEquals(SubtitleMode.KARAOKE, SubtitleTemplates.byId("hormozi").mode)
        assertFalse(SubtitleTemplates.byId("classic").popIn)
    }
}
