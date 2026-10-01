package com.shortsmaker.viral.ui.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shortsmaker.viral.AppContainer
import com.shortsmaker.viral.domain.ClipEdit
import com.shortsmaker.viral.domain.ClipSuggestion
import com.shortsmaker.viral.domain.FacePoint
import com.shortsmaker.viral.domain.TimeSpan
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
import kotlin.math.max
import kotlin.math.min

sealed interface FaceStatus {
    data object Idle : FaceStatus
    data class Running(val progress: Float) : FaceStatus
    data class Done(val points: Int) : FaceStatus
    data object Unavailable : FaceStatus
    /** El fragmento es demasiado largo para analizar el rostro: hay que recortarlo más. */
    data object TooLong : FaceStatus
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
    private var faceDebounce: Job? = null

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
            // Clips sugeridos: se analiza el rostro de inmediato. Modo manual (video completo): al recortar.
            if (edit.framing.autoTrack) scheduleFaceDetection(immediate = !clip.manual)
        }
    }

    fun setRange(startMs: Long, endMs: Long) {
        updateEdit { it.copy(startMs = startMs, endMs = endMs) }
        // El auto-encuadre debe seguir funcionando sobre el fragmento que el usuario recorta, así que se
        // (re)analiza el rostro del nuevo tramo cuando deja de arrastrar.
        scheduleFaceDetection()
    }

    fun setStyle(style: SubtitleStyle) = updateEdit { it.copy(style = style) }

    fun setFraming(framing: Framing) {
        val wasAuto = _state.value.edit?.framing?.autoTrack ?: false
        updateEdit { it.copy(framing = framing) }
        if (framing.autoTrack && !wasAuto) scheduleFaceDetection(immediate = true)
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
        _state.update { st ->
            st.copy(
                faceStatus = FaceStatus.Idle,
                project = st.project?.let { it.copy(faceCoverage = it.faceCoverage - clipId, faceTracks = it.faceTracks - clipId) },
                faceTrack = null,
            )
        }
        scheduleFaceDetection(immediate = true)
    }

    /** Guarda y después ejecuta `then` (p. ej. navegar a exportar con los datos ya persistidos). */
    fun saveAndThen(then: () -> Unit) {
        saveJob?.cancel()
        viewModelScope.launch {
            // Si el usuario acaba de recortar, se espera a que termine el análisis del rostro del nuevo tramo.
            faceDebounce?.join()
            persist(_state.value)
            then()
        }
    }

    /** ¿Hay que analizar el rostro del tramo actual? (autoTrack activo, tramo corto y sin cobertura previa). */
    private fun needsFaceDetection(st: EditorUiState): Boolean {
        val edit = st.edit ?: return false
        val project = st.project ?: return false
        if (!edit.framing.autoTrack) return false
        val span = faceSpanFor(edit, project)
        if (span.endMs - span.startMs > MAX_FACE_SPAN_MS) return false
        val cov = project.faceCoverage[clipId]
        return cov == null || edit.startMs < cov.startMs || edit.endMs > cov.endMs
    }

    private fun faceSpanFor(edit: ClipEdit, project: Project) = TimeSpan(
        (edit.startMs - FACE_MARGIN_MS).coerceAtLeast(0),
        (edit.endMs + FACE_MARGIN_MS).coerceAtMost(project.durationMs),
    )

    private fun scheduleFaceDetection(immediate: Boolean = false) {
        faceDebounce?.cancel()
        val st = _state.value
        val edit = st.edit ?: return
        val project = st.project ?: return
        if (!edit.framing.autoTrack) return
        val span = faceSpanFor(edit, project)
        if (span.endMs - span.startMs > MAX_FACE_SPAN_MS) {
            _state.update { it.copy(faceStatus = FaceStatus.TooLong) }
            return
        }
        if (!needsFaceDetection(st)) {
            // Ya cubierto: sólo se refresca el estado visible.
            val pts = project.faceTracks[clipId]?.size ?: 0
            _state.update { it.copy(faceStatus = if (pts > 0) FaceStatus.Done(pts) else FaceStatus.Unavailable) }
            return
        }
        _state.update { it.copy(faceStatus = FaceStatus.Running(0f)) }
        faceDebounce = viewModelScope.launch {
            if (!immediate) delay(FACE_DEBOUNCE_MS)
            runFaceDetection()
        }
    }

    private suspend fun runFaceDetection() {
        val st = _state.value
        val edit = st.edit ?: return
        val project = st.project ?: return
        val source = sourceFile ?: return
        val want = faceSpanFor(edit, project)
        val cov = project.faceCoverage[clipId]
        val old = project.faceTracks[clipId].orEmpty()

        // Segmentos que faltan por analizar. Si el tramo nuevo no toca lo ya analizado, se reemplaza todo.
        val touches = cov != null && want.startMs <= cov.endMs && want.endMs >= cov.startMs
        val segments = if (touches && cov != null) buildList {
            if (want.startMs < cov.startMs) add(TimeSpan(want.startMs, cov.startMs))
            if (want.endMs > cov.endMs) add(TimeSpan(cov.endMs, want.endMs))
        } else listOf(want)
        val base = if (touches) old else emptyList()

        val collected = ArrayList<FacePoint>(base)
        var failed = false
        segments.forEachIndexed { idx, seg ->
            val step = max(FACE_STEP_MS, (seg.endMs - seg.startMs) / FACE_MAX_FRAMES)
            val pts = container.faceTracker.track(source, seg.startMs, seg.endMs, step) { p ->
                val overall = (idx + p) / segments.size
                _state.update { s -> if (s.faceStatus is FaceStatus.Running) s.copy(faceStatus = FaceStatus.Running(overall)) else s }
            }
            if (pts == null) failed = true else collected += pts
        }
        val merged = collected.distinctBy { it.timeMs }.sortedBy { it.timeMs }
        val hull = if (touches && cov != null) {
            TimeSpan(min(cov.startMs, want.startMs), max(cov.endMs, want.endMs))
        } else want

        _state.update { s ->
            val p = s.project ?: return@update s
            if (failed && merged.isEmpty()) {
                // El detector no se pudo iniciar: no se marca cobertura para poder reintentar.
                return@update s.copy(faceStatus = FaceStatus.Unavailable)
            }
            s.copy(
                faceTrack = if (merged.isEmpty()) null else FaceTrack(merged),
                faceStatus = if (merged.isEmpty()) FaceStatus.Unavailable else FaceStatus.Done(merged.size),
                project = p.copy(faceTracks = p.faceTracks + (clipId to merged), faceCoverage = p.faceCoverage + (clipId to hull)),
            )
        }
        scheduleSave()
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

    private companion object {
        const val FACE_MARGIN_MS = 2_000L
        const val FACE_DEBOUNCE_MS = 1_200L
        const val FACE_STEP_MS = 700L
        const val FACE_MAX_FRAMES = 160L
        /** Más de 2 minutos de tramo se considera demasiado para analizar el rostro en el teléfono. */
        const val MAX_FACE_SPAN_MS = 124_000L
    }
}
