package com.shortsmaker.viral.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.MatrixTransformation
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.OverlaySettings
import androidx.media3.effect.Presentation
import androidx.media3.effect.TextureOverlay
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.shortsmaker.viral.R
import com.shortsmaker.viral.domain.ClipEdit
import com.shortsmaker.viral.domain.ExportResolution
import com.shortsmaker.viral.domain.FaceTrack
import com.shortsmaker.viral.domain.FramingMath
import com.shortsmaker.viral.domain.SubtitleCue
import com.shortsmaker.viral.domain.SubtitleStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Renderiza el clip 9:16 con la aceleración por hardware del teléfono (Media3 Transformer + MediaCodec).
 * No se usa ningún servidor externo.
 */
class VideoExporter(private val context: Context) {

    /** Debe llamarse desde cualquier hilo; internamente salta al hilo principal que exige Transformer. */
    suspend fun export(
        source: File,
        srcWidth: Int,
        srcHeight: Int,
        edit: ClipEdit,
        cues: List<SubtitleCue>,
        faceTrack: FaceTrack?,
        resolution: ExportResolution,
        output: File,
        onProgress: (Float) -> Unit,
    ) = withContext(Dispatchers.Main) {
        output.parentFile?.mkdirs()
        output.delete()

        val clipStart = edit.startMs
        val framingEffect = MatrixTransformation { presentationTimeUs ->
            val tAbs = clipStart + presentationTimeUs / 1000
            val t = FramingMath.transform(srcWidth, srcHeight, edit.framing, faceTrack?.xAt(tAbs), faceTrack?.yAt(tAbs))
            Matrix().apply {
                setScale(t.scale, t.scale)
                postTranslate(t.tx, t.ty)
            }
        }
        val presentation = Presentation.createForWidthAndHeight(
            resolution.width, resolution.height, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP,
        )
        val videoEffects = mutableListOf<Effect>(framingEffect, presentation)
        val relativeCues = cues
            .filter { it.words.isNotEmpty() && it.endMs > edit.startMs && it.startMs < edit.endMs }
            .map { cue ->
                cue.copy(
                    startMs = cue.startMs - edit.startMs,
                    endMs = cue.endMs - edit.startMs,
                    words = cue.words.map { it.copy(startMs = it.startMs - edit.startMs, endMs = it.endMs - edit.startMs) },
                )
            }
        if (relativeCues.isNotEmpty()) {
            videoEffects += OverlayEffect(listOf<TextureOverlay>(SubtitleOverlay(relativeCues, edit.style, resolution.width)))
        }

        val mediaItem = MediaItem.Builder()
            .setUri(Uri.fromFile(source))
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(edit.startMs)
                    .setEndPositionMs(edit.endMs)
                    .build(),
            )
            .build()
        val edited = EditedMediaItem.Builder(mediaItem)
            .setEffects(Effects(emptyList(), videoEffects))
            .build()

        coroutineScope {
            var transformerRef: Transformer? = null
            val poller = launch {
                val holder = ProgressHolder()
                while (isActive) {
                    val t = transformerRef
                    if (t != null && t.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                        onProgress(holder.progress / 100f)
                    }
                    delay(250)
                }
            }
            try {
                suspendCancellableCoroutine<Unit> { cont ->
                    val encoderFactory = DefaultEncoderFactory.Builder(context)
                        .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(resolution.bitrate).build())
                        .build()
                    val transformer = Transformer.Builder(context)
                        .setVideoMimeType(MimeTypes.VIDEO_H264)
                        .setAudioMimeType(MimeTypes.AUDIO_AAC)
                        .setEncoderFactory(encoderFactory)
                        .addListener(object : Transformer.Listener {
                            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                                if (cont.isActive) cont.resume(Unit)
                            }

                            override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                                if (cont.isActive) cont.resumeWithException(AppException(R.string.error_export, cause = exportException))
                            }
                        })
                        .build()
                    transformerRef = transformer
                    cont.invokeOnCancellation { transformer.cancel() }
                    transformer.start(edited, output.absolutePath)
                }
                onProgress(1f)
            } catch (e: kotlinx.coroutines.CancellationException) {
                output.delete()
                throw e
            } catch (e: AppException) {
                output.delete()
                throw e
            } catch (e: Exception) {
                output.delete()
                throw AppException(R.string.error_export, cause = e)
            } finally {
                poller.cancel()
            }
        }
    }

    /** Overlay de Media3 que dibuja el subtítulo activo (tiempos relativos al inicio del clip). */
    private class SubtitleOverlay(
        private val cues: List<SubtitleCue>,
        private val style: SubtitleStyle,
        private val frameWidth: Int,
    ) : BitmapOverlay() {
        private val renderer = SubtitleRenderer()
        private val empty: Bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        private var cachedCue = -1
        private var cachedWord = -1
        private var cachedBitmap: Bitmap = empty

        // NDC: x=0 centrado; y positivo hacia arriba. 0.5 de fracción -> 0; 0.2 -> +0.6; 0.7 -> -0.4.
        private val settings: OverlaySettings = OverlaySettings.Builder()
            .setBackgroundFrameAnchor(0f, 1f - 2f * style.position.centerYFraction)
            .setOverlayFrameAnchor(0f, 0f)
            .build()

        override fun getBitmap(presentationTimeUs: Long): Bitmap {
            val tMs = presentationTimeUs / 1000
            val cueIdx = cues.indexOfFirst { tMs >= it.startMs && tMs < it.endMs }
            if (cueIdx < 0) {
                cachedCue = -1
                cachedWord = -1
                cachedBitmap = empty
                return empty
            }
            val cue = cues[cueIdx]
            val word = renderer.activeWord(cue, tMs)
            if (cueIdx != cachedCue || word != cachedWord) {
                cachedCue = cueIdx
                cachedWord = word
                cachedBitmap = renderer.render(cue, word, style, frameWidth) ?: empty
            }
            return cachedBitmap
        }

        override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings = settings
    }
}
