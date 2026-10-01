package com.shortsmaker.viral.data

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream

data class VideoInfo(
    val durationMs: Long,
    /** Dimensiones tal y como se ven (con la rotación aplicada). */
    val width: Int,
    val height: Int,
    val hasAudio: Boolean,
)

object MediaUtils {

    fun probe(context: Context, uri: Uri): VideoInfo? = withRetriever({ it.setDataSource(context, uri) })

    fun probe(file: File): VideoInfo? = withRetriever({ it.setDataSource(file.absolutePath) })

    private fun withRetriever(source: (MediaMetadataRetriever) -> Unit, ): VideoInfo? {
        val r = MediaMetadataRetriever()
        return try {
            source(r)
            if (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) != "yes") return null
            val duration = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: return null
            var w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: return null
            var h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: return null
            val rotation = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rotation == 90 || rotation == 270) { val t = w; w = h; h = t }
            VideoInfo(duration, w, h, r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes")
        } catch (_: Exception) {
            null
        } finally {
            try { r.release() } catch (_: Exception) { }
        }
    }

    /** Fotograma del video escalado a `maxWidth` píxeles de ancho como máximo. */
    fun frameAt(file: File, timeMs: Long, maxWidth: Int, exact: Boolean = false): Bitmap? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(file.absolutePath)
            val option = if (exact) MediaMetadataRetriever.OPTION_CLOSEST else MediaMetadataRetriever.OPTION_CLOSEST_SYNC
            r.getFrameAtTime(timeMs * 1000, option)?.let { scaleDown(it, maxWidth) }
        } catch (_: Exception) {
            null
        } finally {
            try { r.release() } catch (_: Exception) { }
        }
    }

    /** Varios fotogramas con un solo retriever (miniaturas de la línea de tiempo). */
    fun framesAt(file: File, timesMs: List<Long>, maxWidth: Int): List<Bitmap?> {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(file.absolutePath)
            timesMs.map { t ->
                try {
                    r.getFrameAtTime(t * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { scaleDown(it, maxWidth) }
                } catch (_: Exception) { null }
            }
        } catch (_: Exception) {
            timesMs.map { null }
        } finally {
            try { r.release() } catch (_: Exception) { }
        }
    }

    fun scaleDown(src: Bitmap, maxWidth: Int): Bitmap {
        if (src.width <= maxWidth) return src
        val h = (src.height * (maxWidth.toFloat() / src.width)).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(src, maxWidth, h, true)
        if (scaled !== src) src.recycle()
        return scaled
    }

    fun saveJpeg(bitmap: Bitmap, target: File) {
        FileOutputStream(target).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
    }

    fun displayName(context: Context, uri: Uri): String? = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (_: Exception) { null }

    fun size(context: Context, uri: Uri): Long = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else -1L
        } ?: -1L
    } catch (_: Exception) { -1L }
}

fun formatTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
