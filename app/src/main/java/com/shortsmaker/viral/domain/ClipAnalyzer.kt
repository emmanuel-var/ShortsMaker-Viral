package com.shortsmaker.viral.domain

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class AnalyzerConfig(
    val minClipMs: Long = 15_000,
    val maxClipMs: Long = 60_000,
    val targetClipMs: Long = 35_000,
    val maxClips: Int = 8,
    /** Máximo solapamiento permitido entre dos clips sugeridos (fracción del más corto). */
    val maxOverlap: Float = 0.25f,
)

/**
 * Algoritmo de recomendación de clips.
 *
 * 1. Divide la transcripción en frases (pausas largas / puntuación).
 * 2. Genera ventanas candidatas de 15-60 s que empiezan y terminan en límites de frase.
 * 3. Puntúa cada ventana mezclando: heatmap de YouTube (momentos más vistos), densidad de palabras gancho,
 *    gancho inicial (pregunta o palabra fuerte), ritmo del habla y cercanía a la duración ideal.
 * 4. Selecciona los mejores clips sin solaparse.
 */
object ClipAnalyzer {

    internal data class Sentence(val startMs: Long, val endMs: Long, val first: Int, val last: Int)

    internal data class Candidate(
        val startMs: Long,
        val endMs: Long,
        val firstWord: Int,
        val lastWord: Int,
        val heat: Float?,
        val score: Float,
    )

    fun suggest(
        words: List<WordTiming>,
        heatmap: List<HeatPoint>,
        durationMs: Long,
        lang: Language,
        config: AnalyzerConfig = AnalyzerConfig(),
    ): List<ClipSuggestion> {
        if (durationMs <= 0) return emptyList()
        val cfg = config.copy(
            minClipMs = min(config.minClipMs, durationMs),
            maxClipMs = min(config.maxClipMs, durationMs),
            targetClipMs = min(config.targetClipMs, durationMs),
        )
        val heatNorm = normalizeHeat(heatmap)

        // Sin heatmap (video local, Twitch/Kick o scraping fallido): algoritmo de respaldo sólo con la transcripción.
        if (heatNorm == null && words.size >= 8) {
            val local = LocalTextClipGenerator.generate(words, durationMs, lang, cfg)
            if (local.isNotEmpty()) return local
        }

        val candidates = when {
            words.size >= 8 -> textCandidates(words, heatNorm, durationMs, lang, cfg)
            else -> emptyList()
        }.ifEmpty { heatOrUniformCandidates(heatNorm, durationMs, cfg) }

        return toSuggestions(pickBest(candidates, cfg), words, lang)
    }

    internal fun toSuggestions(selected: List<Candidate>, words: List<WordTiming>, lang: Language): List<ClipSuggestion> {
        return selected.mapIndexed { i, c ->
            val clipWords = if (c.firstWord >= 0 && c.lastWord >= c.firstWord) words.subList(c.firstWord, c.lastWord + 1) else emptyList()
            val texts = clipWords.map { it.text }
            val keywords = TextAnalysis.topKeywords(texts, lang, 3)
            ClipSuggestion(
                id = "clip_${c.startMs}_${c.endMs}",
                title = TitleGenerator.generate(texts, keywords, lang, (c.startMs / 1000).toInt(), i + 1),
                startMs = c.startMs,
                endMs = c.endMs,
                viralScore = (40 + 59 * c.score).toInt().coerceIn(30, 99),
                heatScore = c.heat?.let { (it * 100).toInt().coerceIn(0, 100) } ?: 0,
                preview = texts.take(if (lang.spaced) 14 else 30).joinToString(if (lang.spaced) " " else ""),
                keywords = keywords,
            )
        }
    }

    // ---------------------------------------------------------------- candidatos por texto

    internal fun buildSentences(words: List<WordTiming>): List<Sentence> {
        val result = mutableListOf<Sentence>()
        var first = 0
        for (i in words.indices) {
            val w = words[i]
            val next = words.getOrNull(i + 1)
            val gap = if (next != null) next.startMs - w.endMs else Long.MAX_VALUE
            val punct = w.text.endsWith(".") || w.text.endsWith("?") || w.text.endsWith("!")
            val tooLong = w.endMs - words[first].startMs > 9_000
            if (next == null || gap >= 600 || punct || tooLong) {
                result += Sentence(words[first].startMs, w.endMs, first, i)
                first = i + 1
            }
        }
        return result
    }

