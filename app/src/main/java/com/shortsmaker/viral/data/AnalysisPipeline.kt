package com.shortsmaker.viral.data

import android.content.Context
import android.net.Uri
import com.shortsmaker.viral.ImportRequest
import com.shortsmaker.viral.ProcessingMode
import com.shortsmaker.viral.R
import com.shortsmaker.viral.domain.AnalyzerConfig
import com.shortsmaker.viral.domain.AudioEnergyTrack
import com.shortsmaker.viral.domain.AudioProfile
import com.shortsmaker.viral.domain.ChatActivity
import com.shortsmaker.viral.domain.ClipAnalyzer
import com.shortsmaker.viral.domain.ClipSuggestion
import com.shortsmaker.viral.domain.CueBuilder
import com.shortsmaker.viral.domain.FacePoint
import com.shortsmaker.viral.domain.FastModePlanner
import com.shortsmaker.viral.domain.MediaSignals
import com.shortsmaker.viral.domain.MotionTrack
import com.shortsmaker.viral.domain.MultimediaClipGenerator
import com.shortsmaker.viral.domain.Project
import com.shortsmaker.viral.domain.SourceMeta
import com.shortsmaker.viral.domain.TimeSpan
import com.shortsmaker.viral.domain.WordTiming
import com.shortsmaker.viral.domain.YouTubeHtmlParser
import com.shortsmaker.viral.domain.clamped
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

enum class PipelineStep(val weight: Float) {
    IMPORT(0.12f),
    LINK(0.05f),
    MODEL(0.15f),
    RADAR(0.10f),
    TRANSCRIBE(0.38f),
    MOTION(0.10f),
    ANALYZE(0.10f),
}

data class PipelineProgress(
    val steps: List<PipelineStep>,
    val current: PipelineStep,
    val stepFraction: Float,
    val overall: Float,
)

/**
 * Importar → (datos del enlace) → (modelo de voz) → Audio Radar → transcribir → movimiento del rostro → puntuar.
 *
 * - **Normal** (≤ 15 min, o recortado): Vosk sobre todo el video; texto + audio + movimiento (+ chat).
 * - **Rápido** (video largo forzado): sólo se extrae la energía del audio, se toman los 5 picos más fuertes y
 *   Vosk + detector de rostros se ejecutan únicamente en ventanas de 2 min alrededor de ellos.
 * Todo corre en el teléfono. Si algo falla o se cancela, se borra el proyecto a medias.
 */
