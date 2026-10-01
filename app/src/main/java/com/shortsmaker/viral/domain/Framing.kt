package com.shortsmaker.viral.domain

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Transformación del encuadre vertical, expresada en coordenadas normalizadas del dispositivo (-1..1) del
 * fotograma de origen: `p' = scale * p + (tx, ty)`. La usan tanto la vista previa del editor como el
 * exportador (MatrixTransformation), para que lo que ves sea lo que se exporta.
 */
data class CropTransform(val scale: Float, val tx: Float, val ty: Float)

object FramingMath {
    const val TARGET_ASPECT = 9f / 16f

    /** Fracción del ancho / alto del fotograma que ocupa la ventana 9:16 con zoom 1. */
    fun baseWindow(srcW: Int, srcH: Int): Pair<Float, Float> {
        val cw = (srcH * TARGET_ASPECT) / srcW
        return min(1f, cw) to min(1f, 1f / cw)
    }

    /**
     * @param faceX centro horizontal del rostro (0..1) en el instante actual, o null si no hay seguimiento.
     * @param faceY centro vertical del rostro (0..1) o null.
     */
    fun transform(srcW: Int, srcH: Int, framing: Framing, faceX: Float?, faceY: Float?): CropTransform {
        val s = framing.zoom.coerceIn(1f, 4f)
        val (wx0, wy0) = baseWindow(srcW, srcH)
        val wx = wx0 / s
        val wy = wy0 / s
        val maxPanX = max(0f, 1f - wx)
        val maxPanY = max(0f, 1f - wy)

        val autoX = if (framing.autoTrack && faceX != null) (faceX * 2f - 1f) else 0f
        // Solo se sigue la vertical si la ventana no ocupa ya todo el alto (zoom o video más estrecho que 9:16).
        val autoY = if (framing.autoTrack && faceY != null && maxPanY > 0f) -(faceY * 2f - 1f) else 0f

        val cx = (autoX + framing.offsetX * maxPanX).coerceIn(-maxPanX, maxPanX)
        val cy = (autoY + framing.offsetY * maxPanY).coerceIn(-maxPanY, maxPanY)
        return CropTransform(scale = s, tx = -s * cx, ty = -s * cy)
    }
}

/** Trayectoria suavizada del rostro: interpola entre muestras y evita "temblor" de cámara. */
class FaceTrack(raw: List<FacePoint>) {
    private val points: List<FacePoint> = smooth(raw.sortedBy { it.timeMs })

    val isEmpty: Boolean get() = points.isEmpty()

    fun xAt(timeMs: Long): Float? = interpolate(timeMs) { it.x }
    fun yAt(timeMs: Long): Float? = interpolate(timeMs) { it.y }

    private fun interpolate(timeMs: Long, sel: (FacePoint) -> Float): Float? {
        if (points.isEmpty()) return null
        if (timeMs <= points.first().timeMs) return sel(points.first())
        if (timeMs >= points.last().timeMs) return sel(points.last())
        var lo = 0
        var hi = points.lastIndex
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (points[mid].timeMs <= timeMs) lo = mid else hi = mid
        }
        val a = points[lo]
        val b = points[hi]
        val t = (timeMs - a.timeMs).toFloat() / max(1L, b.timeMs - a.timeMs)
        return sel(a) + (sel(b) - sel(a)) * t
    }

    companion object {
        private const val DEAD_ZONE = 0.035f
        private const val MAX_STEP = 0.15f
        private const val ALPHA = 0.45f

        /** Zona muerta + límite de velocidad + media exponencial: movimientos de cámara suaves. */
        fun smooth(points: List<FacePoint>): List<FacePoint> {
            if (points.isEmpty()) return points
            var x = points.first().x
            var y = points.first().y
            return points.mapIndexed { i, p ->
                if (i > 0) {
                    x = follow(x, p.x)
                    y = follow(y, p.y)
                }
                FacePoint(p.timeMs, x, y)
            }
        }

        private fun follow(current: Float, target: Float): Float {
            val diff = target - current
            if (abs(diff) < DEAD_ZONE) return current
            val step = (diff * ALPHA).coerceIn(-MAX_STEP, MAX_STEP)
            return current + step
        }
    }
}
