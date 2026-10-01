package com.shortsmaker.viral.ui.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shortsmaker.viral.AppContainer
import com.shortsmaker.viral.domain.ClipEdit
import com.shortsmaker.viral.domain.ClipSuggestion
import com.shortsmaker.viral.domain.FaceTrack
import com.shortsmaker.viral.domain.Framing
import com.shortsmaker.viral.domain.Project
import com.shortsmaker.viral.domain.SubtitleStyle
import com.shortsmaker.viral.domain.SubtitleTemplates
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

sealed interface FaceStatus {
    data object Idle : FaceStatus
    data class Running(val progress: Float) : FaceStatus
    data class Done(val points: Int) : FaceStatus
    data object Unavailable : FaceStatus
}

data class EditorUiState(
    val loading: Boolean = true,
    val missing: Boolean = false,
    val project: Project? = null,
    val clip: ClipSuggestion? = null,
    val edit: ClipEdit? = null,
    val faceTrack: FaceTrack? = null,
    val faceStatus: FaceStatus = FaceStatus.Idle,
)

class EditorViewModel(
    private val container: AppContainer,
    private val projectId: String,
    private val clipId: String,
) : ViewModel() {
    private val repo = container.projects
    private val _state = MutableStateFlow(EditorUiState())
    val state: StateFlow<EditorUiState> = _state.asStateFlow()

    var sourceFile: File? = null
        private set

    private var saveJob: Job? = null
    private var faceJob: Job? = null

    init {
        viewModelScope.launch {
            val project = repo.load(projectId)
            val clip = project?.clips?.firstOrNull { it.id == clipId }
            if (project == null || clip == null) {
                _state.value = EditorUiState(loading = false, missing = true)
                return@launch
            }
            sourceFile = repo.sourceFile(project)
            val edit = project.edits[clipId]
                ?: ClipEdit(clipId, clip.startMs, clip.endMs, SubtitleTemplates.all.first())
            val raw = project.faceTracks[clipId]
            _state.value = EditorUiState(
                loading = false,
                project = project,
                clip = clip,
                edit = edit,
                faceTrack = raw?.let { FaceTrack(it) },
                faceStatus = if (raw != null) FaceStatus.Done(raw.size) else FaceStatus.Idle,
            )
            if (edit.framing.autoTrack && raw == null) detectFace()
        }
    }

    fun setRange(startMs: Long, endMs: Long) = updateEdit { it.copy(startMs = startMs, endMs = endMs) }

    fun setStyle(style: SubtitleStyle) = updateEdit { it.copy(style = style) }

    fun setFraming(framing: Framing) {
        updateEdit { it.copy(framing = framing) }
        val s = _state.value
        if (framing.autoTrack && s.faceTrack == null && s.faceStatus !is FaceStatus.Running && s.faceStatus != FaceStatus.Unavailable) {
            detectFace()
        }
    }

    /** Corrige el texto de un bloque de subtítulos (índice dentro de `project.cues`). */
    fun editCue(index: Int, text: String) {
        _state.update { st ->
            val p = st.project ?: return@update st
            if (index !in p.cues.indices) return@update st
            st.copy(project = p.copy(cues = p.cues.toMutableList().also { it[index] = it[index].withText(text) }))
        }
        scheduleSave()
    }

    fun retryFaceDetection() {
        _state.update { it.copy(faceStatus = FaceStatus.Idle) }
        detectFace()
    }

    /** Guarda y después ejecuta `then` (p. ej. navegar a exportar con los datos ya persistidos). */
    fun saveAndThen(then: () -> Unit) {
        saveJob?.cancel()
        viewModelScope.launch {
            persist(_state.value)
            then()
        }
    }

    private fun detectFace() {
        val s = _state.value
        val project = s.project ?: return
        val clip = s.clip ?: return
        val source = sourceFile ?: return
        if (faceJob?.isActive == true) return
        faceJob = viewModelScope.launch {
            _state.update { it.copy(faceStatus = FaceStatus.Running(0f)) }
            val from = (clip.startMs - 5_000).coerceAtLeast(0)
            val to = (clip.endMs + 5_000).coerceAtMost(project.durationMs)
            val points = container.faceTracker.track(source, from, to) { progress ->
                _state.update { st -> if (st.faceStatus is FaceStatus.Running) st.copy(faceStatus = FaceStatus.Running(progress)) else st }
            }
            if (points.isNullOrEmpty()) {
                _state.update { it.copy(faceStatus = FaceStatus.Unavailable) }
            } else {
                _state.update { st ->
                    st.copy(
                        faceTrack = FaceTrack(points),
                        faceStatus = FaceStatus.Done(points.size),
                        project = st.project?.let { it.copy(faceTracks = it.faceTracks + (clipId to points)) },
                    )
                }
                scheduleSave()
            }
        }
    }

    private fun updateEdit(block: (ClipEdit) -> ClipEdit) {
        _state.update { st -> st.edit?.let { st.copy(edit = block(it)) } ?: st }
        scheduleSave()
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(500)
            persist(_state.value)
        }
    }

    private suspend fun persist(s: EditorUiState) {
        val project = s.project ?: return
        val edit = s.edit ?: return
        repo.save(project.copy(edits = project.edits + (clipId to edit), updatedAt = System.currentTimeMillis()))
    }

    override fun onCleared() {
        // Último guardado fuera del ámbito del ViewModel (que ya se está cancelando).
        val snapshot = _state.value
        if (snapshot.project != null) container.appScope.launch { persist(snapshot) }
    }
}
