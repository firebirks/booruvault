package io.github.firebirks.booruvault.vault

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
import io.github.firebirks.booruvault.util.formatBytes
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps the process alive while a backup is made or brought back: a large vault takes minutes,
 * and an app left for another would be frozen midway. Its notification says how far it is.
 */
class BackupService : Service() {
    private val scope = MainScope()
    private var observer: Job? = null

    /** The notification speaks the language chosen in the app, like the screens. */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel()
        val backups = (application as BooruVaultApp).container.backups
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, PROGRESS_ID, progress(backups.state.value), type)
        if (observer == null) {
            observer = scope.launch {
                backups.state.collect { state ->
                    if (state is BackupState.Running) {
                        if (NotificationManagerCompat.from(this@BackupService).areNotificationsEnabled()) {
                            runCatching { NotificationManagerCompat.from(this@BackupService).notify(PROGRESS_ID, progress(state)) }
                        }
                    } else {
                        ServiceCompat.stopForeground(this@BackupService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                        observer?.cancel()
                        observer = null
                        stopSelf()
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun progress(state: BackupState): android.app.Notification {
        val running = state as? BackupState.Running
        val known = running != null && running.total > 0
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(open)
            .setContentTitle(getString(if (state.restoring) R.string.restore_action else R.string.settings_backup))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
        if (running != null && known) {
            val text = if (state.restoring) R.string.restore_progress else R.string.backup_progress
            builder.setContentText(getString(text, formatBytes(this, running.done), formatBytes(this, running.total)))
            // In thousandths: a vault's bytes do not fit the int a progress bar counts in.
            builder.setProgress(1000, (running.done * 1000 / running.total).toInt().coerceIn(0, 1000), false)
        } else {
            builder.setProgress(0, 0, true)
        }
        return builder.build()
    }

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.settings_backup), NotificationManager.IMPORTANCE_LOW).apply {
                    lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
                },
            )
        }
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        // Android 15 caps data-sync services at six hours a day; the work goes on in the app's
        // own scope while the app stays alive.
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val CHANNEL = "backup"
        const val PROGRESS_ID = 3
    }
}
