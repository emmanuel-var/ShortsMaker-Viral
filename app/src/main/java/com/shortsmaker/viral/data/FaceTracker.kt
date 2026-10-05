package com.shortsmaker.viral.data

import android.graphics.Bitmap
import android.graphics.PointF
import android.media.FaceDetector
import android.media.MediaMetadataRetriever
import com.shortsmaker.viral.domain.FacePoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Seguimiento del rostro con el detector que trae el propio Android (android.media.FaceDetector):
 * sin librerías nativas propias, sin modelos y sin red. Detecta rostros frontales y erguidos.
 * Devuelve null si falla por completo (se usará el recorte centrado).
 */
class FaceTracker {

    suspend fun track(
        video: File,
        startMs: Long,
        endMs: Long,
        stepMs: Long = 700L,
        onProgress: (Float) -> Unit,
    ): List<FacePoint>? = withContext(Dispatchers.Default) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(video.absolutePath)
            val points = ArrayList<FacePoint>()
            val span = (endMs - startMs).coerceAtLeast(1)
            var t = startMs
            while (t <= endMs) {
                ensureActive()
                val frame = retriever.getFrameAtTime(t * 1000, MediaMetadataRetriever.OPTION_CLOSEST)
                if (frame != null) {
                    val bmp = prepare(frame)
                    try {
                        detect(bmp, t)?.let { points += it }
                    } finally {
                        bmp.recycle()
                    }
                }
                onProgress(((t - startMs).toFloat() / span).coerceIn(0f, 1f))
                t += stepMs
            }
            onProgress(1f)
            points
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } finally {
            try { retriever.release() } catch (_: Exception) { }
        }
    }

    private fun detect(bmp: Bitmap, timeMs: Long): FacePoint? {
        val faces = arrayOfNulls<FaceDetector.Face>(MAX_FACES)
        val found = FaceDetector(bmp.width, bmp.height, MAX_FACES).findFaces(bmp, faces)
        val best = faces.take(found).filterNotNull().maxByOrNull { it.eyesDistance() } ?: return null
        val eyes = best.eyesDistance()
        if (eyes <= 0f) return null
        val mid = PointF().also { best.getMidPoint(it) }
        // El punto medio de los ojos queda por encima del centro del rostro: se baja un poco.
        val cy = mid.y + eyes * 0.35f
        return FacePoint(
            timeMs = timeMs,
            x = (mid.x / bmp.width).coerceIn(0f, 1f),
            y = (cy / bmp.height).coerceIn(0f, 1f),
            size = (eyes * 2.5f / bmp.width).coerceIn(0f, 1f),
        )
    }

    /** FaceDetector exige RGB_565 con ancho par; además reducimos el tamaño para ir rápido. */
    private fun prepare(frame: Bitmap): Bitmap {
        val scaled = MediaUtils.scaleDown(frame, MAX_WIDTH)
        val w = scaled.width and 1.inv()
        val h = scaled.height
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
        android.graphics.Canvas(out).drawBitmap(scaled, 0f, 0f, null)
        scaled.recycle() // scaleDown ya recicló el original si hubo reducción
        return out
    }

    private companion object {
        const val MAX_WIDTH = 480
        const val MAX_FACES = 3
    }
}
