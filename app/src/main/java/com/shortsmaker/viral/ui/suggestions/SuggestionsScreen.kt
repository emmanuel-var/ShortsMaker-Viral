@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.shortsmaker.viral.ui.suggestions

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.shortsmaker.viral.AppContainer
import com.shortsmaker.viral.R
import com.shortsmaker.viral.data.formatTime
import com.shortsmaker.viral.domain.ClipSuggestion
import com.shortsmaker.viral.domain.Project
import com.shortsmaker.viral.ui.common.BitmapOrPlaceholder
import com.shortsmaker.viral.ui.common.ScoreBadge
import com.shortsmaker.viral.ui.common.rememberVideoFrame
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import com.shortsmaker.viral.ui.common.appViewModel

class SuggestionsViewModel(private val container: AppContainer, private val projectId: String) : ViewModel() {
    private val _project = MutableStateFlow<Project?>(null)
    val project: StateFlow<Project?> = _project.asStateFlow()
    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    val sourceFile: File? get() = _project.value?.let { container.projects.sourceFile(it) }

    init {
        reload()
    }

    /** Relee el proyecto del disco (p. ej. al volver del editor, que pudo crear/editar clips). */
    fun reload() {
        viewModelScope.launch {
            _project.value = container.projects.load(projectId)
            _loaded.value = true
        }
    }

