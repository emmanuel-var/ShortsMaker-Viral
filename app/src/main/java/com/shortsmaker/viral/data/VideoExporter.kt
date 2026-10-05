package com.shortsmaker.viral.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Size
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.MatrixTransformation
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.OverlaySettings
import androidx.media3.effect.Presentation
import androidx.media3.effect.TextureOverlay
import androidx.media3.effect.VideoCompositorSettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.shortsmaker.viral.R
import com.shortsmaker.viral.domain.AudioProfile
import com.shortsmaker.viral.domain.ClipEdit
import com.shortsmaker.viral.domain.ComposeLayout
import com.shortsmaker.viral.domain.ExportResolution
import com.shortsmaker.viral.domain.FaceTrack
import com.shortsmaker.viral.domain.FramingMath
import com.shortsmaker.viral.domain.PopAnimation
import com.shortsmaker.viral.domain.PunchEvent
import com.shortsmaker.viral.domain.PunchIn
import com.shortsmaker.viral.domain.SfxEvent
import com.shortsmaker.viral.domain.SfxPlanner
import com.shortsmaker.viral.domain.SfxType
import com.shortsmaker.viral.domain.SubtitleCue
import com.shortsmaker.viral.domain.SubtitleMode
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
import kotlin.math.max

/** Todo lo necesario para renderizar un clip. */
class ExportJob(
    val source: File,
    val srcWidth: Int,
    val srcHeight: Int,
    val edit: ClipEdit,
    val cues: List<SubtitleCue>,
    val faceTrack: FaceTrack?,
    /** Audio Radar del video (para el auto-zoom en picos de audio); null si no hay. */
    val audio: AudioProfile?,
    /** Segundo video local para `SPLIT_BROLL`. */
    val broll: File?,
    val resolution: ExportResolution,
    val spaced: Boolean,
    val output: File,
)

/**
 * Renderiza el clip 9:16 con la aceleración por hardware del teléfono (Media3 Transformer + MediaCodec).
 * No se usa ningún servidor externo.
 *
 * Rutas de render:
 *  - **Un solo `EditedMediaItem`** (diseño completo, sin efectos de sonido): el pipeline original de la Fase 1.
 *  - **`Composition` con varias secuencias** cuando hace falta:
 *      · modo dividido (arriba rostro, abajo gameplay/B-roll): dos secuencias de video + `VideoCompositorSettings`;
 *      · efectos de sonido (pop/whoosh): una secuencia de audio extra que Media3 mezcla con el audio original.
 */
class VideoExporter(private val context: Context) {

    suspend fun export(job: ExportJob, onProgress: (Float) -> Unit) = withContext(Dispatchers.Main) {
        job.output.parentFile?.mkdirs()
        job.output.delete()

        val edit = job.edit
        val res = job.resolution
        val w = res.width
        val h = res.height
        val clipDurationMs = edit.endMs - edit.startMs
        val style = edit.effectiveStyle()
        val words = job.cues.flatMap { it.words }

        // --- Eventos de punch-in (zoom) y de sonido: tiempos absolutos del video original.
        val punches = if (edit.autoZoom) PunchIn.plan(edit.startMs, edit.endMs, job.audio, words) else emptyList()
        val sfx = if (edit.sfx) SfxPlanner.plan(edit.startMs, edit.endMs, punches, words) else emptyList()

        // --- Subtítulos con tiempos relativos al inicio del clip.
        val relativeCues = job.cues
            .filter { it.words.isNotEmpty() && it.endMs > edit.startMs && it.startMs < edit.endMs }
            .map { cue ->
                cue.copy(
                    startMs = cue.startMs - edit.startMs,
                    endMs = cue.endMs - edit.startMs,
                    words = cue.words.map { it.copy(startMs = it.startMs - edit.startMs, endMs = it.endMs - edit.startMs) },
                )
            }

        // --- Overlays quemados sobre el cuadro final (barra de progreso y subtítulos).
        val overlays = mutableListOf<TextureOverlay>()
        if (edit.progressBar) overlays += ProgressBarOverlay(clipDurationMs * 1000, w, edit.progressColor)
        if (relativeCues.isNotEmpty()) overlays += SubtitleOverlay(relativeCues, style, w, job.spaced)
        val overlayEffects: List<Effect> = if (overlays.isEmpty()) emptyList() else listOf(OverlayEffect(overlays))

        fun framing(aspect: Float) = MatrixTransformation { presentationTimeUs ->
            val tAbs = edit.startMs + presentationTimeUs / 1000
            val t = FramingMath.transform(
                job.srcWidth, job.srcHeight, edit.framing, job.faceTrack?.xAt(tAbs), job.faceTrack?.yAt(tAbs),
                aspect = aspect, extraZoom = PunchIn.zoomAt(punches, tAbs),
            )
            Matrix().apply {
                setScale(t.scale, t.scale)
                postTranslate(t.tx, t.ty)
            }
        }
        fun clipped(file: File) = MediaItem.Builder()
            .setUri(Uri.fromFile(file))
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder().setStartPositionMs(edit.startMs).setEndPositionMs(edit.endMs).build(),
            )
            .build()

