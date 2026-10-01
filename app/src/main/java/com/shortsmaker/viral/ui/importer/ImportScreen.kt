package com.shortsmaker.viral.ui.importer

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.shortsmaker.viral.AppContainer
import com.shortsmaker.viral.ImportRequest
import com.shortsmaker.viral.R
import com.shortsmaker.viral.data.MediaUtils
import com.shortsmaker.viral.data.VideoInfo
import com.shortsmaker.viral.data.formatTime
import com.shortsmaker.viral.domain.Language
import com.shortsmaker.viral.domain.YouTubeUrl
import com.shortsmaker.viral.ui.common.appViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

enum class ImportMode { YOUTUBE, GALLERY }

data class ImportUiState(
    val mode: ImportMode = ImportMode.YOUTUBE,
    val youtubeUrl: String = "",
    val videoUri: Uri? = null,
    val videoName: String? = null,
    val videoInfo: VideoInfo? = null,
    val readError: Boolean = false,
    val language: Language = Language.ES,
    val ownership: Boolean = false,
) {
    val videoId: String? get() = YouTubeUrl.extractVideoId(youtubeUrl)
    val urlInvalid: Boolean get() = youtubeUrl.isNotBlank() && videoId == null
    val canContinue: Boolean
        get() = videoUri != null && videoInfo != null && ownership &&
            (mode == ImportMode.GALLERY || videoId != null)
}

class ImportViewModel(private val container: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(ImportUiState(language = container.settings.settings.value.videoLanguage))
    val state: StateFlow<ImportUiState> = _state.asStateFlow()

    fun setMode(mode: ImportMode) = _state.update { it.copy(mode = mode) }
    fun setUrl(url: String) = _state.update { it.copy(youtubeUrl = url.trim()) }
    fun setLanguage(language: Language) = _state.update { it.copy(language = language) }
    fun setOwnership(value: Boolean) = _state.update { it.copy(ownership = value) }

    fun onVideoPicked(uri: Uri?) {
        if (uri == null) return
        viewModelScope.launch {
            val (info, name) = withContext(Dispatchers.IO) {
                MediaUtils.probe(container.app, uri) to MediaUtils.displayName(container.app, uri)
            }
            _state.update { it.copy(videoUri = if (info != null) uri else null, videoName = name, videoInfo = info, readError = info == null) }
        }
    }

    /** Prepara la petición y devuelve el id del proyecto que se va a crear. */
    fun start(): String? {
        val s = _state.value
        if (!s.canContinue) return null
        container.settings.setVideoLanguage(s.language) // se recuerda como idioma por defecto del próximo video
        val id = UUID.randomUUID().toString()
        container.pendingImports[id] = ImportRequest(
            sourceUri = s.videoUri!!,
            displayName = s.videoName,
            language = s.language,
            youtubeUrl = if (s.mode == ImportMode.YOUTUBE) s.youtubeUrl else null,
        )
        return id
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportScreen(onBack: () -> Unit, onStart: (String) -> Unit) {
    val vm = appViewModel { ImportViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { vm.onVideoPicked(it) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.import_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ModeCard(
                    title = R.string.paste_youtube_link,
                    icon = { Icon(Icons.Filled.Link, contentDescription = null, modifier = Modifier.size(32.dp)) },
                    selected = state.mode == ImportMode.YOUTUBE,
                    modifier = Modifier.weight(1f),
                ) { vm.setMode(ImportMode.YOUTUBE) }
                ModeCard(
                    title = R.string.upload_from_gallery,
                    icon = { Icon(Icons.Filled.VideoLibrary, contentDescription = null, modifier = Modifier.size(32.dp)) },
                    selected = state.mode == ImportMode.GALLERY,
                    modifier = Modifier.weight(1f),
                ) { vm.setMode(ImportMode.GALLERY) }
            }

            if (state.mode == ImportMode.YOUTUBE) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = state.youtubeUrl,
                        onValueChange = vm::setUrl,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.youtube_link)) },
                        placeholder = { Text("https://youtu.be/…") },
                        singleLine = true,
                        isError = state.urlInvalid,
                        supportingText = {
                            when {
                                state.urlInvalid -> Text(stringResource(R.string.invalid_youtube_link))
                                state.videoId != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                                    Text(" " + stringResource(R.string.valid_youtube_link))
                                }
                            }
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    )
                    TextButton(onClick = { clipboard.getText()?.text?.let(vm::setUrl) }) {
                        Text(stringResource(R.string.paste))
                    }
                    Text(
                        stringResource(R.string.youtube_policy_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Archivo de video (obligatorio en ambos modos).
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(if (state.mode == ImportMode.YOUTUBE) R.string.select_original_file else R.string.select_video),
                        fontWeight = FontWeight.SemiBold,
                    )
                    OutlinedButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) }) {
                        Icon(Icons.Filled.VideoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(stringResource(if (state.videoUri == null) R.string.choose_video else R.string.change_video), modifier = Modifier.padding(start = 8.dp))
                    }
                    state.videoInfo?.let { info ->
                        Text(
                            "${state.videoName ?: ""}\n${formatTime(info.durationMs)} · ${info.width}×${info.height}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (state.readError) {
                        Text(stringResource(R.string.error_unreadable_video), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            // Idioma del video.
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.video_language), fontWeight = FontWeight.SemiBold)
                Text(
                    stringResource(R.string.video_language_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Language.entries.forEach { lang ->
                        FilterChip(selected = state.language == lang, onClick = { vm.setLanguage(lang) }, label = { Text(lang.label) })
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth().clickable { vm.setOwnership(!state.ownership) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = state.ownership, onCheckedChange = vm::setOwnership)
                Text(stringResource(R.string.ownership_confirm), style = MaterialTheme.typography.bodySmall)
            }

            Button(
                onClick = { vm.start()?.let(onStart) },
                enabled = state.canContinue,
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) { Text(stringResource(R.string.analyze_video), fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
private fun ModeCard(title: Int, icon: @Composable () -> Unit, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(2.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(Modifier.padding(16.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            icon()
            Text(stringResource(title), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
