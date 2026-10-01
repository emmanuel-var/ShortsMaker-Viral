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
import com.shortsmaker.viral.ui.common.KeepScreenOn
import com.shortsmaker.viral.ui.common.appViewModel
import com.shortsmaker.viral.ui.theme.BrandGradient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ProcessingUiState(
    val progress: PipelineProgress? = null,
    val error: AppException? = null,
    val finished: Boolean = false,
)

class ProcessingViewModel(private val container: AppContainer, private val projectId: String) : ViewModel() {
    private val _state = MutableStateFlow(ProcessingUiState())
    val state: StateFlow<ProcessingUiState> = _state.asStateFlow()

    private var request: ImportRequest? = container.pendingImports.remove(projectId)
    private var job: Job? = null

    init {
        start()
    }

    fun start() {
        val req = request
        if (req == null) {
            _state.value = ProcessingUiState(error = AppException(R.string.error_generic))
            return
        }
        job?.cancel()
        _state.value = ProcessingUiState()
        job = viewModelScope.launch {
            try {
                container.pipeline.run(projectId, req) { p -> _state.update { it.copy(progress = p) } }
                _state.update { it.copy(finished = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: AppException) {
                _state.update { it.copy(error = e) }
            } catch (e: Exception) {
                _state.update { it.copy(error = AppException(R.string.error_generic, cause = e)) }
            }
        }
    }

    override fun onCleared() {
        job?.cancel()
    }
}

private fun stepLabel(step: PipelineStep): Int = when (step) {
    PipelineStep.IMPORT -> R.string.step_import
    PipelineStep.LINK -> R.string.step_link
    PipelineStep.MODEL -> R.string.step_model
    PipelineStep.TRANSCRIBE -> R.string.step_transcribe
    PipelineStep.ANALYZE -> R.string.step_analyze
}

@Composable
fun ProcessingScreen(projectId: String, onDone: () -> Unit, onExit: () -> Unit) {
    val vm = appViewModel(key = "processing-$projectId") { ProcessingViewModel(it, projectId) }
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    KeepScreenOn()
    LaunchedEffect(state.finished) { if (state.finished) onDone() }

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
                    Button(onClick = vm::start) { Text(stringResource(R.string.retry)) }
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
                        label = stringResource(stepLabel(step)),
                        done = idx < currentIdx,
                        active = idx == currentIdx,
                        fraction = if (idx == currentIdx) progress?.stepFraction ?: 0f else 0f,
                    )
                }
            }

            Spacer(Modifier.height(28.dp))
            OutlinedButton(onClick = onExit) { Text(stringResource(R.string.cancel)) }
            Text(
                stringResource(R.string.keep_app_open),
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
