package io.github.firebirks.booruvault.download

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
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import io.github.firebirks.booruvault.BooruVaultApp
import io.github.firebirks.booruvault.MainActivity
import io.github.firebirks.booruvault.R
import io.github.firebirks.booruvault.settings.AppLanguage
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Keeps the process alive while downloads run. Its notification shows counts only: what is being
 * downloaded is nobody's business on a lock screen.
 */
class DownloadService : Service() {
    private val scope = MainScope()
    private var observer: Job? = null

    /** The notifications speak the language chosen in the app, like the screens. */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, PROGRESS_ID, progress(0, 0, true), type)
        if (observer == null) {
            val downloads = (application as BooruVaultApp).container.downloads
            observer = scope.launch {
                combine(downloads.tasks, downloads.batches, downloads.paused) { tasks, batches, paused -> Triple(tasks, batches, paused) }.collect { (tasks, batches, paused) ->
                    val counted = tasks.filter { it.status != DownloadStatus.CANCELLED }
                    val finished = counted.count { !it.active }
                    val collecting = batches.any { it.active }
                    if (counted.none { it.active } && !collecting) {
                        finish(counted.count { it.status == DownloadStatus.DONE }, counted.count { it.status == DownloadStatus.FAILED }, paused)
                    } else {
                        notify(PROGRESS_ID, progress(finished, counted.size, collecting))
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun finish(done: Int, failed: Int, paused: Int) {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        if (paused > 0) {
            // Locking paused the queue: it is sealed in the vault until the next unlock.
            val text = resources.getQuantityString(R.plurals.notification_paused, paused, paused)
            notify(DONE_ID, base().setContentTitle(getString(R.string.notification_paused_title)).setContentText(text).setAutoCancel(true).build())
        } else if (done + failed > 0) {
            val text = buildString {
                append(resources.getQuantityString(R.plurals.notification_saved, done, done))
                if (failed > 0) append(" · ").append(resources.getQuantityString(R.plurals.notification_failed, failed, failed))
            }
            notify(DONE_ID, base().setContentTitle(getString(R.string.notification_done_title)).setContentText(text).setAutoCancel(true).build())
        }
        observer?.cancel()
        observer = null
        stopSelf()
    }

    private fun progress(finished: Int, total: Int, collecting: Boolean) = base()
        .setContentTitle(getString(R.string.notification_progress_title))
        .setContentText(
            if (total == 0) {
                getString(R.string.notification_preparing)
            } else {
                getString(R.string.notification_progress, finished, total) +
                    if (collecting) " · " + getString(R.string.notification_collecting) else ""
            },
        )
        .setProgress(total, finished, total == 0)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setSilent(true)
        .build()

    private fun base(): NotificationCompat.Builder {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_TAB, "downloads")
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(open)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
    }

    private fun notify(id: Int, notification: android.app.Notification) {
        if (NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            runCatching { NotificationManagerCompat.from(this).notify(id, notification) }
        }
    }

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW).apply {
                    description = getString(R.string.notification_channel_description)
                    lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
                },
            )
        }
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        // Android 15 caps data-sync services at six hours a day; downloads in flight keep running
        // in the app's own scope while it stays alive.
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val CHANNEL = "downloads"
        const val PROGRESS_ID = 1
        const val DONE_ID = 2
    }
}
