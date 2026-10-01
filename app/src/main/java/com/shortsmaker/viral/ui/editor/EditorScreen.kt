package com.shortsmaker.viral.ui.editor

import android.net.Uri
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.drawscope.scale
import com.shortsmaker.viral.data.VideoInfo
import com.shortsmaker.viral.domain.ComposeLayout
import com.shortsmaker.viral.domain.PopAnimation
import com.shortsmaker.viral.domain.ProgressBarMath
import com.shortsmaker.viral.domain.PunchIn
import com.shortsmaker.viral.domain.SubtitleMode
import android.view.TextureView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.runtime.withFrameNanos
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.shortsmaker.viral.R
import com.shortsmaker.viral.data.MediaUtils
import com.shortsmaker.viral.data.SubtitleRenderer
import com.shortsmaker.viral.data.formatTime
import com.shortsmaker.viral.domain.ClipEdit
import com.shortsmaker.viral.domain.ClipRange
import com.shortsmaker.viral.domain.FaceTrack
import com.shortsmaker.viral.domain.FontChoice
import com.shortsmaker.viral.domain.Framing
import com.shortsmaker.viral.domain.Language
import com.shortsmaker.viral.domain.FramingMath
import com.shortsmaker.viral.domain.Project
import com.shortsmaker.viral.domain.SubtitleCue
import com.shortsmaker.viral.domain.SubtitlePosition
import com.shortsmaker.viral.domain.SubtitleStyle
import com.shortsmaker.viral.domain.SubtitleTemplates
import com.shortsmaker.viral.ui.common.appViewModel
import com.shortsmaker.viral.ui.theme.BrandGradient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.min

private enum class EditorTool(val label: Int, val icon: ImageVector) {
    STYLES(R.string.tool_styles, Icons.Filled.Subtitles),
    FORMAT(R.string.tool_format, Icons.Filled.Palette),
    TEXT(R.string.tool_text, Icons.Filled.TextFields),
    FRAME(R.string.tool_frame, Icons.Filled.Crop),
    EXTRAS(R.string.tool_extras, Icons.Filled.AutoAwesome),
}

