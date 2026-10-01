package com.shortsmaker.viral.data

import android.content.Context
import android.net.Uri
import com.shortsmaker.viral.ImportRequest
import com.shortsmaker.viral.R
import com.shortsmaker.viral.domain.ClipAnalyzer
import com.shortsmaker.viral.domain.CueBuilder
import com.shortsmaker.viral.domain.Project
import com.shortsmaker.viral.domain.WordTiming
import com.shortsmaker.viral.domain.YouTubeHtmlParser
import com.shortsmaker.viral.domain.YouTubeMeta
import com.shortsmaker.viral.domain.YouTubeUrl
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
    YOUTUBE(0.08f),
    MODEL(0.25f),
    TRANSCRIBE(0.40f),
    ANALYZE(0.15f),
}

data class PipelineProgress(
    val steps: List<PipelineStep>,
    val current: PipelineStep,
    val stepFraction: Float,
    val overall: Float,
)

/**
 * Importar → (datos de YouTube) → (modelo de voz, sólo la primera vez) → transcribir → analizar momentos virales.
 * Todo se ejecuta en el teléfono. Si algo falla o se cancela, se borra el proyecto a medias.
 */
class AnalysisPipeline(
    private val context: Context,
    private val projects: ProjectRepository,
    private val models: VoskModelManager,
    private val youTube: YouTubeClient,
    private val transcriber: VoskTranscriber,
) {

    suspend fun run(projectId: String, request: ImportRequest, onProgress: (PipelineProgress) -> Unit): Project {
        val videoId = request.youtubeUrl?.let(YouTubeUrl::extractVideoId)
        val steps = buildList {
            add(PipelineStep.IMPORT)
            if (videoId != null) add(PipelineStep.YOUTUBE)
            if (!models.isInstalled(request.language)) add(PipelineStep.MODEL)
            add(PipelineStep.TRANSCRIBE)
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
            // 1) Importar: copia el video al almacenamiento privado (así se puede reeditar aunque el original desaparezca).
            report(PipelineStep.IMPORT, 0f)
            val source = File(dir, SOURCE_NAME)
            copyToPrivateStorage(request.sourceUri, source) { report(PipelineStep.IMPORT, it) }
            val info = withContext(Dispatchers.IO) { MediaUtils.probe(source) }
                ?: throw AppException(R.string.error_unreadable_video)
            if (info.durationMs < MIN_DURATION_MS) throw AppException(R.string.error_video_too_short)
            val thumbName = withContext(Dispatchers.IO) {
                MediaUtils.frameAt(source, (info.durationMs * 0.1).toLong(), 480)?.let {
                    MediaUtils.saveJpeg(it, File(dir, THUMB_NAME)); THUMB_NAME
                }
            }
            report(PipelineStep.IMPORT, 1f)

            // 2) Datos de YouTube (heatmap) – opcional y tolerante a fallos.
            var meta: YouTubeMeta? = null
            if (videoId != null) {
                report(PipelineStep.YOUTUBE, 0f)
                meta = youTube.fetch(videoId)
                report(PipelineStep.YOUTUBE, 1f)
            }

            // 3) Modelo de voz (primera vez).
            val modelDir = if (PipelineStep.MODEL in steps) {
                report(PipelineStep.MODEL, 0f)
                models.ensureInstalled(request.language) { report(PipelineStep.MODEL, it) }
            } else models.modelDir(request.language)

            // 4) Transcripción local.
            report(PipelineStep.TRANSCRIBE, 0f)
            val words: List<WordTiming> = if (info.hasAudio) {
                try {
                    transcriber.transcribe(source, modelDir) { report(PipelineStep.TRANSCRIBE, it) }
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
            } else emptyList()
            val clean = words.map { it.copy(endMs = minOf(it.endMs, info.durationMs)) }.filter { it.startMs < info.durationMs }
            report(PipelineStep.TRANSCRIBE, 1f)

            // 5) Análisis viral.
            report(PipelineStep.ANALYZE, 0f)
            currentCoroutineContext().ensureActive()
            val heat = meta?.let { YouTubeHtmlParser.rescale(it.heatmap, it.durationMs, info.durationMs) }.orEmpty()
            val cues = CueBuilder.build(clean)
            val clips = withContext(Dispatchers.Default) {
                ClipAnalyzer.suggest(clean, heat, info.durationMs, request.language)
            }
            report(PipelineStep.ANALYZE, 1f)

            val now = System.currentTimeMillis()
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
                youtubeUrl = videoId?.let(YouTubeUrl::canonicalUrl),
                hasHeatmap = heat.isNotEmpty(),
                heatmap = heat,
                words = clean,
                cues = cues,
                clips = clips,
            )
            projects.save(project)
            return project
        } catch (t: Throwable) {
            withContext(NonCancellable) { projects.delete(projectId) }
            throw t
        }
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
    }
}
