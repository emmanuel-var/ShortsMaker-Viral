package com.shortsmaker.viral.domain

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Movimiento brusco del rostro a partir de las muestras del detector de rostros: desplazamientos del centro (gesticular,
 * moverse) y cambios de tamaño (acercarse / alejarse de la cámara). Se mide como velocidad (unidades/segundo)
 * y se normaliza contra el propio video (percentil 90).
 */
class MotionTrack(points: List<FacePoint>) {
    private val pts = points.sortedBy { it.timeMs }
    private class Step(val midMs: Long, val rate: Float)
    private val pairs: List<Step>
    private val reference: Float

    init {
        val list = ArrayList<Step>()
        for (i in 1 until pts.size) {
            val a = pts[i - 1]; val b = pts[i]
            val dt = (b.timeMs - a.timeMs) / 1000f
            if (dt <= 0f || dt > MAX_GAP_S) continue // huecos = el rostro no se detectó: no se interpreta como movimiento
            val delta = abs(b.x - a.x) + abs(b.y - a.y) + SIZE_WEIGHT * abs(b.size - a.size)
            list += Step((a.timeMs + b.timeMs) / 2, delta / dt)
        }
        pairs = list
        val sorted = list.map { it.rate }.sorted()
        reference = max(MIN_REFERENCE, if (sorted.isEmpty()) 0f else sorted[((sorted.size - 1) * 0.9f).toInt()])
    }

    val isEmpty: Boolean get() = pairs.size < MIN_PAIRS

    /** Puntaje de movimiento 0..1 del tramo, o null si no hay suficientes muestras con rostro. */
    fun score(startMs: Long, endMs: Long): Float? {
        var n = 0; var sum = 0f; var strong = 0
        for (p in pairs) {
            if (p.midMs < startMs || p.midMs > endMs) continue
            n++; sum += min(2f, p.rate / reference); if (p.rate >= reference) strong++
        }
        if (n < MIN_PAIRS) return null
        val mean = (sum / n).coerceIn(0f, 1f)
        return (0.6f * mean + 0.4f * (strong.toFloat() / n)).coerceIn(0f, 1f)
    }

    private companion object {
        const val MAX_GAP_S = 3f
        const val SIZE_WEIGHT = 2f
        const val MIN_REFERENCE = 0.02f
        const val MIN_PAIRS = 3
    }
}
