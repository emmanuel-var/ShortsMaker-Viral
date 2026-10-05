package com.shortsmaker.viral.domain

import kotlin.math.max
import kotlin.math.min

/** Reglas para videos largos (evitar OOM y gasto de batería). */
object LongVideo {
    /** Por encima de 15 min se avisa y se ofrece recortar o forzar el modo rápido. */
    const val THRESHOLD_MS = 15 * 60_000L

    fun isLong(durationMs: Long) = durationMs > THRESHOLD_MS

    /** Duración mínima del fragmento recortado al importar. */
    const val MIN_TRIM_MS = 5_000L

    /**
     * Ajusta el fragmento elegido en el recortador previo: entre 5 s y 15 min, dentro del video.
     * `movedStart` indica qué extremo movió el usuario (el otro se adapta).
     */
    fun clampTrim(startMs: Long, endMs: Long, movedStart: Boolean, durationMs: Long): TimeSpan {
        var s = startMs.coerceIn(0, durationMs)
        var e = endMs.coerceIn(0, durationMs)
        val minLen = minOf(MIN_TRIM_MS, durationMs)
        if (e - s < minLen) { if (movedStart) s = (e - minLen).coerceAtLeast(0) else e = (s + minLen).coerceAtMost(durationMs) }
        if (e - s > THRESHOLD_MS) { if (movedStart) s = e - THRESHOLD_MS else e = s + THRESHOLD_MS }
        return TimeSpan(s, e)
    }
}

/**
 * Modo rápido ("Audio Radar Fallback"): en vez de transcribir 4 horas, se extrae sólo la energía del audio, se
 * buscan los 5 momentos más fuertes y Vosk + detector de rostros se ejecutan únicamente en ventanas de 2 minutos a su alrededor.
 */
object FastModePlanner {
    const val PEAKS = 5
    const val WINDOW_MS = 120_000L

    /** Ventanas (ordenadas, sin solaparse) de 2 min centradas en los 5 picos más fuertes. */
    fun plan(profile: AudioProfile, durationMs: Long): List<TimeSpan> {
        if (durationMs <= 0) return emptyList()
        val peaks = profile.topPeaks(PEAKS, minSeparationMs = WINDOW_MS)
        val spans = peaks.map { p ->
            val start = (p.timeMs - WINDOW_MS / 2).coerceIn(0, max(0, durationMs - WINDOW_MS))
            TimeSpan(start, min(durationMs, start + WINDOW_MS))
        }.sortedBy { it.startMs }
        // Si el vídeo es corto o los picos están pegados, se fusionan los tramos que se tocan.
        val merged = ArrayList<TimeSpan>()
        for (s in spans) {
            val last = merged.lastOrNull()
            if (last != null && s.startMs <= last.endMs) merged[merged.lastIndex] = TimeSpan(last.startMs, max(last.endMs, s.endMs)) else merged += s
        }
        return merged
    }
}
