package app.booruvault.vault

import android.os.SystemClock
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import app.booruvault.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Locks the vault once the app has spent the configured time in the background. Registered on
 * the process lifecycle, so switching between the app's own screens never counts.
 */
class AutoLock(
    private val vault: VaultManager,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
) : DefaultLifecycleObserver {
    private var backgroundSince = 0L
    private var pending: Job? = null

    /** Raised while a system picker is in front, which would otherwise lock the vault midway. */
    @Volatile
    var holdCount = 0

    private val timeout get() = settings.settings.value.autoLockSeconds

    override fun onStop(owner: LifecycleOwner) {
        if (holdCount > 0) return
        backgroundSince = SystemClock.elapsedRealtime()
        val seconds = timeout
        when {
            seconds == 0 -> vault.lock()
            seconds > 0 -> pending = scope.launch {
                delay(seconds * 1000L)
                vault.lock()
            }
        }
    }

    override fun onStart(owner: LifecycleOwner) {
        pending?.cancel()
        pending = null
        // The delayed job may not have run if the process was frozen; check the clock too.
        val seconds = timeout
        if (backgroundSince > 0 && seconds >= 0 &&
            SystemClock.elapsedRealtime() - backgroundSince >= seconds * 1000L
        ) {
            vault.lock()
        }
        backgroundSince = 0
    }

    fun hold(): () -> Unit {
        holdCount++
        var released = false
        return {
            if (!released) {
                released = true
                holdCount--
            }
        }
    }
}