private val SWATCHES = listOf(
    0xFFFFFFFF, 0xFFFFD60A, 0xFF00E5FF, 0xFF39FF14, 0xFFFF2D95,
    0xFFFF8A00, 0xFFFF3B30, 0xFF2979FF, 0xFFB388FF, 0xFF000000,
).map { it.toInt() }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(projectId: String, clipId: String, onBack: () -> Unit, onExport: () -> Unit) {
    val vm = appViewModel(key = "editor-$projectId-$clipId") { EditorViewModel(it, projectId, clipId) }
    val state by vm.state.collectAsStateWithLifecycle()

    val project = state.project
    val edit = state.edit
    if (state.loading || project == null || edit == null) {
        Scaffold { padding ->
            Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) {
                if (state.missing) Text(stringResource(R.string.error_generic)) else CircularProgressIndicator()
            }
        }
        return
    }

    val source = vm.sourceFile!!
    val context = LocalContext.current
    val renderer = remember { SubtitleRenderer() }
    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.fromFile(source)))
            repeatMode = Player.REPEAT_MODE_OFF
            prepare()
            seekTo(edit.startMs)
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { player.pause() }

    // --- Modo dividido: segundo reproductor (mudo) para la mitad inferior (gameplay original o B-roll).
    val brollFile = edit.brollFile?.let { File(vm.projectDir, it) }
    val isSplit = edit.layout == ComposeLayout.SPLIT_GAMEPLAY ||
        (edit.layout == ComposeLayout.SPLIT_BROLL && brollFile != null && state.brollInfo != null)
    val bottomPlayer = remember { ExoPlayer.Builder(context).build().apply { volume = 0f } }
    DisposableEffect(bottomPlayer) { onDispose { bottomPlayer.release() } }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { bottomPlayer.pause() }

    var positionMs by remember { mutableLongStateOf(edit.startMs) }
    var isPlaying by remember { mutableStateOf(false) }
    val currentEdit by rememberUpdatedState(edit)
    val splitNow by rememberUpdatedState(isSplit)
    val brollDurationMs = state.brollInfo?.durationMs ?: 0L

    /** Posición que debe tener el reproductor inferior para ir sincronizado con el principal. */
    fun bottomTarget(mainMs: Long): Long =
        if (currentEdit.layout == ComposeLayout.SPLIT_BROLL && brollDurationMs > 0) (mainMs - currentEdit.startMs).coerceAtLeast(0) % brollDurationMs
        else mainMs

    LaunchedEffect(edit.layout, edit.brollFile, isSplit) {
        if (!isSplit) {
            bottomPlayer.pause()
            bottomPlayer.clearMediaItems()
            return@LaunchedEffect
        }
        val broll = edit.layout == ComposeLayout.SPLIT_BROLL
        bottomPlayer.setMediaItem(MediaItem.fromUri(Uri.fromFile(if (broll) brollFile!! else source)))
        bottomPlayer.repeatMode = if (broll) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF
        bottomPlayer.prepare()
        bottomPlayer.seekTo(bottomTarget(player.currentPosition))
        bottomPlayer.playWhenReady = player.isPlaying
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
                if (splitNow) bottomPlayer.playWhenReady = playing
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    LaunchedEffect(player) {
        var lastSync = 0L
        while (true) {
            withFrameNanos { }
            val e = currentEdit
            val p = player.currentPosition
            if (player.isPlaying && (p >= e.endMs || p < e.startMs - 300)) {
                player.seekTo(e.startMs)
                positionMs = e.startMs
                if (splitNow) bottomPlayer.seekTo(bottomTarget(e.startMs))
            } else {
                positionMs = p
            }
            // Re-sincroniza la mitad inferior cada ~250 ms si se desvía más de 300 ms (pausas, saltos, bucle).
            val now = SystemClock.uptimeMillis()
            if (splitNow && now - lastSync > 250) {
                lastSync = now
                val target = bottomTarget(p)
                if (kotlin.math.abs(bottomPlayer.currentPosition - target) > 300) bottomPlayer.seekTo(target)
            }
        }
    }

    fun togglePlay() {
        if (player.isPlaying) {
            player.pause()
        } else {
            if (player.currentPosition !in edit.startMs until edit.endMs) player.seekTo(edit.startMs)
            player.play()
        }
    }

    // Bloques de subtítulos (con su índice global) que caen dentro del clip.
    val visibleCues = remember(project.cues, edit.startMs, edit.endMs) {
        project.cues.withIndex().filter { it.value.words.isNotEmpty() && it.value.endMs > edit.startMs && it.value.startMs < edit.endMs }
    }

    // Punch-in (auto-zoom): picos de audio + palabras clave dentro del tramo.
    val clipWords = remember(visibleCues) { visibleCues.flatMap { it.value.words } }
    val punches = remember(edit.autoZoom, edit.startMs, edit.endMs, clipWords, state.audio) {
        if (edit.autoZoom) PunchIn.plan(edit.startMs, edit.endMs, state.audio, clipWords) else emptyList()
    }

    // Segundo video (B-roll) desde la galería, sin permisos (Photo Picker).
    val brollPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(vm::importBroll) }
    fun pickBroll() = brollPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))

    var tool by rememberSaveable { mutableStateOf(EditorTool.STYLES) }
    var editingCue by remember { mutableStateOf<Int?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.edit_clip_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    val detecting = state.faceStatus is FaceStatus.Running || state.transcribeStatus is TranscribeStatus.Running
                    if (detecting) CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                    Button(
                        onClick = { player.pause(); vm.saveAndThen(onExport) },
                        enabled = !detecting,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    ) {
                        Text(stringResource(R.string.export), fontWeight = FontWeight.Bold)
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                val h = min(maxHeight.value, maxWidth.value * 16f / 9f)
                VerticalPreview(
                    player = player,
                    bottomPlayer = bottomPlayer,
                    split = isSplit,
                    brollInfo = state.brollInfo,
                    punchZoom = PunchIn.zoomAt(punches, positionMs),
                    project = project,
                    edit = edit,
                    visibleCues = visibleCues,
                    faceTrack = state.faceTrack,
                    positionMs = positionMs,
                    isPlaying = isPlaying,
                    renderer = renderer,
                    onTogglePlay = ::togglePlay,
                    onEditCue = { player.pause(); editingCue = it },
                    modifier = Modifier.size(width = (h * 9f / 16f).dp, height = h.dp),
                )
            }

            when (val ts = state.transcribeStatus) {
                is TranscribeStatus.Running -> Text(
                    stringResource(R.string.transcribing_segment, (ts.progress * 100).toInt()),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                TranscribeStatus.TooLong -> Text(
                    stringResource(R.string.transcribe_too_long),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                TranscribeStatus.Failed -> Text(
                    stringResource(R.string.error_transcription),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                TranscribeStatus.Idle -> Unit
            }
            if (state.clip?.manual == true) {
                Text(
                    stringResource(R.string.manual_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            TrimTimeline(
                source = source,
                durationMs = project.durationMs,
                startMs = edit.startMs,
                endMs = edit.endMs,
                positionMs = positionMs,
                onChange = { s, e, movedStart ->
                    val (ns, ne) = ClipRange.clamp(s, e, movedStart, project.durationMs)
                    vm.setRange(ns, ne)
                    player.seekTo(if (movedStart) ns else max(ns, ne - 1500))
                },
            )

            Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth().height(196.dp)) {
                when (tool) {
                    EditorTool.STYLES -> StylePanel(edit.style, vm::setStyle)
                    EditorTool.FORMAT -> FormatPanel(edit.style, vm::setStyle)
                    EditorTool.TEXT -> TextPanel(
                        cues = visibleCues,
                        onSelect = { index, cue -> player.pause(); player.seekTo(max(cue.startMs, edit.startMs)); editingCue = index },
                    )
                    EditorTool.FRAME -> FramePanel(
                        framing = edit.framing,
                        face = state.faceStatus,
                        layout = edit.layout,
                        brollReady = brollFile != null && state.brollInfo != null,
                        brollImporting = state.brollImporting,
                        onChange = vm::setFraming,
                        onRetry = vm::retryFaceDetection,
                        onLayout = { l -> if (l == ComposeLayout.SPLIT_BROLL && (brollFile == null || state.brollInfo == null)) pickBroll() else vm.setLayout(l) },
                        onPickBroll = ::pickBroll,
                    )
                    EditorTool.EXTRAS -> ExtrasPanel(edit, vm::setAutoZoom, vm::setProgressBar, vm::setProgressColor, vm::setSfx, vm::setStyle)
                }
            }

            NavigationBar {
                EditorTool.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tool == t,
                        onClick = { tool = t },
                        icon = { Icon(t.icon, contentDescription = null) },
                        label = { Text(stringResource(t.label)) },
                    )
                }
            }
        }
    }

    editingCue?.let { index ->
        val cue = project.cues.getOrNull(index)
        if (cue == null) {
            editingCue = null
        } else {
            var text by remember(index) { mutableStateOf(cue.text) }
            AlertDialog(
                onDismissRequest = { editingCue = null },
                title = { Text(stringResource(R.string.edit_subtitle)) },
                text = {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        modifier = Modifier.fillMaxWidth(),
                        supportingText = { Text("${formatTime(cue.startMs)} – ${formatTime(cue.endMs)}") },
                    )
                },
                confirmButton = { TextButton(onClick = { vm.editCue(index, text); editingCue = null }) { Text(stringResource(R.string.save)) } },
                dismissButton = { TextButton(onClick = { editingCue = null }) { Text(stringResource(R.string.cancel)) } },
            )
        }
    }
}

