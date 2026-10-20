package com.hddownloader.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/** Notification helper shared by the activity and the service. */
object DownloadNotifier {
    private const val CHANNEL_ID = "downloads"
    const val ONGOING_ID = 1
    private const val DONE_ID = 2

    private fun manager(ctx: Context) =
        ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_LOW
            )
            manager(ctx).createNotificationChannel(channel)
        }
    }

    private fun openAppIntent(ctx: Context): PendingIntent {
        val intent = Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            ctx, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    fun buildOngoing(ctx: Context, text: String, percent: Int?): Notification {
        ensureChannel(ctx)
        val b = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Facebook HD Downloader")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent(ctx))
        if (percent != null) b.setProgress(100, percent, false) else b.setProgress(0, 0, true)
        return b.build()
    }

    fun update(ctx: Context, text: String, percent: Int?) {
        manager(ctx).notify(ONGOING_ID, buildOngoing(ctx, text, percent))
    }

    fun finished(ctx: Context, text: String, success: Boolean) {
        ensureChannel(ctx)
        manager(ctx).cancel(ONGOING_ID)
        val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(
                if (success) android.R.drawable.stat_sys_download_done
                else android.R.drawable.stat_notify_error
            )
            .setContentTitle("Facebook HD Downloader")
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(ctx))
            .build()
        manager(ctx).notify(DONE_ID, n)
    }
}

/**
 * Foreground service whose only job is to keep the app process alive
 * while a download is running, so it continues in the background.
 */
class KeepAliveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = DownloadNotifier.buildOngoing(this, "Starting…", null)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                DownloadNotifier.ONGOING_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(DownloadNotifier.ONGOING_ID, n)
        }
        return START_NOT_STICKY
    }
}
