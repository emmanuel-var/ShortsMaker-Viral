package com.shortsmaker.viral.domain

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Algoritmo de respaldo para sugerir clips usando ÚNICAMENTE la transcripción de Vosk (100 % local).
 * Se usa cuando no hay heatmap: video de la galería sin enlace, Twitch/Kick o scraping fallido.
 *
 * Genera ventanas de 15-60 s alineadas a frases y puntúa cada una (0..1) con cinco señales, todas relativas
 * al propio video (así funciona igual con oradores rápidos o lentos):
 *
 *  - **Densidad** (30 %): palabras por segundo de la ventana frente al promedio de habla del video, y poco silencio.
 *  - **Preguntas / exclamaciones** (20 %): frases con "?" / "!" (si el modelo los emite), que empiezan con una
 *    palabra interrogativa, o cargadas de palabras de énfasis (≥2 ganchos/emociones). Vosk no emite puntuación,
 *    por eso se infiere también por las palabras.
 *  - **Palabras clave repetidas** (25 %): apariciones de los temas más repetidos de TODO el video dentro de la ventana.
 *  - **Ganchos** (15 %): densidad de palabras gancho/emocionales y arranque con pregunta o gancho.
 *  - **Duración** (10 %): cercanía a ~35 s.
 */
object LocalTextClipGenerator {

    private const val TOP_TOPICS = 8
    private const val MIN_TOPIC_COUNT = 2

    fun generate(
        words: List<WordTiming>,
        durationMs: Long,
        lang: Language,
        config: AnalyzerConfig = AnalyzerConfig(),
    ): List<ClipSuggestion> {
        if (words.size < 8 || durationMs <= 0) return emptyList()
        val cfg = config.clamped(durationMs)
        val candidates = scoredCandidates(words, durationMs, lang, cfg)
        return ClipAnalyzer.toSuggestions(ClipAnalyzer.pickBest(candidates, cfg), words, lang)
    }

    /**
     * Todas las ventanas candidatas (alineadas a frases, 15-60 s) con su puntaje de TEXTO en `Candidate.score`.
     * Lo reutiliza `MultimediaClipGenerator` como componente de texto del puntaje multimedia.
     */
    internal fun scoredCandidates(
        words: List<WordTiming>,
        durationMs: Long,
        lang: Language,
        cfg: AnalyzerConfig,
    ): List<ClipAnalyzer.Candidate> {
        if (words.size < 8 || durationMs <= 0) return emptyList()
        val sentences = ClipAnalyzer.buildSentences(words)
        if (sentences.isEmpty()) return emptyList()
        val ctx = Context.build(words, sentences, lang)

        val candidates = mutableListOf<ClipAnalyzer.Candidate>()
        for (i in sentences.indices) {
            var j = i
            var perStart = 0
            while (j < sentences.size) {
                val dur = sentences[j].endMs - sentences[i].startMs
                if (dur > cfg.maxClipMs) break
                if (dur >= cfg.minClipMs && (perStart == 0 || abs(dur - cfg.targetClipMs) < cfg.targetClipMs / 3)) {
                    candidates += score(words, sentences, i, j, durationMs, ctx, lang, cfg)
                    if (++perStart >= 3) break
                }
                j++
            }
        }
        return candidates
    }

    /** Datos globales del video, calculados una sola vez. */
    private class Context(
        val avgWps: Float,
        val topics: Set<String>,
        val questionSentence: BooleanArray,
        val emphaticSentence: BooleanArray,
    ) {
        companion object {
            fun build(words: List<WordTiming>, sentences: List<ClipAnalyzer.Sentence>, lang: Language): Context {
                val speechSeconds = max(1f, sentences.sumOf { (it.endMs - it.startMs).toDouble() }.toFloat() / 1000f)
                val avgWps = words.size / speechSeconds

                // Temas: palabras clave que se repiten (≥2 veces) en todo el video.
                val all = words.map { it.text }
                val allTop = TextAnalysis.topKeywords(all, lang, TOP_TOPICS * 2)
                val counts = all.groupingBy { TextAnalysis.normalize(it) }.eachCount()
                val topics = allTop.filter { (counts[it] ?: 0) >= MIN_TOPIC_COUNT }.take(TOP_TOPICS).toSet()

                val q = BooleanArray(sentences.size)
                val e = BooleanArray(sentences.size)
                sentences.forEachIndexed { idx, s ->
                    val texts = words.subList(s.first, s.last + 1).map { it.text }
                    val last = texts.last()
                    q[idx] = last.endsWith("?") || last.endsWith("？") ||
                        (texts.size >= 3 && TextAnalysis.startsWithQuestion(texts, lang))
                    e[idx] = last.endsWith("!") || last.endsWith("！") || TextAnalysis.hookHits(texts, lang) >= 2
                }
                return Context(avgWps, topics, q, e)
            }
        }
    }

    private fun score(
        words: List<WordTiming>,
        sentences: List<ClipAnalyzer.Sentence>,
        i: Int,
        j: Int,
        durationMs: Long,
        ctx: Context,
        lang: Language,
        cfg: AnalyzerConfig,
    ): ClipAnalyzer.Candidate {
        val a = sentences[i]
        val b = sentences[j]
        val start = max(0L, a.startMs - 200)
        val end = min(durationMs, b.endMs + 300)
        val seconds = max(1f, (end - start) / 1000f)
        val span = words.subList(a.first, b.last + 1).map { it.text }

        // 1) Densidad de habla relativa al video + poco silencio.
        val speechMs = (i..j).sumOf { sentences[it].endMs - sentences[it].startMs }
        val wps = span.size / seconds
        val relative = if (ctx.avgWps > 0f) wps / ctx.avgWps else 1f
        val density = (((relative - 0.7f) / 0.8f).coerceIn(0f, 1f) * 0.7f) +
            ((speechMs.toFloat() / (end - start)).coerceIn(0f, 1f) * 0.3f)

        // 2) Preguntas y exclamaciones.
        var qe = 0
        for (k in i..j) if (ctx.questionSentence[k] || ctx.emphaticSentence[k]) qe++
        val questions = (qe / 2f).coerceIn(0f, 1f)

        // 3) Temas repetidos del video presentes en la ventana.
        val topicHits = if (ctx.topics.isEmpty()) 0 else span.count { TextAnalysis.normalize(it) in ctx.topics }
        val keywords = if (ctx.topics.isEmpty()) 0f else (topicHits / seconds / 0.25f).coerceIn(0f, 1f)

        // 4) Ganchos.
        val hookDensity = (TextAnalysis.hookHits(span, lang) / seconds / 0.35f).coerceIn(0f, 1f)
        val startHook = if (TextAnalysis.startsWithHook(span, lang)) 1f else 0f
        val hooks = 0.65f * hookDensity + 0.35f * startHook

        // 5) Duración.
        val length = (1f - abs((end - start) - cfg.targetClipMs).toFloat() / cfg.maxClipMs).coerceIn(0f, 1f)

        val score = 0.30f * density + 0.20f * questions + 0.25f * keywords + 0.15f * hooks + 0.10f * length
        return ClipAnalyzer.Candidate(start, end, a.first, b.last, heat = null, score = score.coerceIn(0f, 1f))
    }
}
