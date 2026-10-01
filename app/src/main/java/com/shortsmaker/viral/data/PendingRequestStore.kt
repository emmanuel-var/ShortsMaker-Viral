package com.shortsmaker.viral.data

import android.content.Context
import android.net.Uri
import com.shortsmaker.viral.ImportRequest
import com.shortsmaker.viral.ProcessingMode
import com.shortsmaker.viral.domain.Language
import com.shortsmaker.viral.domain.MediaLinks
import kotlinx.serialization.Serializable
import java.io.File

/**
 * Guarda en disco la petición de importación para que el trabajo en segundo plano (WorkManager) pueda
 * reanudarse aunque Android reinicie el proceso.
 */
class PendingRequestStore(context: Context) {
    private val dir = File(context.filesDir, "pending").apply { mkdirs() }

    @Serializable
    private data class Stored(
        val uri: String,
        val displayName: String?,
        val language: String,
        val linkUrl: String?,
        val mode: String,
        val trimStartMs: Long?,
        val trimEndMs: Long?,
    )

    fun save(id: String, r: ImportRequest) {
        val s = Stored(r.sourceUri.toString(), r.displayName, r.language.code, r.link?.canonicalUrl, r.mode.name, r.trimStartMs, r.trimEndMs)
        File(dir, "$id.json").writeText(AppJson.encodeToString(Stored.serializer(), s))
    }

    fun load(id: String): ImportRequest? {
        val f = File(dir, "$id.json")
        if (!f.exists()) return null
        return try {
            val s = AppJson.decodeFromString(Stored.serializer(), f.readText())
            ImportRequest(
                sourceUri = Uri.parse(s.uri),
                displayName = s.displayName,
                language = Language.fromCode(s.language) ?: Language.ES,
                link = s.linkUrl?.let(MediaLinks::parse),
                mode = ProcessingMode.entries.firstOrNull { it.name == s.mode } ?: ProcessingMode.NORMAL,
                trimStartMs = s.trimStartMs,
                trimEndMs = s.trimEndMs,
            )
        } catch (_: Exception) {
            null
        }
    }

    fun delete(id: String) {
        File(dir, "$id.json").delete()
    }
}
