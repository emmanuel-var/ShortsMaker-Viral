package com.shortsmaker.viral.data

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import com.shortsmaker.viral.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer

/**
 * Recorte previo SIN recodificar (remux): copia las muestras comprimidas entre `startMs` y `endMs` a un MP4 nuevo.
 * Es casi instantáneo y no pierde calidad; el inicio se ajusta al fotograma clave anterior. Sirve de "trimmer"
 * para videos largos: sólo se guarda y procesa el fragmento elegido (≤ 15 min).
 */
object VideoTrimmer {
    private const val BUFFER_SIZE = 8 * 1024 * 1024

    suspend fun trim(
        context: Context,
        source: Uri,
        target: File,
        startMs: Long,
        endMs: Long,
        onProgress: (Float) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var started = false
        try {
            extractor.setDataSource(context, source, null)
            val rotation = readRotation(context, source)

            muxer = MediaMuxer(target.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            if (rotation != 0) muxer.setOrientationHint(rotation)

            // Sólo pistas de video y audio.
            val map = HashMap<Int, Int>()
            var videoTrack = -1
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                    extractor.selectTrack(i)
                    map[i] = muxer.addTrack(f)
                    if (mime.startsWith("video/") && videoTrack < 0) videoTrack = i
                }
            }
            if (videoTrack < 0) throw AppException(R.string.error_unreadable_video)

            val startUs = startMs * 1000
            val endUs = endMs * 1000
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            muxer.start()
            started = true

            // La referencia temporal es la 1.ª muestra de video tras el seek (fotograma clave): así el audio queda alineado.
            var baseUs = -1L
            val buffer = ByteBuffer.allocate(BUFFER_SIZE)
            val info = MediaCodec.BufferInfo()
            val span = (endUs - startUs).coerceAtLeast(1)
            while (true) {
                ensureActive()
                val track = extractor.sampleTrackIndex
                if (track < 0) break
                val t = extractor.sampleTime
                if (t >= endUs) break
                if (track == videoTrack && baseUs < 0) baseUs = t
                if (baseUs < 0 || t < baseUs) { extractor.advance(); continue } // audio previo al 1.er fotograma clave
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                info.set(0, size, t - baseUs, if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                muxer.writeSampleData(map.getValue(track), buffer, info)
                extractor.advance()
                onProgress(((t - startUs).toFloat() / span).coerceIn(0f, 1f))
            }
            if (baseUs < 0) throw AppException(R.string.error_unreadable_video)
            onProgress(1f)
        } catch (e: AppException) {
            target.delete(); throw e
        } catch (e: kotlinx.coroutines.CancellationException) {
            target.delete(); throw e
        } catch (e: Exception) {
            // Formatos que el muxer MP4 no acepta (p. ej. VP9/Opus en WebM) o archivos ilegibles.
            target.delete()
            throw AppException(R.string.error_trim, cause = e)
        } finally {
            try { if (started) muxer?.stop() } catch (_: Exception) { }
            try { muxer?.release() } catch (_: Exception) { }
            extractor.release()
        }
    }

    private fun readRotation(context: Context, uri: Uri): Int {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(context, uri)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        } catch (_: IOException) { 0 } catch (_: RuntimeException) { 0 } finally {
            try { r.release() } catch (_: Exception) { }
        }
    }
}