// ------------------------------------------------------------------------------------------ vista previa 9:16

@Composable
private fun VerticalPreview(
    player: ExoPlayer,
    bottomPlayer: ExoPlayer,
    split: Boolean,
    brollInfo: VideoInfo?,
    punchZoom: Float,
    project: Project,
    edit: ClipEdit,
    visibleCues: List<IndexedValue<SubtitleCue>>,
    faceTrack: FaceTrack?,
    positionMs: Long,
    isPlaying: Boolean,
    renderer: SubtitleRenderer,
    onTogglePlay: () -> Unit,
    onEditCue: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val style = edit.effectiveStyle() // en modo dividido los subtítulos van en la costura central
    val spaced = remember(project.language) { Language.fromCode(project.language)?.spaced ?: true }
    BoxWithConstraints(modifier.clip(RoundedCornerShape(16.dp)).background(Color.Black)) {
        val boxW = constraints.maxWidth.toFloat()
        val boxH = constraints.maxHeight.toFloat()
        val srcAspect = project.width.toFloat() / project.height.toFloat()
        val areaH = if (split) boxH / 2f else boxH               // alto de la zona del rostro
        val aspect = if (split) FramingMath.SPLIT_ASPECT else FramingMath.TARGET_ASPECT
        val vh = areaH
        val vw = areaH * srcAspect
        val base = max(1f, boxW / vw) // videos más estrechos que la ventana se amplían para cubrirla
        // El mismo cálculo que usa el exportador: encuadre del rostro + auto-zoom (punch-in).
        val t = FramingMath.transform(
            project.width, project.height, edit.framing, faceTrack?.xAt(positionMs), faceTrack?.yAt(positionMs),
            aspect = aspect, extraZoom = punchZoom,
        )

        // --- Zona superior (o toda la pantalla): el video principal recortado alrededor del rostro.
        Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(with(density) { areaH.toDp() }).clipToBounds()) {
            AndroidView(
                factory = { ctx -> TextureView(ctx).also { player.setVideoTextureView(it) } },
                onRelease = { view -> try { player.clearVideoTextureView(view) } catch (_: Exception) { } },
                modifier = Modifier
                    .align(Alignment.Center)
                    .requiredSize(with(density) { vw.toDp() }, with(density) { vh.toDp() })
                    .graphicsLayer {
                        scaleX = base * t.scale
                        scaleY = base * t.scale
                        translationX = base * vw / 2f * t.tx
                        translationY = -base * vh / 2f * t.ty
                    },
            )
        }

        // --- Zona inferior: gameplay original o B-roll, rellenando (recorte centrado).
        if (split) {
            val bAspect = if (edit.layout == ComposeLayout.SPLIT_BROLL && brollInfo != null) brollInfo.width.toFloat() / brollInfo.height else srcAspect
            val bw = max(boxW, areaH * bAspect)
            val bh = bw / bAspect
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(with(density) { areaH.toDp() }).clipToBounds()) {
                AndroidView(
                    factory = { ctx -> TextureView(ctx).also { bottomPlayer.setVideoTextureView(it) } },
                    onRelease = { view -> try { bottomPlayer.clearVideoTextureView(view) } catch (_: Exception) { } },
                    modifier = Modifier.align(Alignment.Center).requiredSize(with(density) { bw.toDp() }, with(density) { bh.toDp() }),
                )
            }
        }

        // --- Subtítulo activo: mismo renderizador que la exportación, con animación pop-in.
        val active = visibleCues.firstOrNull { positionMs >= it.value.startMs && positionMs < it.value.endMs }
        val word = active?.let { renderer.activeWord(it.value, positionMs) } ?: -1
        val subtitle: ImageBitmap? = remember(active?.index, word, style, active?.value?.text, boxW, spaced) {
            active?.let { renderer.render(it.value, word, style, boxW.toInt(), spaced)?.asImageBitmap() }
        }
        val subtitleRect: Rect? = subtitle?.let {
            val top = (boxH * style.position.centerYFraction - it.height / 2f).coerceIn(0f, max(0f, boxH - it.height))
            Rect(Offset((boxW - it.width) / 2f, top), Size(it.width.toFloat(), it.height.toFloat()))
        }
        val sinceMs = if (active == null || word < 0) 0L
        else positionMs - if (style.mode == SubtitleMode.WORD_BY_WORD) active.value.words[word].startMs else active.value.startMs
        val pop = if (style.popIn && active != null) PopAnimation.scale(sinceMs) else 1f
        val popAlpha = if (style.popIn && active != null) PopAnimation.alpha(sinceMs) else 1f
        val currentRect by rememberUpdatedState(subtitleRect)
        val currentActive by rememberUpdatedState(active?.index)
        val progress = ProgressBarMath.progress(positionMs - edit.startMs, edit.endMs - edit.startMs)

        Canvas(
            Modifier.fillMaxSize().pointerInput(Unit) {
                detectTapGestures { offset ->
                    val r = currentRect
                    val idx = currentActive
                    if (r != null && idx != null && r.contains(offset)) onEditCue(idx) else onTogglePlay()
                }
            },
        ) {
            if (subtitle != null && subtitleRect != null) {
                scale(pop, pivot = subtitleRect.center) {
                    drawImage(subtitle, topLeft = subtitleRect.topLeft, alpha = popAlpha)
                }
            }
            // Barra de progreso fina en el borde superior (se quema igual en el render).
            if (edit.progressBar) {
                drawRect(Color(edit.progressColor), Offset.Zero, Size(size.width * progress, max(4f, size.width / 120f)))
            }
        }

        if (!isPlaying) {
            Box(
                Modifier.align(Alignment.Center).size(64.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = stringResource(R.string.play), tint = Color.White, modifier = Modifier.size(40.dp))
            }
        }
    }
}

