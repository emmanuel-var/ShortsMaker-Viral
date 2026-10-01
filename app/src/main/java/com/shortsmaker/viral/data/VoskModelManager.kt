package com.shortsmaker.viral.data

import android.content.Context
import com.shortsmaker.viral.R
import com.shortsmaker.viral.domain.Language
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

/** Descarga (una sola vez) y gestiona los modelos de voz offline de Vosk. */
class VoskModelManager(private val context: Context, private val http: OkHttpClient) {
    private val root = File(context.filesDir, "vosk")

    fun modelDir(lang: Language) = File(root, lang.voskModel)

    fun isInstalled(lang: Language) = File(modelDir(lang), COMPLETE_MARKER).exists()

    fun installedLanguages(): List<Language> = Language.entries.filter(::isInstalled)

    fun installedSizeBytes(): Long = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun delete(lang: Language) {
        modelDir(lang).deleteRecursively()
    }

    fun deleteAll() {
        root.deleteRecursively()
    }

    /** Garantiza que el modelo esté instalado; `onProgress` recibe 0..1. */
    suspend fun ensureInstalled(lang: Language, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = modelDir(lang)
        if (isInstalled(lang)) return@withContext dir
        root.mkdirs()
        dir.deleteRecursively()
        val zip = File(context.cacheDir, "${lang.voskModel}.zip")
        try {
            download(lang, zip) { onProgress(it * 0.85f) }
            unzip(zip, root) { onProgress(0.85f + it * 0.15f) }
            if (!dir.isDirectory) throw AppException(R.string.error_model_corrupt)
            File(dir, COMPLETE_MARKER).createNewFile()
            onProgress(1f)
            dir
        } catch (e: AppException) {
            dir.deleteRecursively()
            throw e
        } catch (e: IOException) {
            dir.deleteRecursively()
            throw AppException(R.string.error_model_download, cause = e)
        } finally {
            zip.delete()
        }
    }

    private suspend fun download(lang: Language, target: File, onProgress: (Float) -> Unit) {
        val request = Request.Builder().url(lang.modelUrl).build()
        http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val body = resp.body ?: throw IOException("Empty body")
            val total = body.contentLength()
            if (total > 0 && context.cacheDir.usableSpace < total * 2) throw AppException(R.string.error_no_space)
            body.byteStream().use { input ->
                target.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var read = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        currentCoroutineContext().ensureActive()
                        output.write(buf, 0, n)
                        read += n
                        if (total > 0) onProgress(read.toFloat() / total)
                    }
                }
            }
        }
    }

    private suspend fun unzip(zip: File, destRoot: File, onProgress: (Float) -> Unit) {
        val rootPath = destRoot.canonicalPath + File.separator
        ZipFile(zip).use { zf ->
            val entries = zf.entries().toList()
            entries.forEachIndexed { i, entry ->
                currentCoroutineContext().ensureActive()
                val out = File(destRoot, entry.name)
                // Protección contra "zip slip".
                if (!out.canonicalPath.startsWith(rootPath)) throw AppException(R.string.error_model_corrupt)
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    zf.getInputStream(entry).use { input -> out.outputStream().use { input.copyTo(it) } }
                }
                onProgress((i + 1f) / entries.size)
            }
        }
    }

    private companion object {
        const val COMPLETE_MARKER = ".complete"
    }
}
