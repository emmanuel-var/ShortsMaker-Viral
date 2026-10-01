package com.shortsmaker.viral.ui.settings

import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shortsmaker.viral.BuildConfig
import com.shortsmaker.viral.R
import com.shortsmaker.viral.domain.ExportResolution
import com.shortsmaker.viral.domain.Language
import com.shortsmaker.viral.ui.common.container
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val container = context.container
    val settings by container.settings.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var modelsVersion by remember { mutableIntStateOf(0) }
    var showPrivacy by remember { mutableStateOf(false) }
    var showLicenses by remember { mutableStateOf(false) }
    var confirmDeleteProjects by remember { mutableStateOf(false) }
    val installed = remember(modelsVersion) { container.modelManager.installedLanguages() }
    val modelsSize = remember(modelsVersion) { container.modelManager.installedSizeBytes() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            SectionTitle(R.string.default_language)
            Text(
                stringResource(R.string.default_language_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Language.entries.forEach { lang ->
                RadioRow(lang.label, settings.defaultLanguage == lang) { container.settings.setDefaultLanguage(lang) }
            }

            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            SectionTitle(R.string.export_resolution)
            ExportResolution.entries.forEach { res ->
                RadioRow(res.label + " (${res.width}×${res.height})", settings.resolution == res) { container.settings.setResolution(res) }
            }

            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            SectionTitle(R.string.voice_models)
            Text(
                stringResource(R.string.voice_models_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            if (installed.isEmpty()) {
                Text(stringResource(R.string.no_models_installed), modifier = Modifier.padding(16.dp))
            } else {
                installed.forEach { lang ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(lang.label, Modifier.weight(1f))
                        IconButton(onClick = { container.modelManager.delete(lang); modelsVersion++ }) {
                            Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.delete))
                        }
                    }
                }
                Text(
                    stringResource(R.string.models_size, Formatter.formatShortFileSize(context, modelsSize)),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }

            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            SectionTitle(R.string.storage)
            OutlinedButton(onClick = { confirmDeleteProjects = true }, modifier = Modifier.padding(horizontal = 16.dp)) {
                Text(stringResource(R.string.delete_all_projects))
            }

            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            SectionTitle(R.string.about)
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.version_format, BuildConfig.VERSION_NAME))
                TextButton(onClick = { showPrivacy = true }) { Text(stringResource(R.string.privacy_policy)) }
                TextButton(onClick = { showLicenses = true }) { Text(stringResource(R.string.open_source_licenses)) }
                Text(
                    stringResource(R.string.affiliation_disclaimer),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (showPrivacy) InfoDialog(R.string.privacy_policy, R.string.privacy_policy_body) { showPrivacy = false }
    if (showLicenses) InfoDialog(R.string.open_source_licenses, R.string.licenses_body) { showLicenses = false }
    if (confirmDeleteProjects) {
        AlertDialog(
            onDismissRequest = { confirmDeleteProjects = false },
            title = { Text(stringResource(R.string.delete_all_projects)) },
            text = { Text(stringResource(R.string.delete_all_projects_message)) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { container.projects.deleteAll() }
                    confirmDeleteProjects = false
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteProjects = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun SectionTitle(res: Int) {
    Text(
        stringResource(res),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun RadioRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun InfoDialog(title: Int, body: Int, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) { Text(stringResource(body)) } },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}