// ------------------------------------------------------------------------------------------ línea de tiempo

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrimTimeline(
    source: File,
    durationMs: Long,
    startMs: Long,
    endMs: Long,
    positionMs: Long,
    onChange: (start: Long, end: Long, movedStart: Boolean) -> Unit,
) {
    val thumbs by produceState<List<ImageBitmap?>>(emptyList(), source.path, durationMs) {
        val times = (0 until THUMB_COUNT).map { i -> ((i + 0.5f) / THUMB_COUNT * durationMs).toLong() }
        value = withContext(Dispatchers.IO) { MediaUtils.framesAt(source, times, 160).map { it?.asImageBitmap() } }
    }
    Column(Modifier.padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatTime(startMs), style = MaterialTheme.typography.labelMedium)
            Text(
                stringResource(R.string.clip_duration, formatTime(endMs - startMs)),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(formatTime(endMs), style = MaterialTheme.typography.labelMedium)
        }
        Box(Modifier.fillMaxWidth().padding(horizontal = 10.dp).height(40.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
            Row(Modifier.fillMaxSize()) {
                for (i in 0 until THUMB_COUNT) {
                    val bmp = thumbs.getOrNull(i)
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        if (bmp != null) Image(bmp, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    }
                }
            }
            Canvas(Modifier.fillMaxSize()) {
                val w = size.width
                val s = startMs.toFloat() / durationMs * w
                val e = endMs.toFloat() / durationMs * w
                val dim = Color.Black.copy(alpha = 0.6f)
                drawRect(dim, Offset.Zero, Size(s, size.height))
                drawRect(dim, Offset(e, 0f), Size(w - e, size.height))
                val px = (positionMs.toFloat() / durationMs * w).coerceIn(0f, w)
                drawRect(Color.White, Offset(px - 1.5.dp.toPx(), 0f), Size(3.dp.toPx(), size.height))
            }
        }
        RangeSlider(
            value = startMs.toFloat()..endMs.toFloat(),
            onValueChange = { r ->
                val ns = r.start.toLong()
                val ne = r.endInclusive.toLong()
                onChange(ns, ne, ns != startMs)
            },
            valueRange = 0f..durationMs.toFloat(),
        )
        // Ajuste fino: en videos largos el deslizador es poco preciso, así que se añaden saltos de 1 s y 10 s.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            NudgeGroup(R.string.trim_start, Modifier.weight(1f)) { d -> onChange(startMs + d, endMs, true) }
            NudgeGroup(R.string.trim_end, Modifier.weight(1f)) { d -> onChange(startMs, endMs + d, false) }
        }
    }
}

