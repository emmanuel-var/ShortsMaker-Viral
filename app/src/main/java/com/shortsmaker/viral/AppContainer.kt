package com.shortsmaker.viral

import android.app.Application
import android.net.Uri
import com.shortsmaker.viral.ads.AdsManager
import com.shortsmaker.viral.data.AnalysisPipeline
import com.shortsmaker.viral.data.FaceTracker
import com.shortsmaker.viral.data.ProjectRepository
import com.shortsmaker.viral.data.SettingsRepository
import com.shortsmaker.viral.data.VideoExporter
import com.shortsmaker.viral.data.VoskModelManager
import com.shortsmaker.viral.data.VoskTranscriber
import com.shortsmaker.viral.data.LinkMetadataClient
import com.shortsmaker.viral.data.AnalysisRunner
import com.shortsmaker.viral.domain.Language
import com.shortsmaker.viral.domain.MediaLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

enum class ProcessingMode {
    /** Transcribe todo el video y analiza texto + audio + movimiento (+ chat). */
    NORMAL,
    /** Video largo forzado (> 15 min): Audio Radar → 5 picos → Vosk + detector de rostros sólo en ventanas de 2 min. */
    FAST,
}

/** Datos que viajan de la pantalla de importación al procesamiento . */
data class ImportRequest(
    val sourceUri: Uri,
    val displayName: String?,
    val language: Language,
    /** Enlace opcional (YouTube/Twitch/Kick). Sin enlace = flujo de video local con análisis sólo por transcripción. */
    val link: MediaLink?,
    val mode: ProcessingMode = ProcessingMode.NORMAL,
    /** Si no es null, el video se recorta (sin recodificar) a este tramo al importarlo. */
    val trimStartMs: Long? = null,
    val trimEndMs: Long? = null,
)

/** Inyección de dependencias manual (suficiente para esta app, sin KSP/Hilt). */
class AppContainer(val app: Application) {
    /** Ámbito que sobrevive a los ViewModels (para guardar ediciones al salir de una pantalla). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    val ads = AdsManager(app)
    val settings = SettingsRepository(app)
    val projects = ProjectRepository(app)
    val modelManager = VoskModelManager(app, http)
    val linkMetadata = LinkMetadataClient(app, http)
    val transcriber = VoskTranscriber()
    val faceTracker = FaceTracker()
    val exporter = VideoExporter(app)
    val pipeline = AnalysisPipeline(app, projects, modelManager, linkMetadata, transcriber, faceTracker)
    val analysis = AnalysisRunner(pipeline, appScope)

    val pendingImports = ConcurrentHashMap<String, ImportRequest>()
}