    private fun textCandidates(
        words: List<WordTiming>,
        heat: HeatNorm?,
        durationMs: Long,
        lang: Language,
        cfg: AnalyzerConfig,
    ): List<Candidate> {
        val sentences = buildSentences(words)
        val out = mutableListOf<Candidate>()
        for (i in sentences.indices) {
            var j = i
            var perStart = 0
            while (j < sentences.size) {
                val dur = sentences[j].endMs - sentences[i].startMs
                if (dur > cfg.maxClipMs) break
                if (dur >= cfg.minClipMs) {
                    // Máximo 3 ventanas por inicio: la primera válida y las que rodean la duración ideal.
                    if (perStart == 0 || abs(dur - cfg.targetClipMs) < cfg.targetClipMs / 3) {
                        out += scoreWindow(words, sentences[i], sentences[j], heat, durationMs, lang, cfg)
                        perStart++
                        if (perStart >= 3) break
                    }
                }
                j++
            }
        }
        return out
    }

    private fun scoreWindow(
        words: List<WordTiming>,
        a: Sentence,
        b: Sentence,
        heat: HeatNorm?,
        durationMs: Long,
        lang: Language,
        cfg: AnalyzerConfig,
    ): Candidate {
        val start = max(0L, a.startMs - 200)
        val end = min(durationMs, b.endMs + 300)
        val span = words.subList(a.first, b.last + 1).map { it.text }
        val seconds = max(1f, (end - start) / 1000f)

        val hooks = TextAnalysis.hookHits(span, lang)
        val hookScore = (hooks / seconds / 0.35f).coerceIn(0f, 1f)           // ~1 gancho cada 3 s = máximo
        val startHook = if (TextAnalysis.startsWithHook(span, lang)) 1f else 0f
        val wps = span.size / seconds
        val pace = (1f - abs(wps - 2.7f) / 2.7f).coerceIn(0f, 1f)            // ritmo ideal ≈ 2.7 palabras/s
        val length = (1f - abs((end - start) - cfg.targetClipMs).toFloat() / cfg.maxClipMs).coerceIn(0f, 1f)
        val h = heat?.score(start, end)

        val score = if (h != null) {
            0.42f * h + 0.24f * hookScore + 0.14f * startHook + 0.12f * pace + 0.08f * length
        } else {
            0.40f * hookScore + 0.25f * startHook + 0.22f * pace + 0.13f * length
        }
        return Candidate(start, end, a.first, b.last, h, score.coerceIn(0f, 1f))
    }

    // ---------------------------------------------------------------- candidatos sin texto

    private fun heatOrUniformCandidates(heat: HeatNorm?, durationMs: Long, cfg: AnalyzerConfig): List<Candidate> {
        val len = min(cfg.targetClipMs, durationMs)
        val step = max(5_000L, len / 2)
        val out = mutableListOf<Candidate>()
        var s = 0L
        while (s + len <= durationMs || s == 0L) {
            val e = min(durationMs, s + len)
            val h = heat?.score(s, e)
            out += Candidate(s, e, -1, -1, h, if (h != null) h else 0.35f)
            if (e >= durationMs) break
            s += step
        }
        return out
    }

    // ---------------------------------------------------------------- selección

    internal fun pickBest(candidates: List<Candidate>, cfg: AnalyzerConfig): List<Candidate> {
        val chosen = mutableListOf<Candidate>()
        for (c in candidates.sortedByDescending { it.score }) {
            if (chosen.size >= cfg.maxClips) break
            val clash = chosen.any { overlapFraction(it, c) > cfg.maxOverlap }
            if (!clash) chosen += c
        }
        return chosen
    }

    private fun overlapFraction(a: Candidate, b: Candidate): Float {
        val overlap = min(a.endMs, b.endMs) - max(a.startMs, b.startMs)
        if (overlap <= 0) return 0f
        return overlap.toFloat() / min(a.endMs - a.startMs, b.endMs - b.startMs).coerceAtLeast(1)
    }

    // ---------------------------------------------------------------- heatmap

    class HeatNorm(private val points: List<HeatPoint>, private val lo: Float, private val hi: Float) {
        private fun norm(v: Float) = if (hi - lo < 1e-4f) 0.5f else ((v - lo) / (hi - lo)).coerceIn(0f, 1f)

        /** Mezcla de la media ponderada por tiempo y del pico dentro de la ventana, normalizada al rango del video. */
        fun score(startMs: Long, endMs: Long): Float {
            var weighted = 0f
            var covered = 0L
            var peak = 0f
            for (p in points) {
                val o = min(endMs, p.endMs) - max(startMs, p.startMs)
                if (o <= 0) continue
                weighted += norm(p.value) * o
                covered += o
                peak = max(peak, norm(p.value))
            }
            if (covered == 0L) return 0f
            return 0.6f * (weighted / covered) + 0.4f * peak
        }
    }

    fun normalizeHeat(heatmap: List<HeatPoint>): HeatNorm? {
        if (heatmap.isEmpty()) return null
        return HeatNorm(heatmap, heatmap.minOf { it.value }, heatmap.maxOf { it.value })
    }
}
