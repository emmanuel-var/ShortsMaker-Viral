package com.shortsmaker.viral.data

import android.content.Context
import com.shortsmaker.viral.domain.AudioEnergyAccumulator
import com.shortsmaker.viral.domain.AudioEnergyTrack
import com.shortsmaker.viral.domain.Project
import com.shortsmaker.viral.domain.ProjectSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

val AppJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/**
 * Proyectos guardados 100 % en el dispositivo: `files/projects/<id>/{project.json, summary.json, source.mp4, thumb.jpg}`.
 */
class ProjectRepository(context: Context) {
    private val root = File(context.filesDir, "projects").apply { mkdirs() }
    private val mutex = Mutex()
    private var cleaned = false

    private val _summaries = MutableStateFlow<List<ProjectSummary>>(emptyList())
    val summaries: StateFlow<List<ProjectSummary>> = _summaries.asStateFlow()

    fun dir(id: String) = File(root, id)
    fun sourceFile(project: Project) = File(dir(project.id), project.sourceFile)
    fun thumbnailFile(id: String, name: String?) = name?.let { File(dir(id), it) }

    fun createDir(id: String): File = dir(id).apply { mkdirs() }

    /** Audio Radar: energía por trozos de 100 ms (1 byte cada uno) para reutilizarla en el editor y el render. */
    suspend fun saveEnergy(id: String, track: AudioEnergyTrack) = withContext(Dispatchers.IO) {
        File(createDir(id), ENERGY_FILE).writeBytes(track.toBytes())
    }

    suspend fun loadEnergy(id: String): AudioEnergyTrack? = withContext(Dispatchers.IO) {
        val f = File(dir(id), ENERGY_FILE)
        if (f.exists()) AudioEnergyTrack(AudioEnergyAccumulator.DEFAULT_HOP_MS, f.readBytes()) else null
    }

    suspend fun refresh() = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!cleaned) {
                // Carpetas huérfanas de procesos interrumpidos (sin project.json).
                root.listFiles()?.filter { it.isDirectory && !File(it, PROJECT_FILE).exists() }?.forEach { it.deleteRecursively() }
                cleaned = true
            }
            val list = root.listFiles().orEmpty().mapNotNull { d ->
                val f = File(d, SUMMARY_FILE)
                if (!f.exists()) return@mapNotNull null
                try { AppJson.decodeFromString<ProjectSummary>(f.readText()) } catch (_: Exception) { null }
            }.sortedByDescending { it.updatedAt }
            _summaries.value = list
        }
    }

    suspend fun load(id: String): Project? = withContext(Dispatchers.IO) {
        val f = File(dir(id), PROJECT_FILE)
        if (!f.exists()) return@withContext null
        try { AppJson.decodeFromString<Project>(f.readText()) } catch (_: Exception) { null }
    }

    suspend fun save(project: Project) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val d = createDir(project.id)
            // Escribe el resumen primero: si el proceso muere entre ambos, no queda un proyecto "a medias" visible.
            writeAtomic(File(d, PROJECT_FILE), AppJson.encodeToString(Project.serializer(), project))
            writeAtomic(File(d, SUMMARY_FILE), AppJson.encodeToString(ProjectSummary.serializer(), project.toSummary()))
            val current = _summaries.value.filterNot { it.id == project.id } + project.toSummary()
            _summaries.value = current.sortedByDescending { it.updatedAt }
        }
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            dir(id).deleteRecursively()
            _summaries.value = _summaries.value.filterNot { it.id == id }
        }
    }

    suspend fun deleteAll() = withContext(Dispatchers.IO) {
        mutex.withLock {
            root.listFiles()?.forEach { it.deleteRecursively() }
            _summaries.value = emptyList()
        }
    }

    private fun writeAtomic(target: File, text: String) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) {
            target.delete()
            check(tmp.renameTo(target)) { "No se pudo escribir ${target.name}" }
        }
    }

    private companion object {
        const val PROJECT_FILE = "project.json"
        const val SUMMARY_FILE = "summary.json"
        const val ENERGY_FILE = "energy.bin"
    }
}
