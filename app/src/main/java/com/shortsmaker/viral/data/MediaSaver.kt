package com.shortsmaker.viral.data

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException

enum class ShareTarget(val label: String, val packages: List<String>) {
    TIKTOK("TikTok", listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill")),
    INSTAGRAM("Instagram", listOf("com.instagram.android")),
    FACEBOOK("Facebook", listOf("com.facebook.katana")),
}

object MediaSaver {
    private const val FOLDER = "ShortsMaker"

    /** Guarda el MP4 en la galería (Movies/ShortsMaker). En Android 10+ no requiere permisos. */
    fun saveToGallery(context: Context, file: File, displayName: String): Uri {
        val name = if (displayName.endsWith(".mp4")) displayName else "$displayName.mp4"
        val resolver = context.contentResolver
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/$FOLDER")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: throw IOException("insert failed")
            try {
                resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } } ?: throw IOException("no stream")
                resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
            uri
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), FOLDER).apply { mkdirs() }
            val target = File(dir, name)
            file.copyTo(target, overwrite = true)
            MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf("video/mp4"), null)
            Uri.fromFile(target)
        }
    }

    fun contentUri(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    /** Intent de compartir con el video ya cargado. `packageName` limita el destino a una app concreta. */
    fun shareIntent(context: Context, file: File, packageName: String? = null): Intent {
        val uri = contentUri(context, file)
        return Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (packageName != null) setPackage(packageName)
        }
    }

    /** Devuelve el intent para abrir la red social directamente, o null si no está instalada. */
    fun intentFor(context: Context, file: File, target: ShareTarget): Intent? =
        target.packages.firstNotNullOfOrNull { pkg ->
            val intent = shareIntent(context, file, pkg)
            if (intent.resolveActivity(context.packageManager) != null) intent else null
        }
}