        val split = edit.layout == ComposeLayout.SPLIT_GAMEPLAY || (edit.layout == ComposeLayout.SPLIT_BROLL && job.broll != null)
        val sfxSequence = if (sfx.isEmpty()) null else buildSfxSequence(sfx, edit.startMs, clipDurationMs)

        val legacyItem: EditedMediaItem? // pipeline original de la Fase 1 (sin composición)
        val composition: Composition?
        if (!split) {
            val effects = listOf<Effect>(
                framing(FramingMath.TARGET_ASPECT),
                Presentation.createForWidthAndHeight(w, h, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP),
            ) + overlayEffects
            val main = EditedMediaItem.Builder(clipped(job.source)).setEffects(Effects(emptyList(), effects)).build()
            if (sfxSequence == null) {
                legacyItem = main
                composition = null
            } else {
                legacyItem = null
                composition = Composition.Builder(listOf(EditedMediaItemSequence.Builder(main).build(), sfxSequence)).build()
            }
        } else {
            // Arriba: rostro (seguido por el detector de rostros) recortado a la mitad superior 9:8. Abajo: gameplay original o B-roll.
            val halfH = h / 2
            val top = EditedMediaItem.Builder(clipped(job.source))
                .setEffects(
                    Effects(
                        emptyList(),
                        listOf(
                            framing(FramingMath.SPLIT_ASPECT),
                            Presentation.createForWidthAndHeight(w, halfH, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP),
                        ),
                    ),
                )
                .build()
            val bottomSource = job.broll?.let { MediaItem.fromUri(Uri.fromFile(it)) } ?: clipped(job.source)
            val bottom = EditedMediaItem.Builder(bottomSource)
                .setRemoveAudio(true) // el audio sale sólo del video principal
                .setEffects(
                    Effects(
                        emptyList(),
                        listOf(Presentation.createForWidthAndHeight(w, halfH, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP)),
                    ),
                )
                .build()
            val sequences = buildList {
                add(EditedMediaItemSequence.Builder(top).build())
                // El B-roll se repite en bucle hasta que termina la secuencia principal.
                add(EditedMediaItemSequence.Builder(bottom).setIsLooping(job.broll != null).build())
                sfxSequence?.let { add(it) } // sin video: no consume un inputId del compositor
            }
            legacyItem = null
            composition = Composition.Builder(sequences)
                .setVideoCompositorSettings(SplitCompositorSettings(w, h))
                // Los subtítulos y la barra se dibujan UNA vez sobre el resultado compuesto.
                .setEffects(Effects(emptyList(), overlayEffects))
                .build()
        }

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
                        .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(res.bitrate).build())
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
                    if (legacyItem != null) transformer.start(legacyItem, job.output.absolutePath)
                    else transformer.start(composition!!, job.output.absolutePath)
                }
                onProgress(1f)
            } catch (e: kotlinx.coroutines.CancellationException) {
                job.output.delete()
                throw e
            } catch (e: AppException) {
                job.output.delete()
                throw e
            } catch (e: Exception) {
                job.output.delete()
                throw AppException(R.string.error_export, cause = e)
            } finally {
                poller.cancel()
            }
        }
    }

    // ------------------------------------------------------------------------------------------ audio: SFX

    /**
     * Secuencia de audio con los efectos (pop / whoosh) separados por silencios exactos (`addGap`), de la misma
     * duración que el clip. Media3 la mezcla con el audio del video principal.
     */
    private fun buildSfxSequence(events: List<SfxEvent>, startMs: Long, clipDurationMs: Long): EditedMediaItemSequence? {
        val files = sfxFiles()
        val builder = EditedMediaItemSequence.Builder()
        var cursorUs = 0L
        var added = 0
        for (e in events) {
            val relUs = (e.timeMs - startMs) * 1000
            if (relUs < cursorUs) continue // sin solapar efectos
            val gap = relUs - cursorUs
            if (gap > 0) builder.addGap(gap)
            builder.addItem(EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(files.getValue(e.type)))).build())
            cursorUs = relUs + e.type.durationMs * 1000
            added++
        }
        if (added == 0) return null
        val tail = clipDurationMs * 1000 - cursorUs
        if (tail > 0) builder.addGap(tail)
        return builder.build()
    }

    private fun sfxFiles(): Map<SfxType, File> {
        val dir = File(context.cacheDir, "sfx").apply { mkdirs() }
        fun copy(resId: Int, name: String): File {
            val f = File(dir, name)
            if (!f.exists() || f.length() == 0L) context.resources.openRawResource(resId).use { input -> f.outputStream().use { input.copyTo(it) } }
            return f
        }
        return mapOf(SfxType.POP to copy(R.raw.sfx_pop, "pop.wav"), SfxType.WHOOSH to copy(R.raw.sfx_whoosh, "whoosh.wav"))
    }

    // ------------------------------------------------------------------------------------------ composición dividida

    /**
     * Lienzo W×H. Entrada 0 (rostro) en la mitad superior y entrada 1 (gameplay/B-roll) en la inferior. Cada textura ya
     * mide W×H/2 (la fija el `Presentation` de cada secuencia) y Media3 la dibuja con relación 1:1 respecto al lienzo,
     * así que sólo hay que centrarla en y = +0.5 / −0.5 (coordenadas normalizadas).
     */
    private class SplitCompositorSettings(private val width: Int, private val height: Int) : VideoCompositorSettings {
        private val top = OverlaySettings.Builder().setBackgroundFrameAnchor(0f, 0.5f).setOverlayFrameAnchor(0f, 0f).build()
        private val bottom = OverlaySettings.Builder().setBackgroundFrameAnchor(0f, -0.5f).setOverlayFrameAnchor(0f, 0f).build()

        override fun getOutputSize(inputSizes: List<Size>): Size = Size(width, height)

        override fun getOverlaySettings(inputId: Int, presentationTimeUs: Long): OverlaySettings = if (inputId == 0) top else bottom
    }

    // ------------------------------------------------------------------------------------------ overlays

    /** Overlay de Media3 que dibuja el subtítulo activo (tiempos relativos al inicio del clip) con animación pop-in. */
    private class SubtitleOverlay(
        private val cues: List<SubtitleCue>,
        private val style: SubtitleStyle,
        private val frameWidth: Int,
        private val spaced: Boolean,
    ) : BitmapOverlay() {
        private val renderer = SubtitleRenderer()
        private val empty: Bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        private var cachedCue = -1
        private var cachedWord = -1
        private var cachedBitmap: Bitmap = empty

        // NDC: x=0 centrado; y positivo hacia arriba. Fracción 0.5 -> 0; 0.2 -> +0.6; 0.7 -> -0.4.
        private val yNdc = 1f - 2f * style.position.centerYFraction
        private val still: OverlaySettings = OverlaySettings.Builder()
            .setBackgroundFrameAnchor(0f, yNdc)
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
                // El color de la palabra cambia en el fotograma en que empieza a pronunciarse (tiempos por palabra de Vosk).
                cachedBitmap = renderer.render(cue, word, style, frameWidth, spaced) ?: empty
            }
            return cachedBitmap
        }

        override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings {
            if (!style.popIn) return still
            val tMs = presentationTimeUs / 1000
            val cue = cues.firstOrNull { tMs >= it.startMs && tMs < it.endMs } ?: return still
            val word = renderer.activeWord(cue, tMs)
            val since = tMs - if (style.mode == SubtitleMode.WORD_BY_WORD) cue.words[word].startMs else cue.startMs
            if (since >= PopAnimation.DURATION_MS) return still
            val s = PopAnimation.scale(since)
            return OverlaySettings.Builder()
                .setBackgroundFrameAnchor(0f, yNdc)
                .setOverlayFrameAnchor(0f, 0f)
                .setScale(s, s)
                .setAlphaScale(PopAnimation.alpha(since))
                .build()
        }
    }

    /**
     * Barra de progreso fina en el borde superior: un único bitmap del ancho del cuadro cuya escala horizontal crece
     * con el tiempo. Se escala respecto a su esquina izquierda, así que la barra "avanza" hacia la derecha.
     */
    private class ProgressBarOverlay(private val durationUs: Long, frameWidth: Int, color: Int) : BitmapOverlay() {
        private val bar: Bitmap = Bitmap.createBitmap(frameWidth, max(4, frameWidth / 120), Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

        override fun getBitmap(presentationTimeUs: Long): Bitmap = bar

        override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings {
            val progress = if (durationUs <= 0) 1f else (presentationTimeUs.toFloat() / durationUs).coerceIn(0.003f, 1f)
            return OverlaySettings.Builder()
                .setBackgroundFrameAnchor(-1f, 1f) // esquina superior izquierda del cuadro
                .setOverlayFrameAnchor(-1f, 1f)    // esquina superior izquierda de la barra
                .setScale(progress, 1f)
                .build()
        }
    }
}
