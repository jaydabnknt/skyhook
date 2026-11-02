package com.jaydabnknt.skyhook.download

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.jaydabnknt.skyhook.R
import com.jaydabnknt.skyhook.SkyhookApp
import com.jaydabnknt.skyhook.media.formatBytes
import com.jaydabnknt.skyhook.ui.DownloadsActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Keeps the process alive while downloads/uploads run and shows their progress. */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wakeLock: PowerManager.WakeLock? = null
    private var started = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(this, NOTIF_ID, build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        if (!started) {
            started = true
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "skyhook:downloads")
                .apply { acquire(6 * 60 * 60 * 1000L) }
            scope.launch {
                while (true) {
                    delay(1000)
                    if (Downloads.activeCount == 0) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                        break
                    }
                    getSystemService(NotificationManager::class.java).notify(NOTIF_ID, build())
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun build(): Notification {
        val active = Downloads.jobs.filter { it.active }
        val first = active.firstOrNull()
        val title = when {
            active.size > 1 -> "${active.size} downloads running"
            first != null -> first.name
            else -> "Downloads"
        }
        val text = first?.let { j ->
            val where = if (j.dest.isCloud) "→ ${j.dest.label}" else ""
            val amount = if (j.segTotal > 0) "${j.segDone}/${j.segTotal} parts · ${formatBytes(j.bytes)}" else formatBytes(j.bytes)
            "$amount $where"
        } ?: ""
        val pct = first?.fraction
        return NotificationCompat.Builder(this, SkyhookApp.CHANNEL_DOWNLOADS)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, ((pct ?: 0f) * 100).toInt(), pct == null)
            .setContentIntent(openDownloads(this))
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    companion object {
        private const val NOTIF_ID = 1

        private fun openDownloads(ctx: Context) = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, DownloadsActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )

        fun notifyFinished(ctx: Context, job: DownloadJob) {
            val (title, text) = when (job.status) {
                Status.DONE -> "Saved: ${job.name}" to "→ ${job.location ?: job.dest.label}" + (job.note?.let { " · $it" } ?: "")
                Status.FAILED -> "Failed: ${job.name}" to (job.error ?: "Unknown error")
                else -> return
            }
            val n = NotificationCompat.Builder(ctx, SkyhookApp.CHANNEL_DOWNLOADS)
                .setSmallIcon(if (job.dest.isCloud) R.drawable.ic_cloud else R.drawable.ic_download)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(openDownloads(ctx))
                .build()
            runCatching { ctx.getSystemService(NotificationManager::class.java).notify(1000 + job.id, n) }
        }
    }
}
