package com.shortsmaker.viral.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import com.shortsmaker.viral.MainActivity
import com.shortsmaker.viral.R
import com.shortsmaker.viral.data.PipelineProgress
import com.shortsmaker.viral.data.PipelineStep
import java.util.UUID

val PipelineStep.labelRes: Int
    get() = when (this) {
        PipelineStep.IMPORT -> R.string.step_import
        PipelineStep.LINK -> R.string.step_link
        PipelineStep.MODEL -> R.string.step_model
        PipelineStep.RADAR -> R.string.step_radar
        PipelineStep.TRANSCRIBE -> R.string.step_transcribe
        PipelineStep.MOTION -> R.string.step_motion
        PipelineStep.ANALYZE -> R.string.step_analyze
    }

/** Notificación persistente de progreso del Foreground Service y aviso de "clips listos". */
object AnalysisNotifications {
    const val CHANNEL_PROGRESS = "analysis_progress"
    const val CHANNEL_DONE = "analysis_done"
    const val EXTRA_OPEN_PROJECT = "open_project_id"

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_PROGRESS, context.getString(R.string.channel_progress), NotificationManager.IMPORTANCE_LOW).apply {
                description = context.getString(R.string.channel_progress_desc)
                setShowBadge(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_DONE, context.getString(R.string.channel_done), NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    fun notificationId(projectId: String) = projectId.hashCode() and 0x0FFFFFFF

    /** `ctx` debe ser un contexto ya localizado al idioma de la app. */
    fun foregroundInfo(ctx: Context, workId: UUID, projectId: String, progress: PipelineProgress?): ForegroundInfo {
        ensureChannels(ctx)
        val percent = ((progress?.overall ?: 0f) * 100).toInt().coerceIn(0, 100)
        val step = progress?.let { ctx.getString(it.current.labelRes) } ?: ctx.getString(R.string.processing_subtitle)
        val notification = NotificationCompat.Builder(ctx, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(ctx.getString(R.string.processing_title))
            .setContentText("$step · $percent%")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setProgress(100, percent, progress == null)
            .setContentIntent(openAppIntent(ctx, projectId, openProject = false))
            .addAction(0, ctx.getString(R.string.cancel), WorkManager.getInstance(ctx).createCancelPendingIntent(workId))
            .build()
        val id = notificationId(projectId)
        return when {
            // Android 15+: tipo específico para procesamiento de medios; 10-14: sincronización de datos.
            Build.VERSION.SDK_INT >= 35 -> ForegroundInfo(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> ForegroundInfo(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else -> ForegroundInfo(id, notification)
        }
    }

    fun notifyDone(ctx: Context, projectId: String, projectName: String) {
        ensureChannels(ctx)
        val n = NotificationCompat.Builder(ctx, CHANNEL_DONE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(ctx.getString(R.string.notif_done_title))
            .setContentText(ctx.getString(R.string.notif_done_text, projectName))
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(ctx, projectId, openProject = true))
            .build()
        try {
            ctx.getSystemService(NotificationManager::class.java).notify(notificationId(projectId) + 1, n)
        } catch (_: SecurityException) { /* sin permiso POST_NOTIFICATIONS: el usuario simplemente no verá el aviso */ }
    }

    private fun openAppIntent(ctx: Context, projectId: String, openProject: Boolean): PendingIntent {
        val intent = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (openProject) putExtra(EXTRA_OPEN_PROJECT, projectId)
        }
        return PendingIntent.getActivity(
            ctx, notificationId(projectId) + if (openProject) 2 else 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
