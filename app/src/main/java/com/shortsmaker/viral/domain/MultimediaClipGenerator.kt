package com.shortsmaker.viral.domain

import kotlin.math.max
import kotlin.math.min

/** Señales disponibles para puntuar clips. Todo es opcional: lo que falte se ignora. */
data class MediaSignals(
    val words: List<WordTiming>,
    val durationMs: Long,
    val audio: AudioProfile? = null,
    val motion: MotionTrack? = null,
    val chat: ChatActivity? = null,
)

/** Pesos del Puntaje Viral. El audio vale siempre 40 % si existe; lo que no esté disponible pasa al texto. */
data class ScoreWeights(val text: Float, val audio: Float, val motion: Float, val chat: Float) {
    companion object {
        const val AUDIO = 0.40f
        const val MOTION = 0.15f
        const val CHAT = 0.15f

        fun of(hasAudio: Boolean, hasMotion: Boolean, hasChat: Boolean): ScoreWeights {
            val a = if (hasAudio) AUDIO else 0f
            val m = if (hasMotion) MOTION else 0f
            val c = if (hasChat) CHAT else 0f
            return ScoreWeights(text = 1f - a - m - c, audio = a, motion = m, chat = c)
        }
    }
}

/** Ventana candidata con el desglose de sus puntajes (0..1). `audio`/`chat`/`motion` null = sin dato. */
data class ScoredWindow(
    val startMs: Long,
    val endMs: Long,
    val firstWord: Int,
    val lastWord: Int,
    val text: Float,
    val audio: Float?,
    val chat: Float?,
    val motion: Float? = null,
) {
    fun total(w: ScoreWeights): Float =
        (w.text * text + w.audio * (audio ?: 0f) + w.chat * (chat ?: 0f) + w.motion * (motion ?: 0f)).coerceIn(0f, 1f)
}

/**
 * Analizador multimedia: combina TEXTO (densidad, preguntas, temas repetidos, ganchos), AUDIO (picos y contrastes
 * "silencio → explosión"), MOVIMIENTO del rostro y CHAT del VOD. Todo local.
 *
 * Se hace en dos etapas para ahorrar batería: (A) se puntúan TODAS las ventanas con texto + audio + chat, que son baratos;
 * (B) sólo para las mejores se mide el movimiento del rostro (MediaPipe es caro) y se calcula el puntaje final.
 */
object MultimediaClipGenerator {
    const val PRESELECT = 10

    /** Todo en una llamada (sin etapa asíncrona): `signals.motion` ya debe contener las muestras necesarias. */
    fun generate(signals: MediaSignals, lang: Language, config: AnalyzerConfig = AnalyzerConfig()): List<ClipSuggestion> {
        val cfg = config.clamped(signals.durationMs)
        val pre = preselect(signals, lang, cfg, PRESELECT)
        return finalize(pre, signals, lang, cfg)
    }

    /** Etapa A. */
    fun preselect(signals: MediaSignals, lang: Language, cfg: AnalyzerConfig, count: Int = PRESELECT): List<ScoredWindow> {
        val hasAudio = signals.audio != null
        val hasChat = signals.chat != null
        val textCandidates = LocalTextClipGenerator.scoredCandidates(signals.words, signals.durationMs, lang, cfg)

        val windows: List<ScoredWindow> = if (textCandidates.isNotEmpty()) {
            textCandidates.map { c ->
                ScoredWindow(
                    c.startMs, c.endMs, c.firstWord, c.lastWord, text = c.score,
                    audio = signals.audio?.score(c.startMs, c.endMs),
                    chat = signals.chat?.score(c.startMs, c.endMs),
                )
            }
        } else {
            // Sin voz (p. ej. gameplay): se recorre el video con ventanas uniformes puntuadas sólo por audio/chat.
            uniformWindows(signals, cfg)
        }
        if (windows.isEmpty()) return emptyList()

        val w = ScoreWeights.of(hasAudio, hasMotion = false, hasChat = hasChat)
        val asCandidates = windows.map { ClipAnalyzer.Candidate(it.startMs, it.endMs, it.firstWord, it.lastWord, null, it.total(w)) }
        val chosen = ClipAnalyzer.pickBest(asCandidates, cfg.copy(maxClips = count))
        return chosen.map { c -> windows.first { it.startMs == c.startMs && it.endMs == c.endMs } }
    }

    /** Etapa B: añade el movimiento, calcula el puntaje final y devuelve los mejores clips sin solaparse. */
    fun finalize(pre: List<ScoredWindow>, signals: MediaSignals, lang: Language, config: AnalyzerConfig = AnalyzerConfig()): List<ClipSuggestion> {
        if (pre.isEmpty()) return emptyList()
        val cfg = config.clamped(signals.durationMs)
        val motion = signals.motion?.takeUnless { it.isEmpty }
        val withMotion = pre.map { it.copy(motion = motion?.let { m -> m.score(it.startMs, it.endMs) ?: 0f }) }
        val w = ScoreWeights.of(signals.audio != null, motion != null, signals.chat != null)

        val candidates = withMotion.map {
            ClipAnalyzer.Candidate(it.startMs, it.endMs, it.firstWord, it.lastWord, null, it.total(w))
        }
        val picked = ClipAnalyzer.pickBest(candidates, cfg)
        val suggestions = ClipAnalyzer.toSuggestions(picked, signals.words, lang)
        return suggestions.zip(picked).map { (s, c) ->
            val sw = withMotion.first { it.startMs == c.startMs && it.endMs == c.endMs }
            s.copy(
                audioScore = ((sw.audio ?: 0f) * 100).toInt(),
                motionScore = ((sw.motion ?: 0f) * 100).toInt(),
                chatScore = ((sw.chat ?: 0f) * 100).toInt(),
            )
        }
    }

    private fun uniformWindows(signals: MediaSignals, cfg: AnalyzerConfig): List<ScoredWindow> {
        if (signals.audio == null && signals.chat == null) return emptyList()
        val len = min(cfg.targetClipMs, signals.durationMs)
        val step = max(5_000L, len / 3)
        val out = ArrayList<ScoredWindow>()
        var s = 0L
        while (s < signals.durationMs) {
            val e = min(signals.durationMs, s + len)
            if (e - s >= min(cfg.minClipMs, signals.durationMs)) {
                out += ScoredWindow(s, e, -1, -1, text = 0f, audio = signals.audio?.score(s, e), chat = signals.chat?.score(s, e))
            }
            if (e >= signals.durationMs) break
            s += step
        }
        return out
    }
}
