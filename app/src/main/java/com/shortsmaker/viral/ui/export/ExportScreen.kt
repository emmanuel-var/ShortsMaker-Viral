package com.shortsmaker.viral.ui.export

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.shortsmaker.viral.AppContainer
import com.shortsmaker.viral.R
import com.shortsmaker.viral.data.AppException
import com.shortsmaker.viral.data.MediaSaver
import com.shortsmaker.viral.data.ShareTarget
import com.shortsmaker.viral.domain.ClipEdit
import com.shortsmaker.viral.domain.FaceTrack
import com.shortsmaker.viral.domain.SubtitleTemplates
import com.shortsmaker.viral.ui.common.KeepScreenOn
import com.shortsmaker.viral.ui.common.appViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

sealed interface ExportState {
    data class Rendering(val progress: Float) : ExportState
    data class Done(val file: File, val displayName: String) : ExportState
    data class Failed(val error: AppException) : ExportState
}

class ExportViewModel(
    private val container: AppContainer,
    private val projectId: String,
    private val clipId: String,
) : ViewModel() {
    private val _state = MutableStateFlow<ExportState>(ExportState.Rendering(0f))
    val state: StateFlow<ExportState> = _state.asStateFlow()
    private var job: Job? = null

    init {
        start()
    }

    fun start() {
        job?.cancel()
        _state.value = ExportState.Rendering(0f)
        job = viewModelScope.launch {
            try {
                val project = container.projects.load(projectId) ?: throw AppException(R.string.error_generic)
                val clip = project.clips.firstOrNull { it.id == clipId } ?: throw AppException(R.string.error_generic)
                val edit = project.edits[clipId] ?: ClipEdit(clipId, clip.startMs, clip.endMs, SubtitleTemplates.all.first())
                val track = if (edit.framing.autoTrack) project.faceTracks[clipId]?.let(::FaceTrack) else null
                val resolution = container.settings.settings.value.resolution

                val dir = File(container.app.cacheDir, "exports").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() } // sólo se conserva el último render
                val out = File(dir, "ShortsMaker_${System.currentTimeMillis()}.mp4")

                container.exporter.export(
                    source = container.projects.sourceFile(project),
                    srcWidth = project.width,
                    srcHeight = project.height,
                    edit = edit,
                    cues = project.cues,
                    faceTrack = track,
                    resolution = resolution,
                    output = out,
                ) { _state.value = ExportState.Rendering(it) }

                val slug = project.name.replace(Regex("[^A-Za-z0-9]+"), "_").trim('_').take(30).ifEmpty { "clip" }
                _state.value = ExportState.Done(out, "ShortsMaker_${slug}_${System.currentTimeMillis() / 1000}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: AppException) {
                _state.value = ExportState.Failed(e)
            } catch (e: Exception) {
                _state.value = ExportState.Failed(AppException(R.string.error_export, cause = e))
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }

    override fun onCleared() {
        job?.cancel()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportScreen(projectId: String, clipId: String, onBack: () -> Unit, onHome: () -> Unit) {
    val vm = appViewModel(key = "export-$projectId-$clipId") { ExportViewModel(it, projectId, clipId) }
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var saved by remember { mutableStateOf(false) }

    if (state is ExportState.Rendering) KeepScreenOn()

    fun toast(res: Int, vararg args: Any) {
        scope.launch { snackbar.showSnackbar(context.getString(res, *args)) }
    }

    fun save(done: ExportState.Done) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) { MediaSaver.saveToGallery(context, done.file, done.displayName) }
                saved = true
                toast(R.string.saved_to_gallery)
            } catch (e: Exception) {
                toast(R.string.error_save)
            }
        }
    }

    // En Android 9 o inferior se necesita permiso de escritura para guardar en la galería.
    var pendingSave by remember { mutableStateOf<ExportState.Done?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val target = pendingSave
        pendingSave = null
        if (granted && target != null) save(target) else toast(R.string.error_save)
    }
    fun requestSave(done: ExportState.Done) {
        val needsPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        if (needsPermission) {
            pendingSave = done
            permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else save(done)
    }

    fun share(done: ExportState.Done, target: ShareTarget?) {
        val intent = if (target == null) null else MediaSaver.intentFor(context, done.file, target)
        try {
            if (intent != null) {
                context.startActivity(intent)
            } else {
                if (target != null) toast(R.string.app_not_installed, target.label)
                context.startActivity(Intent.createChooser(MediaSaver.shareIntent(context, done.file), context.getString(R.string.share_with)))
            }
        } catch (_: ActivityNotFoundException) {
            toast(R.string.app_not_installed, target?.label ?: "")
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.export_title)) },
                navigationIcon = {
                    IconButton(onClick = { vm.cancel(); onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (val s = state) {
                is ExportState.Rendering -> RenderingContent(s.progress, onCancel = { vm.cancel(); onBack() })
                is ExportState.Failed -> Column(
                    Modifier.fillMaxSize().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(Icons.Filled.Warning, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(56.dp))
                    Spacer(Modifier.height(12.dp))
                    Text(s.error.localized(context), textAlign = TextAlign.Center)
                    Spacer(Modifier.height(20.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = onBack) { Text(stringResource(R.string.back)) }
                        Button(onClick = vm::start) { Text(stringResource(R.string.retry)) }
                    }
                }
                is ExportState.Done -> DoneContent(
                    done = s,
                    saved = saved,
                    onSave = { requestSave(s) },
                    onShare = { target -> share(s, target) },
                    onHome = onHome,
                )
            }
        }
    }
}

@Composable
private fun RenderingContent(progress: Float, onCancel: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(contentAlignment = Alignment.Center) {
            CircularProgressIndicator(progress = { progress.coerceAtLeast(0.02f) }, modifier = Modifier.size(120.dp), strokeWidth = 8.dp)
            Text("${(progress * 100).toInt()}%", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
        }
        Spacer(Modifier.height(24.dp))
        Text(stringResource(R.string.rendering_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(
            stringResource(R.string.rendering_subtitle),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp),
        )
        Spacer(Modifier.height(24.dp))
        OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
    }
}

@Composable
private fun DoneContent(
    done: ExportState.Done,
    saved: Boolean,
    onSave: () -> Unit,
    onShare: (ShareTarget?) -> Unit,
    onHome: () -> Unit,
) {
    val context = LocalContext.current
    val player = remember(done.file) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.fromFile(done.file)))
            prepare()
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(stringResource(R.string.export_ready), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
        AndroidView(
            factory = { ctx -> PlayerView(ctx).apply { this.player = player; useController = true } },
            update = { it.player = player },
            modifier = Modifier.heightIn(max = 380.dp).aspectRatio(9f / 16f).clip(RoundedCornerShape(16.dp)),
        )
        Button(onClick = onSave, modifier = Modifier.fillMaxWidth().height(56.dp), enabled = !saved) {
            Icon(Icons.Filled.Save, null, modifier = Modifier.size(20.dp))
            Text(
                stringResource(if (saved) R.string.saved else R.string.save_to_device),
                modifier = Modifier.padding(start = 8.dp),
                fontWeight = FontWeight.Bold,
            )
        }
        Text(stringResource(R.string.publish_on), style = MaterialTheme.typography.titleSmall, modifier = Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ShareTarget.entries.forEach { target ->
                FilledTonalButton(onClick = { onShare(target) }, modifier = Modifier.weight(1f)) { Text(target.label, maxLines = 1) }
            }
        }
        OutlinedButton(onClick = { onShare(null) }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Share, null, modifier = Modifier.size(18.dp))
            Text(stringResource(R.string.more_apps), modifier = Modifier.padding(start = 8.dp))
        }
        TextButton(onClick = onHome) { Text(stringResource(R.string.back_to_home)) }
        Text(
            stringResource(R.string.export_footer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
