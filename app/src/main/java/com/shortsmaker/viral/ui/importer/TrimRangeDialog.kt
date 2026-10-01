package com.shortsmaker.viral.ui.importer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shortsmaker.viral.R
import com.shortsmaker.viral.data.formatTime
import com.shortsmaker.viral.domain.LongVideo
import com.shortsmaker.viral.domain.TimeSpan

/**
 * Recortador previo para videos largos: el usuario elige un fragmento de hasta 15 min. Al confirmar, el video se
 * recorta SIN recodificar (remux) y sólo ese fragmento se guarda y analiza.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrimRangeDialog(
    durationMs: Long,
    initial: TimeSpan?,
    onConfirm: (TimeSpan) -> Unit,
    onDismiss: () -> Unit,
) {
    var range by remember {
        mutableStateOf(initial ?: LongVideo.clampTrim(0, LongVideo.THRESHOLD_MS, false, durationMs))
    }

    fun change(start: Long, end: Long, movedStart: Boolean) {
        range = LongVideo.clampTrim(start, end, movedStart, durationMs)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.trim_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.trim_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(formatTime(range.startMs), fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.clip_duration, formatTime(range.endMs - range.startMs)), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    Text(formatTime(range.endMs), fontWeight = FontWeight.Bold)
                }
                RangeSlider(
                    value = range.startMs.toFloat()..range.endMs.toFloat(),
                    onValueChange = { r ->
                        val s = r.start.toLong()
                        change(s, r.endInclusive.toLong(), movedStart = s != range.startMs)
                    },
                    valueRange = 0f..durationMs.toFloat(),
                )
                // En videos de horas el deslizador es poco preciso: saltos de 1 min y 10 s.
                NudgeRow(R.string.trim_start) { d -> change(range.startMs + d, range.endMs, true) }
                NudgeRow(R.string.trim_end) { d -> change(range.startMs, range.endMs + d, false) }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(range) }) { Text(stringResource(R.string.trim_use_segment)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun NudgeRow(label: Int, onNudge: (Long) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(stringResource(label), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(-60_000L to "−1m", -10_000L to "−10s", 10_000L to "+10s", 60_000L to "+1m").forEach { (delta, text) ->
                Box(
                    Modifier.weight(1f).height(32.dp).clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { onNudge(delta) },
                    contentAlignment = Alignment.Center,
                ) { Text(text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
            }
        }
    }
}
