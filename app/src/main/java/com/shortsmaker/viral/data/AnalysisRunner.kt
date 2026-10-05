package com.shortsmaker.viral.data

import com.shortsmaker.viral.ImportRequest
import com.shortsmaker.viral.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

data class AnalysisState(
    val progress: PipelineProgress? = null,
    val error: AppException? = null,
    val finished: Boolean = false,
    /** El análisis fue cancelado por el usuario. */
    val cancelled: Boolean = false,
) {
    val running: Boolean get() = !finished && !cancelled && error == null
}

val PipelineStep.labelRes: Int
    get() = when (this) {
        PipelineStep.IMPORT -> R.string.step_import
        PipelineStep.LINK -> R.string.step_link
        PipelineStep.MODEL -> R.string.step_model
        PipelineStep.RADAR -> R.string.step_radar
        PipelineStep.TRANSCRIBE -> R.string.step_transcribe
        PipelineStep.MOTION -> R.string.step_motion
        PipelineStep.ANALYZE -> R.string.step_analyze
    }

/**
 * Ejecuta el análisis dentro del proceso de la app (sin servicios en primer plano ni permisos especiales).
 * Sobrevive a salir de la pantalla de procesamiento, pero NO a cerrar la app ni a cambiar de aplicación durante
 * mucho tiempo (Android podría detener el proceso), por eso la UI avisa al usuario de que no cambie de app.
 */
class AnalysisRunner(private val pipeline: AnalysisPipeline, private val scope: CoroutineScope) {
    private val _states = MutableStateFlow<Map<String, AnalysisState>>(emptyMap())
    val states: StateFlow<Map<String, AnalysisState>> = _states.asStateFlow()
    private val jobs = ConcurrentHashMap<String, Job>()

    fun state(projectId: String): Flow<AnalysisState> =
        _states.map { it[projectId] ?: AnalysisState() }.distinctUntilChanged()

    fun start(projectId: String, request: ImportRequest) {
        if (jobs[projectId]?.isActive == true) return
        set(projectId, AnalysisState())
        jobs[projectId] = scope.launch {
            try {
                pipeline.run(projectId, request) { p -> set(projectId, AnalysisState(progress = p)) }
                set(projectId, AnalysisState(finished = true))
            } catch (e: CancellationException) {
                set(projectId, AnalysisState(cancelled = true))
                throw e
            } catch (e: AppException) {
                set(projectId, AnalysisState(error = e))
            } catch (e: Exception) {
                set(projectId, AnalysisState(error = AppException(R.string.error_generic, cause = e)))
            }
        }
    }

    fun cancel(projectId: String) {
        jobs.remove(projectId)?.cancel()
    }

    private fun set(projectId: String, state: AnalysisState) {
        _states.update { it + (projectId to state) }
    }
}
