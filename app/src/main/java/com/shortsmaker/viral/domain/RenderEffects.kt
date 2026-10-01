package com.shortsmaker.viral.domain

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Diccionario local (en memoria) de palabras con énfasis: color y/o emoji. */
object WordEmphasis {
    data class Emphasis(val emoji: String?, val color: Int?)

    private val RED = 0xFFFF3B30.toInt()
    private val GREEN = 0xFF00E676.toInt()

    private val FIRE = setOf(
        "fuego", "fire", "feu", "feuer", "fogo", "огонь", "火", "火事", "आग", "peligro", "danger", "gefahr", "perigo",
        "опасно", "危险", "危険", "खतरा", "error", "errores", "mistake", "erreur", "fehler", "erro", "ошибка", "错误", "失敗", "गलती",
        "cuidado", "warning", "attention", "achtung", "cuidado", "осторожно", "小心", "注意",
    )
    private val MONEY = setOf(
        "dinero", "plata", "millones", "millón", "money", "cash", "million", "millions", "dollars", "dólares", "argent",
        "geld", "dinheiro", "деньги", "钱", "金钱", "お金", "पैसा", "पैसे", "gratis", "free", "gratuit", "kostenlos", "бесплатно", "免费", "無料", "मुफ्त",
    )

    /** Emoji "principal" para cada familia (los demás salen de `TextAnalysis.emojiFor`). */
    private fun emojiFor(norm: String): String? = when (norm) {
        in FIRE -> if (norm in setOf("error", "errores", "mistake", "erreur", "fehler", "erro", "ошибка", "错误", "失敗", "गलती")) "❌"
        else if (norm in setOf("peligro", "danger", "gefahr", "perigo", "опасно", "危险", "危険", "खतरा", "cuidado", "warning", "attention", "achtung", "осторожно", "小心", "注意")) "🚨" else "🔥"
        in MONEY -> if (norm in setOf("gratis", "free", "gratuit", "kostenlos", "бесплатно", "免费", "無料", "मुफ्त")) "🎁" else "💰"
        else -> TextAnalysis.emojiFor(norm)
    }

    fun of(word: String): Emphasis? {
        val n = TextAnalysis.normalize(word)
        if (n.isEmpty()) return null
        val color = when (n) { in FIRE -> RED; in MONEY -> GREEN; else -> null }
        val emoji = emojiFor(n)
        return if (color == null && emoji == null) null else Emphasis(emoji, color)
    }
}

/** Animación "pop-in": entra pequeña y transparente, rebota ligeramente y se asienta. */
object PopAnimation {
    private const val UP_MS = 120f
    private const val SETTLE_MS = 100f
    private const val FROM = 0.60f
    private const val OVERSHOOT = 1.18f
    const val DURATION_MS = (UP_MS + SETTLE_MS).toLong()

    fun scale(msSinceStart: Long): Float {
        val t = msSinceStart.toFloat()
        return when {
            t <= 0f -> FROM
            t < UP_MS -> FROM + (OVERSHOOT - FROM) * easeOut(t / UP_MS)
            t < UP_MS + SETTLE_MS -> OVERSHOOT + (1f - OVERSHOOT) * easeInOut((t - UP_MS) / SETTLE_MS)
            else -> 1f
        }
    }

    fun alpha(msSinceStart: Long): Float = (msSinceStart / 70f).coerceIn(0f, 1f)

    private fun easeOut(x: Float) = 1f - (1f - x).pow(3)
    private fun easeInOut(x: Float) = x * x * (3f - 2f * x)
}

data class PunchEvent(val startMs: Long, val durationMs: Long = DEFAULT_DURATION_MS) {
    companion object {
        const val DEFAULT_DURATION_MS = 1_600L
    }
}

/**
 * Auto-zoom (punch-in): un acercamiento corto del 12 % (1-2 s) en picos de audio y palabras clave.
 */
object PunchIn {
    const val MAX_ZOOM = 1.12f
    private const val RAMP_UP_MS = 180f
    private const val RAMP_DOWN_MS = 420f
    private const val MIN_SEPARATION_MS = 4_000L

    /** Eventos dentro de [startMs, endMs): flancos de subida de picos de audio + palabras clave (énfasis o gancho). */
    fun plan(startMs: Long, endMs: Long, audio: AudioProfile?, words: List<WordTiming>): List<PunchEvent> {
        val triggers = ArrayList<Long>()
        audio?.peakOnsets(startMs, endMs, 1_000)?.let { triggers += it }
        for (w in words) {
            if (w.startMs < startMs || w.startMs >= endMs) continue
            if (WordEmphasis.of(w.text) != null || TextAnalysis.hookHits(listOf(w.text), Language.ES) > 0 ||
                TextAnalysis.hookHits(listOf(w.text), Language.EN) > 0
            ) triggers += w.startMs
        }
        triggers.sort()
        val out = ArrayList<PunchEvent>()
        for (t in triggers) {
            if (out.isEmpty() || t - out.last().startMs >= MIN_SEPARATION_MS) out += PunchEvent(t)
        }
        return out
    }

    /** Factor de zoom (1..MAX_ZOOM) en el instante `tMs`: máximo de las curvas de todos los eventos activos. */
    fun zoomAt(events: List<PunchEvent>, tMs: Long): Float {
        var best = 1f
        for (e in events) {
            val dt = (tMs - e.startMs).toFloat()
            if (dt < 0f || dt > e.durationMs) continue
            val k = when {
                dt < RAMP_UP_MS -> smooth(dt / RAMP_UP_MS)
                dt > e.durationMs - RAMP_DOWN_MS -> smooth((e.durationMs - dt) / RAMP_DOWN_MS)
                else -> 1f
            }
            best = max(best, 1f + (MAX_ZOOM - 1f) * k)
        }
        return best
    }

    private fun smooth(x: Float): Float { val c = x.coerceIn(0f, 1f); return c * c * (3f - 2f * c) }
}

/** Duración de cada efecto (coincide con los WAV de res/raw). */
enum class SfxType(val durationMs: Long) { POP(250), WHOOSH(450) }

data class SfxEvent(val timeMs: Long, val type: SfxType)

/** Efectos de sonido: "whoosh" al empezar un punch-in y "pop" cuando salta una palabra clave. */
object SfxPlanner {
    private const val MIN_GAP_MS = 900L
    private const val WHOOSH_LEAD_MS = 80L

    /** Tiempos ABSOLUTOS (del video original) dentro de [startMs, endMs). */
    fun plan(startMs: Long, endMs: Long, punches: List<PunchEvent>, words: List<WordTiming>): List<SfxEvent> {
        val raw = ArrayList<SfxEvent>()
        punches.forEach { raw += SfxEvent(max(startMs, it.startMs - WHOOSH_LEAD_MS), SfxType.WHOOSH) }
        for (w in words) {
            if (w.startMs < startMs || w.startMs >= endMs) continue
            if (WordEmphasis.of(w.text) != null) raw += SfxEvent(w.startMs, SfxType.POP)
        }
        raw.sortWith(compareBy({ it.timeMs }, { it.type.ordinal })) // WHOOSH (ordinal 1) tras POP en empate; da igual
        val out = ArrayList<SfxEvent>()
        for (e in raw) if (out.isEmpty() || e.timeMs - out.last().timeMs >= MIN_GAP_MS) out += e
        return out
    }
}

object ProgressBarMath {
    /** Progreso 0..1 del clip (tiempos relativos al inicio del clip). */
    fun progress(relativeMs: Long, durationMs: Long): Float =
        if (durationMs <= 0) 0f else (relativeMs.toFloat() / durationMs).coerceIn(0f, 1f)
}
