package io.github.firebirks.booruvault.vault

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.firebirks.booruvault.BooruVaultApp

/** Calls [AutoLock.lockIfDue] at a time on the elapsed-realtime clock, the process running or not. */
interface LockAlarm {
    fun set(atElapsed: Long)

    fun cancel()
}

/**
 * Android freezes an app soon after it leaves the screen, and a frozen app's own timers do not
 * run; the system thaws it to deliver an alarm. Inexact, so it needs no permission and may come
 * some minutes late; it wakes the phone and goes off in Doze too.
 */
class SystemLockAlarm(context: Context) : LockAlarm {
    private val alarms = context.getSystemService(AlarmManager::class.java)
    private val intent = PendingIntent.getBroadcast(
        context, 0, Intent(context, AutoLockReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    override fun set(atElapsed: Long) = alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, atElapsed, intent)

    override fun cancel() = alarms.cancel(intent)
}

class AutoLockReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        (context.applicationContext as BooruVaultApp).container.autoLock.lockIfDue()
    }
}
