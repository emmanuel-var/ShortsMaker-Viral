package com.shortsmaker.viral.work

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.shortsmaker.viral.R
import com.shortsmaker.viral.ShortsMakerApp
import com.shortsmaker.viral.data.AppException
import com.shortsmaker.viral.data.PipelineProgress
import com.shortsmaker.viral.data.PipelineStep
import com.shortsmaker.viral.ui.withLanguage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/**
 * Análisis en segundo plano: WorkManager + Foreground Service con notificación de progreso persistente, para que
 * Android no mate el proceso cuando el usuario cambia de app (imprescindible con videos largos).
 */
class AnalysisWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val container get() = (applicationContext as ShortsMakerApp).container
    private val projectId: String get() = inputData.getString(KEY_PROJECT_ID).orEmpty()

    /** Contexto con el idioma elegido en la app, para los textos de la notificación. */
    private val localized: Context get() = applicationContext.withLanguage(container.settings.settings.value.uiLanguage)

    /** Lo usa WorkManager en trabajos "expedited" (Android < 12) y como respaldo del foreground. */
    override suspend fun getForegroundInfo(): ForegroundInfo =
        AnalysisNotifications.foregroundInfo(localized, id, projectId, null)

    override suspend fun doWork(): Result {
        val request = container.pendingRequests.load(projectId)
            ?: return Result.failure(errorData(AppException(R.string.error_generic)))

        // Sube a primer plano. Si el sistema no lo permite (p. ej. ya en segundo plano en Android 12+), se continúa igual.
        try {
            setForeground(AnalysisNotifications.foregroundInfo(localized, id, projectId, null))
        } catch (e: IllegalStateException) {
            Log.w(TAG, "No se pudo iniciar el servicio en primer plano", e)
        }

        val latest = MutableStateFlow<PipelineProgress?>(null)
        return coroutineScope {
            // Publica el progreso (para la UI) y actualiza la notificación como máximo ~2 veces por segundo.
            val reporter = launch {
                latest.filterNotNull().collect { p ->
                    setProgress(p.toData())
                    try { setForeground(AnalysisNotifications.foregroundInfo(localized, id, projectId, p)) } catch (_: IllegalStateException) { }
                    delay(500)
                }
            }
            try {
                val project = container.pipeline.run(projectId, request) { latest.value = it }
                container.pendingRequests.delete(projectId)
                AnalysisNotifications.notifyDone(localized, projectId, project.name)
                Result.success(workDataOf(KEY_PROJECT_ID to projectId))
            } catch (e: CancellationException) {
                throw e // cancelación del usuario o del sistema: el pipeline ya limpió el proyecto
            } catch (e: AppException) {
                container.pendingRequests.delete(projectId)
                Result.failure(errorData(e))
            } catch (e: Exception) {
                Log.e(TAG, "Fallo inesperado en el análisis", e)
                container.pendingRequests.delete(projectId)
                Result.failure(errorData(AppException(R.string.error_generic, cause = e)))
            } finally {
                reporter.cancel()
            }
        }
    }

    private fun errorData(e: AppException) = workDataOf(
        KEY_ERROR_RES to e.messageRes,
        KEY_ERROR_ARGS to e.formatArgs.map { it.toString() }.toTypedArray(),
    )

    companion object {
        private const val TAG = "AnalysisWorker"
        const val KEY_PROJECT_ID = "project_id"
        const val KEY_ERROR_RES = "error_res"
        const val KEY_ERROR_ARGS = "error_args"
        private const val KEY_STEPS = "steps"
        private const val KEY_CURRENT = "current"
        private const val KEY_FRACTION = "fraction"
        private const val KEY_OVERALL = "overall"

        fun uniqueName(projectId: String) = "analysis_$projectId"

        /** Encola el análisis. `KEEP`: si ya está en marcha (p. ej. al rotar la pantalla) no se duplica. */
        fun enqueue(context: Context, projectId: String) {
            val request = OneTimeWorkRequestBuilder<AnalysisWorker>()
                .setInputData(workDataOf(KEY_PROJECT_ID to projectId))
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .addTag(TAG_ANALYSIS)
                .addTag(PROJECT_TAG_PREFIX + projectId)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(uniqueName(projectId), ExistingWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context, projectId: String) {
            WorkManager.getInstance(context).cancelUniqueWork(uniqueName(projectId))
        }

        const val TAG_ANALYSIS = "analysis"
        const val PROJECT_TAG_PREFIX = "project:"

        fun PipelineProgress.toData(): Data = workDataOf(
            KEY_STEPS to steps.map { it.ordinal }.toIntArray(),
            KEY_CURRENT to current.ordinal,
            KEY_FRACTION to stepFraction,
            KEY_OVERALL to overall,
        )

        fun Data.toPipelineProgress(): PipelineProgress? {
            val ordinals = getIntArray(KEY_STEPS) ?: return null
            if (ordinals.isEmpty()) return null
            val all = PipelineStep.entries
            return PipelineProgress(
                steps = ordinals.map { all[it] },
                current = all[getInt(KEY_CURRENT, 0)],
                stepFraction = getFloat(KEY_FRACTION, 0f),
                overall = getFloat(KEY_OVERALL, 0f),
            )
        }
    }
}