    /** Elimina un clip manual (los sugeridos por la IA no se pueden borrar). */
    fun deleteManualClip(clipId: String) {
        viewModelScope.launch {
            // Se recarga desde disco: el editor pudo guardar ediciones desde que se cargó esta pantalla.
            val p = container.projects.load(projectId) ?: return@launch
            if (p.clips.none { it.id == clipId && it.manual }) return@launch
            val updated = p.copy(
                clips = p.clips.filterNot { it.id == clipId },
                edits = p.edits - clipId,
                faceTracks = p.faceTracks - clipId,
                faceCoverage = p.faceCoverage - clipId,
                updatedAt = System.currentTimeMillis(),
            )
            container.projects.save(updated)
            _project.value = updated
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SuggestionsScreen(projectId: String, onBack: () -> Unit, onEdit: (clipId: String) -> Unit, onManual: () -> Unit) {
    val vm = appViewModel(key = "suggestions-$projectId") { SuggestionsViewModel(it, projectId) }
    val project by vm.project.collectAsStateWithLifecycle()
    val loaded by vm.loaded.collectAsStateWithLifecycle()
    var playingClip by remember { mutableStateOf<String?>(null) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.reload() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(project?.name ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        val p = project
        when {
            !loaded -> Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) { CircularProgressIndicator() }
            p == null -> Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) {
                Text(stringResource(R.string.error_generic))
            }
            else -> {
                val source = vm.sourceFile
                Column(Modifier.fillMaxSize().padding(padding)) {
                    // Botón FIJO (fuera de la lista): salta las sugerencias y abre el editor con el video completo.
                    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
                        Button(
                            onClick = onManual,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp).height(52.dp),
                        ) {
                            Icon(Icons.Filled.ContentCut, null, modifier = Modifier.size(20.dp))
                            Text(stringResource(R.string.manual_clip), modifier = Modifier.padding(start = 8.dp), fontWeight = FontWeight.Bold)
                        }
                    }
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(stringResource(R.string.suggested_clips), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
                                Text(
                                    pluralStringResource(R.plurals.clips_found, p.clips.size, p.clips.size) + " · " +
                                        stringResource(if (p.hasHeatmap) R.string.analysis_with_heatmap else R.string.analysis_text_only),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (p.fastMode) {
                                    Text(stringResource(R.string.fast_mode_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                                }
                                if (p.words.isEmpty()) {
                                    Text(stringResource(R.string.no_speech_detected), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                        items(p.clips, key = { it.id }) { clip ->
                            ClipCard(
                                index = p.clips.indexOf(clip) + 1,
                                clip = clip,
                                source = source,
                                playing = playingClip == clip.id,
                                onTogglePlay = { playingClip = if (playingClip == clip.id) null else clip.id },
                                onEdit = { onEdit(clip.id) },
                                onDelete = if (clip.manual) ({ vm.deleteManualClip(clip.id) }) else null,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ClipCard(
    index: Int,
    clip: ClipSuggestion,
    source: File?,
    playing: Boolean,
    onTogglePlay: () -> Unit,
    onEdit: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    val title = clip.title.ifBlank { stringResource(R.string.manual_clip_title) }
    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black)) {
                if (playing && source != null) {
                    ClipPreviewPlayer(source, clip.startMs, clip.endMs, Modifier.fillMaxSize())
                    IconButton(onClick = onTogglePlay, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).background(Color.Black.copy(alpha = 0.5f), CircleShape)) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.close), tint = Color.White)
                    }
                } else {
                    val frame by rememberVideoFrame(source, clip.startMs + 500)
                    BitmapOrPlaceholder(frame, Modifier.fillMaxSize().clickable(onClick = onTogglePlay))
                    Box(
                        Modifier.align(Alignment.Center).size(56.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)).clickable(onClick = onTogglePlay),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = stringResource(R.string.preview), tint = Color.White, modifier = Modifier.size(34.dp))
                    }
                    Text(
                        "${formatTime(clip.startMs)} – ${formatTime(clip.endMs)}  ·  ${formatTime(clip.durationMs)}",
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.align(Alignment.BottomStart).padding(8.dp)
                            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (!clip.manual) ScoreBadge(clip.viralScore)
                    if (clip.manual && onDelete != null) {
                        IconButton(onClick = onDelete) {
                            Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.delete))
                        }
                    }
                    Column(Modifier.weight(1f)) {
                        Text("#$index", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (!clip.manual) {
                            Text(stringResource(R.string.viral_score), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (clip.audioScore >= 60 || clip.motionScore >= 60 || clip.chatScore >= 60) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (clip.audioScore >= 60) SignalChip(R.string.signal_audio, Icons.AutoMirrored.Filled.VolumeUp)
                        if (clip.motionScore >= 60) SignalChip(R.string.signal_motion, Icons.Filled.Face)
                        if (clip.chatScore >= 60) SignalChip(R.string.signal_chat, Icons.Filled.Forum)
                    }
                }
                if (clip.heatScore >= 60) {
                    AssistChip(
                        onClick = {},
                        label = { Text(stringResource(R.string.youtube_peak, clip.heatScore)) },
                        leadingIcon = { Icon(Icons.Filled.Whatshot, null, tint = Color(0xFFFF6D00), modifier = Modifier.size(18.dp)) },
                    )
                }
                if (clip.preview.isNotBlank()) {
                    Text("“${clip.preview}…”", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                Button(onClick = onEdit, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                    Icon(Icons.Filled.ContentCut, null, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.edit_clip), modifier = Modifier.padding(start = 8.dp), fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun SignalChip(label: Int, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    AssistChip(
        onClick = {},
        label = { Text(stringResource(label)) },
        leadingIcon = { Icon(icon, null, modifier = Modifier.size(18.dp)) },
    )
}

/** Vista previa rápida del clip (sin entrar al editor): reproduce sólo el tramo sugerido en bucle. */
@Composable
private fun ClipPreviewPlayer(source: File, startMs: Long, endMs: Long, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val player = remember(source, startMs, endMs) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(
                MediaItem.Builder()
                    .setUri(Uri.fromFile(source))
                    .setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder().setStartPositionMs(startMs).setEndPositionMs(endMs).build(),
                    )
                    .build(),
            )
            repeatMode = Player.REPEAT_MODE_ONE
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    AndroidView(
        modifier = modifier,
        factory = { ctx -> PlayerView(ctx).apply { this.player = player; useController = false } },
        update = { it.player = player },
    )
}