class AnalysisPipeline(
    private val context: Context,
    private val projects: ProjectRepository,
    private val models: VoskModelManager,
    private val linkMetadata: LinkMetadataClient,
    private val transcriber: VoskTranscriber,
    private val faceTracker: FaceTracker,
) {

    suspend fun run(projectId: String, request: ImportRequest, onProgress: (PipelineProgress) -> Unit): Project {
        val link = request.link
        val fast = request.mode == ProcessingMode.FAST
        val needModel = !models.isInstalled(request.language)
        val steps = buildList {
            add(PipelineStep.IMPORT)
            if (link != null) add(PipelineStep.LINK)
            if (fast) { add(PipelineStep.RADAR); if (needModel) add(PipelineStep.MODEL) }
            else { if (needModel) add(PipelineStep.MODEL); add(PipelineStep.RADAR) }
            add(PipelineStep.TRANSCRIBE)
            add(PipelineStep.MOTION)
            add(PipelineStep.ANALYZE)
        }
        val totalWeight = steps.sumOf { it.weight.toDouble() }.toFloat()
        fun report(step: PipelineStep, fraction: Float) {
            val done = steps.takeWhile { it != step }.sumOf { it.weight.toDouble() }.toFloat()
            val overall = (done + step.weight * fraction.coerceIn(0f, 1f)) / totalWeight
            onProgress(PipelineProgress(steps, step, fraction.coerceIn(0f, 1f), overall))
        }

        val dir = projects.createDir(projectId)
        try {
            // 1) Importar: copia (o recorta sin recodificar) al almacenamiento privado, para poder reeditar.
            report(PipelineStep.IMPORT, 0f)
            val source = File(dir, SOURCE_NAME)
            val trimStart = request.trimStartMs
            val trimEnd = request.trimEndMs
            if (trimStart != null && trimEnd != null) {
                VideoTrimmer.trim(context, request.sourceUri, source, trimStart, trimEnd) { report(PipelineStep.IMPORT, it) }
            } else {
                copyToPrivateStorage(request.sourceUri, source) { report(PipelineStep.IMPORT, it) }
            }
            val info = withContext(Dispatchers.IO) { MediaUtils.probe(source) }
                ?: throw AppException(R.string.error_unreadable_video)
            if (info.durationMs < MIN_DURATION_MS) throw AppException(R.string.error_video_too_short)
            val thumbName = withContext(Dispatchers.IO) {
                MediaUtils.frameAt(source, (info.durationMs * 0.1).toLong(), 480)?.let {
                    MediaUtils.saveJpeg(it, File(dir, THUMB_NAME)); THUMB_NAME
                }
            }
            report(PipelineStep.IMPORT, 1f)

            // 2) Datos del enlace – opcional y tolerante a fallos (si falla, todo sigue con audio + texto).
            var meta: SourceMeta? = null
            if (link != null) {
                report(PipelineStep.LINK, 0f)
                meta = try {
                    linkMetadata.fetch(link)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
                report(PipelineStep.LINK, 1f)
            }

            // 3) Audio Radar (energía del audio). Opcional: sin pista de audio o si falla, se sigue sin él.
            suspend fun radar(): AudioEnergyTrack? {
                report(PipelineStep.RADAR, 0f)
                val track = if (!info.hasAudio) null else try {
                    AudioDecoder.decodeEnergy(source) { report(PipelineStep.RADAR, it) }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
                report(PipelineStep.RADAR, 1f)
                return track
            }
            suspend fun ensureModel(): File {
                if (!needModel) return models.modelDir(request.language)
                report(PipelineStep.MODEL, 0f)
                return models.ensureInstalled(request.language) { report(PipelineStep.MODEL, it) }
            }

            val energy: AudioEnergyTrack?
            val modelDir: File
            if (fast) { energy = radar(); modelDir = ensureModel() } else { modelDir = ensureModel(); energy = radar() }
            val profile = energy?.let(::AudioProfile)

            // Modo rápido: ventanas de 2 min alrededor de los 5 picos más fuertes de TODO el audio.
            val windows: List<TimeSpan> = if (fast && profile != null) FastModePlanner.plan(profile, info.durationMs) else emptyList()

            // 4) Transcripción local (todo el video, o sólo las ventanas en modo rápido).
            report(PipelineStep.TRANSCRIBE, 0f)
            val words: List<WordTiming> = if (!info.hasAudio || (fast && windows.isEmpty())) emptyList() else {
                try {
                    if (fast) {
                        transcriber.transcribeRanges(source, modelDir, windows.map { it.startMs to it.endMs }) { i, p ->
                            report(PipelineStep.TRANSCRIBE, (i + p) / windows.size)
                        }
                    } else {
                        transcriber.transcribe(source, modelDir) { report(PipelineStep.TRANSCRIBE, it) }
                    }
                } catch (_: NoAudioTrackException) {
                    emptyList()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Modelo corrupto o códec no soportado: se borra el modelo para forzar una nueva descarga.
                    if (e is IOException) models.delete(request.language)
                    throw AppException(R.string.error_transcription, cause = e)
                } catch (e: UnsatisfiedLinkError) {
                    throw AppException(R.string.error_transcription, cause = e)
                }
            }
            val clean = words.map { it.copy(endMs = minOf(it.endMs, info.durationMs)) }.filter { it.startMs < info.durationMs }
            report(PipelineStep.TRANSCRIBE, 1f)

            // 5) Señales para puntuar. Sólo YouTube aporta heatmap; sin él se usa el análisis multimedia local.
            currentCoroutineContext().ensureActive()
            val heat = meta?.let { YouTubeHtmlParser.rescale(it.heatmap, it.durationMs, info.durationMs) }.orEmpty()
            val chat = meta?.chat?.let { ChatActivity.from(it, info.durationMs) } // null si hay pocos mensajes: se ignora
            val baseSignals = MediaSignals(clean, info.durationMs, profile, motion = null, chat = chat)
            val cfg = AnalyzerConfig().clamped(info.durationMs)

            report(PipelineStep.MOTION, 0f)
            val preselected = if (heat.isEmpty()) MultimediaClipGenerator.preselect(baseSignals, request.language, cfg) else emptyList()
            // Detector de rostros sólo en las mejores ventanas (normal) o en las ventanas de 2 min (rápido), nunca en las 4 h.
            val motionSpans = when {
                heat.isNotEmpty() -> emptyList()
                fast -> windows
                else -> preselected.map { TimeSpan(it.startMs, it.endMs) }
            }
            val motion = sampleMotion(source, motionSpans) { report(PipelineStep.MOTION, it) }
            report(PipelineStep.MOTION, 1f)

            // 6) Puntaje final y clips.
            report(PipelineStep.ANALYZE, 0f)
            val cues = CueBuilder.build(clean, wide = !request.language.spaced)
            val clips: List<ClipSuggestion> = withContext(Dispatchers.Default) {
                if (heat.isNotEmpty()) {
                    ClipAnalyzer.suggest(clean, heat, info.durationMs, request.language)
                } else {
                    MultimediaClipGenerator.finalize(preselected, baseSignals.copy(motion = motion), request.language, cfg)
                        .ifEmpty { ClipAnalyzer.suggest(clean, emptyList(), info.durationMs, request.language) }
                }
            }
            report(PipelineStep.ANALYZE, 1f)

            val now = System.currentTimeMillis()
            if (energy != null) projects.saveEnergy(projectId, energy)
            val project = Project(
                id = projectId,
                name = meta?.title ?: request.displayName?.substringBeforeLast('.')?.takeIf { it.isNotBlank() } ?: "Video",
                createdAt = now,
                updatedAt = now,
                sourceFile = SOURCE_NAME,
                thumbnailFile = thumbName,
                durationMs = info.durationMs,
                width = info.width,
                height = info.height,
                language = request.language.code,
                sourceUrl = link?.canonicalUrl,
                sourcePlatform = link?.platform?.name,
                hasHeatmap = heat.isNotEmpty(),
                heatmap = heat,
                words = clean,
                cues = cues,
                clips = clips,
                fastMode = fast,
                transcribedSpans = if (fast) windows else emptyList(),
                hasEnergy = energy != null,
                hasChat = chat != null,
            )
            projects.save(project)
            return project
        } catch (t: Throwable) {
            withContext(NonCancellable) { projects.delete(projectId) }
            throw t
        }
    }

    /** Muestrea el rostro (1 fotograma cada 1.5 s) sólo en los tramos indicados. Null si el detector falla. */
    private suspend fun sampleMotion(source: File, spans: List<TimeSpan>, onProgress: (Float) -> Unit): MotionTrack? {
        if (spans.isEmpty()) return null
        val all = ArrayList<FacePoint>()
        spans.forEachIndexed { i, span ->
            val pts = faceTracker.track(source, span.startMs, span.endMs, MOTION_STEP_MS) { p -> onProgress((i + p) / spans.size) }
                ?: return null // detector no disponible: no se insiste
            all += pts
        }
        return MotionTrack(all).takeUnless { it.isEmpty }
    }

    private suspend fun copyToPrivateStorage(uri: Uri, target: File, onProgress: (Float) -> Unit) =
        withContext(Dispatchers.IO) {
            val total = MediaUtils.size(context, uri)
            if (total > 0 && target.parentFile!!.usableSpace < total + SPACE_MARGIN) throw AppException(R.string.error_no_space)
            val input = context.contentResolver.openInputStream(uri) ?: throw AppException(R.string.error_unreadable_video)
            try {
                input.use { ins ->
                    target.outputStream().use { out ->
                        val buf = ByteArray(256 * 1024)
                        var copied = 0L
                        while (true) {
                            val n = ins.read(buf)
                            if (n < 0) break
                            ensureActive()
                            out.write(buf, 0, n)
                            copied += n
                            if (total > 0) onProgress(copied.toFloat() / total)
                        }
                    }
                }
            } catch (e: IOException) {
                throw AppException(R.string.error_unreadable_video, cause = e)
            }
        }

    private companion object {
        const val SOURCE_NAME = "source.mp4"
        const val THUMB_NAME = "thumb.jpg"
        const val MIN_DURATION_MS = 5_000L
        const val SPACE_MARGIN = 100L * 1024 * 1024
        const val MOTION_STEP_MS = 1_500L
    }
}