@Composable
private fun NudgeGroup(label: Int, modifier: Modifier, onNudge: (Long) -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(stringResource(label), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(-10_000L to "−10s", -1_000L to "−1s", 1_000L to "+1s", 10_000L to "+10s").forEach { (delta, text) ->
                Box(
                    Modifier.weight(1f).height(28.dp).clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { onNudge(delta) },
                    contentAlignment = Alignment.Center,
                ) { Text(text, fontSize = 11.sp, fontWeight = FontWeight.SemiBold) }
            }
        }
    }
}

private const val THUMB_COUNT = 10

// ------------------------------------------------------------------------------------------ paneles

private fun FontChoice.toComposeFamily(): FontFamily = when (this) {
    FontChoice.IMPACT, FontChoice.BOLD, FontChoice.CONDENSED -> FontFamily.SansSerif
    FontChoice.SERIF -> FontFamily.Serif
    FontChoice.MONO -> FontFamily.Monospace
    FontChoice.HAND -> FontFamily.Cursive
}

private fun templateName(id: String): Int = when (id) {
    "hormozi" -> R.string.style_hormozi
    "karaoke" -> R.string.style_karaoke
    "classic" -> R.string.style_classic
    "box" -> R.string.style_box
    "neon" -> R.string.style_neon
    else -> R.string.style_minimal
}

