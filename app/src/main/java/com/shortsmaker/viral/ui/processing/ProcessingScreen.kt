package com.shortsmaker.viral.ui.processing

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.shortsmaker.viral.AppContainer
import com.shortsmaker.viral.ImportRequest
import com.shortsmaker.viral.R
import com.shortsmaker.viral.data.AppException
import com.shortsmaker.viral.data.PipelineProgress
import com.shortsmaker.viral.data.PipelineStep
import com.shortsmaker.viral.work.AnalysisWorker
import com.shortsmaker.viral.work.labelRes
import com.shortsmaker.viral.ui.common.appViewModel
import com.shortsmaker.viral.ui.theme.BrandGradient
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class ProcessingUiState(
    val progress: PipelineProgress? = null,
    val error: AppException? = null,
    val finished: Boolean = false,
    /** El trabajo fue cancelado (por el usuario o por el sistema). */
    val cancelled: Boolean = false,
)

/**
 * El análisis NO se ejecuta aquí sino en un `AnalysisWorker` (WorkManager + Foreground Service con notificación
 * persistente), de modo que sigue aunque el usuario salga de esta pantalla o cambie de aplicación. Este
 * ViewModel sólo lo encola y observa su progreso.
 */
class ProcessingViewModel(private val container: AppContainer, private val projectId: String) : ViewModel() {
    private val app = container.app
    private var lastRequest: ImportRequest? = container.pendingImports.remove(projectId)

    val state: StateFlow<ProcessingUiState> = WorkManager.getInstance(app)
        .getWorkInfosForUniqueWorkFlow(AnalysisWorker.uniqueName(projectId))
        .map { infos -> infos.firstOrNull()?.toUiState() ?: ProcessingUiState() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProcessingUiState())

    init {
        // Primera vez: se guarda la petición en disco (para poder reanudar) y se encola el trabajo.
        lastRequest?.let { enqueue(it) }
    }

    private fun enqueue(request: ImportRequest) {
        container.pendingRequests.save(projectId, request)
        AnalysisWorker.enqueue(app, projectId)
    }

    fun retry() {
        val req = lastRequest ?: return
        enqueue(req)
    }

    fun cancel() {
        AnalysisWorker.cancel(app, projectId)
        container.pendingRequests.delete(projectId)
    }

    private fun WorkInfo.toUiState(): ProcessingUiState = when (state) {
        WorkInfo.State.SUCCEEDED -> ProcessingUiState(finished = true)
        WorkInfo.State.CANCELLED -> ProcessingUiState(cancelled = true)
        WorkInfo.State.FAILED -> {
            val res = outputData.getInt(AnalysisWorker.KEY_ERROR_RES, R.string.error_generic)
            val args = outputData.getStringArray(AnalysisWorker.KEY_ERROR_ARGS)?.toList().orEmpty()
            ProcessingUiState(error = AppException(res, args))
        }
        else -> ProcessingUiState(progress = with(AnalysisWorker) { progress.toPipelineProgress() })
    }
}

@Composable
fun ProcessingScreen(projectId: String, onDone: () -> Unit, onExit: () -> Unit) {
    val vm = appViewModel(key = "processing-$projectId") { ProcessingViewModel(it, projectId) }
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(state.finished) { if (state.finished) onDone() }
    LaunchedEffect(state.cancelled) { if (state.cancelled) onExit() }

    Scaffold { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            val error = state.error
            if (error != null) {
                Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(56.dp))
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.processing_failed), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text(error.localized(context), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(24.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = onExit) { Text(stringResource(R.string.back)) }
                    Button(onClick = vm::retry) { Text(stringResource(R.string.retry)) }
                }
                return@Column
            }

            PulsingOrb()
            Spacer(Modifier.height(24.dp))
            Text(stringResource(R.string.processing_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
            Text(
                stringResource(R.string.processing_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp),
            )
            Spacer(Modifier.height(24.dp))

            val progress = state.progress
            val overall = progress?.overall ?: 0f
            LinearProgressIndicator(progress = { overall }, modifier = Modifier.fillMaxWidth().height(10.dp).clip(CircleShape))
            Text("${(overall * 100).toInt()}%", modifier = Modifier.padding(top = 6.dp), fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(20.dp))

            val steps = progress?.steps ?: listOf(PipelineStep.IMPORT, PipelineStep.TRANSCRIBE, PipelineStep.ANALYZE)
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                steps.forEach { step ->
                    val currentIdx = progress?.let { steps.indexOf(it.current) } ?: 0
                    val idx = steps.indexOf(step)
                    StepRow(
                        label = stringResource(step.labelRes),
                        done = idx < currentIdx,
                        active = idx == currentIdx,
                        fraction = if (idx == currentIdx) progress?.stepFraction ?: 0f else 0f,
                    )
                }
            }

            Spacer(Modifier.height(28.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                // Salir sin cancelar: el análisis sigue en segundo plano con su notificación.
                Button(onClick = onExit) { Text(stringResource(R.string.run_in_background)) }
                OutlinedButton(onClick = { vm.cancel(); onExit() }) { Text(stringResource(R.string.cancel)) }
            }
            Text(
                stringResource(R.string.background_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

@Composable
private fun StepRow(label: String, done: Boolean, active: Boolean, fraction: Float) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
            when {
                done -> Icon(Icons.Filled.CheckCircle, null, tint = Color(0xFF1DB954), modifier = Modifier.size(26.dp))
                active -> CircularProgressIndicator(progress = { fraction.coerceAtLeast(0.04f) }, strokeWidth = 3.dp, modifier = Modifier.size(24.dp))
                else -> Icon(Icons.Filled.RadioButtonUnchecked, null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(26.dp))
            }
        }
        Text(
            label,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
            color = if (done || active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PulsingOrb() {
    val transition = rememberInfiniteTransition(label = "orb")
    val scale by transition.animateFloat(
        initialValue = 0.88f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "scale",
    )
    Box(
        Modifier.size(104.dp).scale(scale).clip(CircleShape).background(BrandGradient),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.Whatshot, contentDescription = null, tint = Color.White, modifier = Modifier.size(56.dp))
    }
}
