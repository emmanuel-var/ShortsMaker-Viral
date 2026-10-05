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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import com.shortsmaker.viral.data.AnalysisState
import com.shortsmaker.viral.data.labelRes
import com.shortsmaker.viral.ui.common.KeepScreenOn
import com.shortsmaker.viral.ui.common.appViewModel
import com.shortsmaker.viral.ui.theme.BrandGradient
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/**
 * El análisis lo ejecuta el `AnalysisRunner` de la app (no depende de esta pantalla): se puede salir y volver a verlo
 * desde la pantalla de inicio. Este ViewModel sólo lo arranca y observa su estado.
 */
class ProcessingViewModel(private val container: AppContainer, private val projectId: String) : ViewModel() {
    private val lastRequest: ImportRequest? = container.pendingImports.remove(projectId)

    val state: StateFlow<AnalysisState> = container.analysis.state(projectId)
        .stateIn(viewModelScope, SharingStarted.Eagerly, AnalysisState())

    init {
        lastRequest?.let { container.analysis.start(projectId, it) }
    }

    fun retry() {
        lastRequest?.let { container.analysis.start(projectId, it) }
    }

    fun cancel() = container.analysis.cancel(projectId)
}

@Composable
fun ProcessingScreen(projectId: String, onDone: () -> Unit, onExit: () -> Unit) {
    val vm = appViewModel(key = "processing-$projectId") { ProcessingViewModel(it, projectId) }
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    KeepScreenOn() // la pantalla encendida evita que el sistema pause el análisis
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

            // Aviso: el análisis vive en la app; si el usuario cambia de app Android puede detenerlo.
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
                    Text(stringResource(R.string.keep_app_open_warning), color = MaterialTheme.colorScheme.onErrorContainer, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                }
            }
            Spacer(Modifier.height(20.dp))
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
            OutlinedButton(onClick = { vm.cancel(); onExit() }) { Text(stringResource(R.string.cancel)) }
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