@Composable
private fun StylePanel(current: SubtitleStyle, onSelect: (SubtitleStyle) -> Unit) {
    LazyRow(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(SubtitleTemplates.all, key = { it.templateId }) { t ->
            val selected = current.templateId == t.templateId
            Column(
                Modifier.clickable { onSelect(t) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    Modifier.size(width = 104.dp, height = 76.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF2B2B33))
                        .border(BorderStroke(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else Color.Gray), RoundedCornerShape(14.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    val sample = if (t.uppercase) "AB" else "Ab"
                    Row {
                        Text(sample, color = Color(t.textColor), fontFamily = t.font.toComposeFamily(), fontWeight = FontWeight.Black, fontSize = 26.sp)
                        Text("C", color = Color(t.highlightColor), fontFamily = t.font.toComposeFamily(), fontWeight = FontWeight.Black, fontSize = 26.sp)
                    }
                }
                Text(stringResource(templateName(t.templateId)), style = MaterialTheme.typography.labelMedium, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
            }
        }
    }
}

@Composable
private fun FormatPanel(style: SubtitleStyle, onChange: (SubtitleStyle) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.font), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.labelLarge)
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(FontChoice.entries, key = { it.name }) { f ->
                FilterChip(selected = style.font == f, onClick = { onChange(style.copy(font = f)) }, label = { Text(f.label, fontFamily = f.toComposeFamily()) })
            }
        }
        SwatchRow(R.string.text_color, style.textColor) { onChange(style.copy(textColor = it)) }
        SwatchRow(R.string.highlight_color, style.highlightColor) { onChange(style.copy(highlightColor = it)) }

        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.text_size), style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(96.dp))
            Slider(value = style.textSize, onValueChange = { onChange(style.copy(textSize = it)) }, valueRange = 40f..120f, modifier = Modifier.weight(1f))
        }

        Text(stringResource(R.string.position), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.labelLarge)
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SubtitlePosition.entries.forEach { pos ->
                FilterChip(
                    selected = style.position == pos,
                    onClick = { onChange(style.copy(position = pos)) },
                    label = { Text(stringResource(when (pos) { SubtitlePosition.TOP -> R.string.pos_top; SubtitlePosition.CENTER -> R.string.pos_center; SubtitlePosition.BOTTOM -> R.string.pos_bottom })) },
                )
            }
        }
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = style.uppercase, onClick = { onChange(style.copy(uppercase = !style.uppercase)) }, label = { Text(stringResource(R.string.uppercase)) })
            FilterChip(selected = style.emojis, onClick = { onChange(style.copy(emojis = !style.emojis)) }, label = { Text(stringResource(R.string.emojis)) })
        }
    }
}

@Composable
private fun SwatchRow(label: Int, selected: Int, onPick: (Int) -> Unit) {
    Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(label), style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(96.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(SWATCHES) { c ->
                Box(
                    Modifier.size(30.dp).clip(CircleShape).background(Color(c))
                        .border(if (c == selected) 3.dp else 1.dp, if (c == selected) MaterialTheme.colorScheme.primary else Color.Gray, CircleShape)
                        .clickable { onPick(c) },
                )
            }
        }
    }
}

