@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.shortsmaker.viral.ui.home

import android.text.format.DateFormat
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shortsmaker.viral.data.labelRes
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import androidx.lifecycle.viewModelScope
import com.shortsmaker.viral.AppContainer
import com.shortsmaker.viral.R
import com.shortsmaker.viral.data.formatTime
import com.shortsmaker.viral.domain.ProjectSummary
import com.shortsmaker.viral.ui.common.BitmapOrPlaceholder
import com.shortsmaker.viral.ui.common.appViewModel
import com.shortsmaker.viral.ui.common.rememberFileBitmap
import com.shortsmaker.viral.ui.theme.BrandGradient
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.Date

/** Análisis en curso , para el aviso de la pantalla de inicio. */
data class ActiveAnalysis(val projectId: String, val percent: Int, val stepRes: Int?)

class HomeViewModel(private val container: AppContainer) : ViewModel() {
    val projects: StateFlow<List<ProjectSummary>> = container.projects.summaries

    val active: StateFlow<List<ActiveAnalysis>> = container.analysis.states
        .map { map ->
            map.filter { it.value.running }.map { (id, st) ->
                ActiveAnalysis(id, ((st.progress?.overall ?: 0f) * 100).toInt(), st.progress?.current?.labelRes)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun reload() {
        viewModelScope.launch { container.projects.refresh() }
    }

    init {
        viewModelScope.launch { container.projects.refresh() }
    }

    fun thumbnail(summary: ProjectSummary) = container.projects.thumbnailFile(summary.id, summary.thumbnailFile)

    fun delete(id: String) {
        viewModelScope.launch { container.projects.delete(id) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(onCreate: () -> Unit, onOpenProject: (String) -> Unit, onOpenProcessing: (String) -> Unit, onSettings: () -> Unit) {
    val vm = appViewModel { HomeViewModel(it) }
    val projects by vm.projects.collectAsStateWithLifecycle()
    val active by vm.active.collectAsStateWithLifecycle()
    // Al volver (p. ej. desde la notificación) se refresca la lista: un análisis en segundo plano pudo terminar.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.reload() }
    var query by rememberSaveable { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<ProjectSummary?>(null) }

    val filtered = remember(projects, query) {
        if (query.isBlank()) projects else projects.filter { it.name.contains(query.trim(), ignoreCase = true) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Image(
                            painterResource(R.drawable.ic_logo),
                            contentDescription = null,
                            modifier = Modifier.size(34.dp).clip(RoundedCornerShape(9.dp)),
                        )
                        Text(stringResource(R.string.app_name), fontWeight = FontWeight.Black)
                    }
                },
                actions = {
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.settings))
                    }
                },
            )
        },
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(160.dp),
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) { CreateButton(onCreate) }
            items(active, key = { "active-" + it.projectId }, span = { GridItemSpan(maxLineSpan) }) { a ->
                Card(
                    onClick = { onOpenProcessing(a.projectId) },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    shape = RoundedCornerShape(20.dp),
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(stringResource(R.string.analysis_running, a.percent), fontWeight = FontWeight.Bold)
                        a.stepRes?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall) }
                        LinearProgressIndicator(progress = { a.percent / 100f }, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.search_projects)) },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) {
                                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.clear))
                            }
                        }
                    },
                    shape = RoundedCornerShape(28.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                )
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    stringResource(R.string.project_history),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (filtered.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    EmptyState(isSearch = query.isNotBlank())
                }
            }
            items(filtered, key = { it.id }) { summary ->
                ProjectCard(
                    summary = summary,
                    thumbnail = vm.thumbnail(summary),
                    onClick = { onOpenProject(summary.id) },
                    onDelete = { pendingDelete = summary },
                )
            }
        }
    }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.delete_project_title)) },
            text = { Text(stringResource(R.string.delete_project_message, target.name)) },
            confirmButton = {
                TextButton(onClick = { vm.delete(target.id); pendingDelete = null }) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun CreateButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(112.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(BrandGradient)
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(Icons.Filled.Whatshot, contentDescription = null, tint = Color.White, modifier = Modifier.size(48.dp))
            Column {
                Text(
                    stringResource(R.string.create_new_clip),
                    color = Color.White,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Black,
                    lineHeight = 28.sp,
                )
                Text(stringResource(R.string.create_new_clip_sub), color = Color.White.copy(alpha = 0.9f), fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun EmptyState(isSearch: Boolean) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Filled.Movie, contentDescription = null, modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.outline)
        Text(
            stringResource(if (isSearch) R.string.no_results else R.string.no_projects),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ProjectCard(summary: ProjectSummary, thumbnail: java.io.File?, onClick: () -> Unit, onDelete: () -> Unit) {
    val bitmap by rememberFileBitmap(thumbnail)
    val context = LocalContext.current
    val date = remember(summary.updatedAt) { DateFormat.getMediumDateFormat(context).format(Date(summary.updatedAt)) }
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        shape = RoundedCornerShape(20.dp),
    ) {
        Box {
            BitmapOrPlaceholder(bitmap, Modifier.fillMaxWidth().aspectRatio(16f / 10f))
            IconButton(
                onClick = onDelete,
                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(36.dp).background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(18.dp)),
            ) {
                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.delete), tint = Color.White, modifier = Modifier.size(18.dp))
            }
            Text(
                formatTime(summary.durationMs),
                color = Color.White,
                fontSize = 11.sp,
                modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        Column(Modifier.padding(12.dp)) {
            Text(summary.name, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                "$date · " + pluralStringResource(R.plurals.clip_count, summary.clipCount, summary.clipCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
