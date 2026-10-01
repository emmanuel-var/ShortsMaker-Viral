package com.shortsmaker.viral

import android.app.Application
import android.net.Uri
import com.shortsmaker.viral.data.AnalysisPipeline
import com.shortsmaker.viral.data.FaceTracker
import com.shortsmaker.viral.data.ProjectRepository
import com.shortsmaker.viral.data.SettingsRepository
import com.shortsmaker.viral.data.VideoExporter
import com.shortsmaker.viral.data.VoskModelManager
import com.shortsmaker.viral.data.VoskTranscriber
import com.shortsmaker.viral.data.YouTubeClient
import com.shortsmaker.viral.domain.Language
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Datos que viajan de la pantalla de importación a la de procesamiento. */
data class ImportRequest(
    val sourceUri: Uri,
    val displayName: String?,
    val language: Language,
    val youtubeUrl: String?,
)

/** Inyección de dependencias manual (suficiente para esta app, sin KSP/Hilt). */
class AppContainer(val app: Application) {
    /** Ámbito que sobrevive a los ViewModels (para guardar ediciones al salir de una pantalla). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    val settings = SettingsRepository(app)
    val projects = ProjectRepository(app)
    val modelManager = VoskModelManager(app, http)
    val youTube = YouTubeClient(app, http)
    val transcriber = VoskTranscriber()
    val faceTracker = FaceTracker(app)
    val exporter = VideoExporter(app)
    val pipeline = AnalysisPipeline(app, projects, modelManager, youTube, transcriber)

    val pendingImports = ConcurrentHashMap<String, ImportRequest>()
}