@Composable
private fun TextPanel(cues: List<IndexedValue<SubtitleCue>>, onSelect: (Int, SubtitleCue) -> Unit) {
    if (cues.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(16.dp), Alignment.Center) {
            Text(stringResource(R.string.no_subtitles_in_clip), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
        itemsIndexed(cues, key = { _, c -> c.index }) { _, indexed ->
            Row(
                Modifier.fillMaxWidth().clickable { onSelect(indexed.index, indexed.value) }.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(formatTime(indexed.value.startMs), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                Text(indexed.value.text, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.edit_subtitle), modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun FramePanel(
    framing: Framing,
    face: FaceStatus,
    layout: ComposeLayout,
    brollReady: Boolean,
    brollImporting: Boolean,
    onChange: (Framing) -> Unit,
    onRetry: () -> Unit,
    onLayout: (ComposeLayout) -> Unit,
    onPickBroll: () -> Unit,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
        // --- Plantillas de composición.
        Text(stringResource(R.string.layout_title), style = MaterialTheme.typography.labelLarge)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = layout == ComposeLayout.FULL, onClick = { onLayout(ComposeLayout.FULL) }, label = { Text(stringResource(R.string.layout_full)) })
            FilterChip(selected = layout == ComposeLayout.SPLIT_GAMEPLAY, onClick = { onLayout(ComposeLayout.SPLIT_GAMEPLAY) }, label = { Text(stringResource(R.string.layout_split_gameplay)) })
            FilterChip(selected = layout == ComposeLayout.SPLIT_BROLL, onClick = { onLayout(ComposeLayout.SPLIT_BROLL) }, label = { Text(stringResource(R.string.layout_split_broll)) })
        }
        if (layout == ComposeLayout.SPLIT_BROLL || brollImporting) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = onPickBroll, enabled = !brollImporting) {
                    Text(stringResource(if (brollReady) R.string.broll_change else R.string.broll_pick))
                }
                if (brollImporting) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.padding(start = 12.dp).size(20.dp))
                    Text(stringResource(R.string.broll_importing), modifier = Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        // --- Seguimiento del rostro y encuadre manual.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.auto_face_tracking), fontWeight = FontWeight.SemiBold)
                val status = when (face) {
                    FaceStatus.Idle -> R.string.face_idle
                    is FaceStatus.Running -> R.string.face_running
                    is FaceStatus.Done -> R.string.face_done
                    FaceStatus.Unavailable -> R.string.face_unavailable
                    FaceStatus.TooLong -> R.string.face_too_long
                }
                Text(
                    if (face is FaceStatus.Running) stringResource(status, (face.progress * 100).toInt()) else stringResource(status),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (face == FaceStatus.Unavailable) TextButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
            Switch(checked = framing.autoTrack, onCheckedChange = { onChange(framing.copy(autoTrack = it)) })
        }
        LabeledSlider(R.string.zoom, framing.zoom, 1f..3f) { onChange(framing.copy(zoom = it)) }
        LabeledSlider(R.string.move_horizontal, framing.offsetX, -1f..1f) { onChange(framing.copy(offsetX = it)) }
        LabeledSlider(R.string.move_vertical, framing.offsetY, -1f..1f) { onChange(framing.copy(offsetY = it)) }
        OutlinedButton(onClick = { onChange(Framing(autoTrack = framing.autoTrack)) }) { Text(stringResource(R.string.reset_framing)) }
    }
}

/** Extras de retención: auto-zoom, barra de progreso, efectos de sonido, pop-in y resaltado automático. */
@Composable
private fun ExtrasPanel(
    edit: ClipEdit,
    onAutoZoom: (Boolean) -> Unit,
    onProgressBar: (Boolean) -> Unit,
    onProgressColor: (Int) -> Unit,
    onSfx: (Boolean) -> Unit,
    onStyle: (SubtitleStyle) -> Unit,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
        ToggleRow(R.string.extra_auto_zoom, R.string.extra_auto_zoom_desc, edit.autoZoom, onAutoZoom)
        ToggleRow(R.string.extra_sfx, R.string.extra_sfx_desc, edit.sfx, onSfx)
        ToggleRow(R.string.extra_pop_in, null, edit.style.popIn) { onStyle(edit.style.copy(popIn = it)) }
        ToggleRow(R.string.extra_keyword_colors, R.string.extra_keyword_colors_desc, edit.style.keywordColors) { onStyle(edit.style.copy(keywordColors = it)) }
        ToggleRow(R.string.extra_progress_bar, null, edit.progressBar, onProgressBar)
        if (edit.progressBar) SwatchRow(R.string.extra_progress_color, edit.progressColor, onProgressColor)
    }
}

@Composable
private fun ToggleRow(title: Int, desc: Int?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(title), fontWeight = FontWeight.SemiBold)
            if (desc != null) Text(stringResource(desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun LabeledSlider(label: Int, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(label), style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(96.dp))
        Slider(value = value.coerceIn(range), onValueChange = onChange, valueRange = range, modifier = Modifier.weight(1f))
    }
}
