package com.shortsmaker.viral.ui.manual

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.shortsmaker.viral.AppContainer
import com.shortsmaker.viral.R
import com.shortsmaker.viral.domain.ManualClip
import com.shortsmaker.viral.ui.common.appViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface ManualEntryState {
    data object Creating : ManualEntryState
    data class Ready(val clipId: String) : ManualEntryState
    data object Failed : ManualEntryState
}

/**
 * Modo manual: crea (una sola vez) un clip que abarca el video completo y deja la pantalla lista para saltar
 * directamente al editor 9:16, sin pasar por las sugerencias de la IA.
 */
class ManualClipViewModel(private val container: AppContainer, private val projectId: String) : ViewModel() {
    private val _state = MutableStateFlow<ManualEntryState>(ManualEntryState.Creating)
    val state: StateFlow<ManualEntryState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            // Se carga desde disco: así se conservan las ediciones guardadas por el editor.
            val project = container.projects.load(projectId)
            if (project == null) {
                _state.value = ManualEntryState.Failed
                return@launch
            }
            val clip = ManualClip.create(project, System.currentTimeMillis())
            container.projects.save(project.copy(clips = project.clips + clip, updatedAt = System.currentTimeMillis()))
            _state.value = ManualEntryState.Ready(clip.id)
        }
    }
}

@Composable
fun ManualClipEntryScreen(projectId: String, onReady: (clipId: String) -> Unit, onFailed: () -> Unit) {
    val vm = appViewModel(key = "manual-$projectId") { ManualClipViewModel(it, projectId) }
    val state by vm.state.collectAsStateWithLifecycle()

    LaunchedEffect(state) {
        when (val s = state) {
            is ManualEntryState.Ready -> onReady(s.clipId)
            ManualEntryState.Failed -> onFailed()
            ManualEntryState.Creating -> Unit
        }
    }
    Scaffold { padding ->
        Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) {
            CircularProgressIndicator()
        }
    }
}
