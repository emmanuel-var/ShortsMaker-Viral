package com.shortsmaker.viral.data

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facedetector.FaceDetector
import com.shortsmaker.viral.domain.FacePoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Seguimiento del rostro con MediaPipe (modelo BlazeFace incluido en la app, sin red).
 * Devuelve null si el detector no puede iniciarse en este dispositivo (se usará el recorte centrado).
 */
class FaceTracker(private val context: Context) {

    suspend fun track(
        video: File,
        startMs: Long,
        endMs: Long,
        stepMs: Long = 700L,
        onProgress: (Float) -> Unit,
    ): List<FacePoint>? = withContext(Dispatchers.Default) {
        val detector = try {
            val base = BaseOptions.builder().setModelAssetPath(MODEL_ASSET).build()
            val options = FaceDetector.FaceDetectorOptions.builder()
                .setBaseOptions(base)
                .setRunningMode(RunningMode.IMAGE)
                .setMinDetectionConfidence(0.5f)
                .build()
            FaceDetector.createFromOptions(context, options)
        } catch (_: Throwable) {
            return@withContext null
        }

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
                        val result = detector.detect(BitmapImageBuilder(bmp).build())
                        val best = result.detections().maxByOrNull { it.boundingBox().let { b -> b.width() * b.height() } }
                        if (best != null) {
                            val box = best.boundingBox()
                            points += FacePoint(
                                timeMs = t,
                                x = (box.centerX() / bmp.width).coerceIn(0f, 1f),
                                y = (box.centerY() / bmp.height).coerceIn(0f, 1f),
                                size = (box.width() / bmp.width).coerceIn(0f, 1f),
                            )
                        }
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
            detector.close()
        }
    }

    /** MediaPipe necesita ARGB_8888; además reducimos el tamaño para ir rápido. */
    private fun prepare(frame: Bitmap): Bitmap {
        val scaled = MediaUtils.scaleDown(frame, MAX_WIDTH)
        if (scaled.config == Bitmap.Config.ARGB_8888) return scaled
        val copy = scaled.copy(Bitmap.Config.ARGB_8888, false)
        scaled.recycle()
        return copy
    }

    private companion object {
        const val MODEL_ASSET = "blaze_face_short_range.tflite"
        const val MAX_WIDTH = 640
    }
}
