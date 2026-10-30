package io.github.firebirks.booruvault.vault

import android.os.SystemClock
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import io.github.firebirks.booruvault.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Locks the vault once the app has spent the configured time in the background. Registered on
 * the process lifecycle, so switching between the app's own screens never counts. A delayed job
 * locks on time while the process runs, but Android freezes a cached app about a minute after it
 * leaves the screen, so [alarm] has the system bring it back for the lock too.
 */
class AutoLock(
    private val vault: VaultManager,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
    private val alarm: LockAlarm,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) : DefaultLifecycleObserver {
    @Volatile
    private var backgroundSince = 0L
    private var pending: Job? = null

    /** Raised while a system picker is in front, which would otherwise lock the vault midway. */
    @Volatile
    var holdCount = 0

    /** The timeout in force since the last stop: held, a picker gets at least [HOLD_SECONDS]. */
    @Volatile
    private var seconds = 0

    private val timeout: Int
        get() {
            val configured = settings.settings.value.autoLockSeconds
            return if (holdCount > 0 && configured in 0..<HOLD_SECONDS) HOLD_SECONDS else configured
        }

    override fun onStop(owner: LifecycleOwner) {
        backgroundSince = clock()
        seconds = timeout
        when {
            seconds == 0 -> vault.lock()
            seconds > 0 && vault.state.value is VaultState.Unlocked -> {
                pending = scope.launch {
                    delay(seconds * 1000L)
                    lockIfDue()
                }
                alarm.set(backgroundSince + seconds * 1000L)
            }
        }
    }

    override fun onStart(owner: LifecycleOwner) {
        pending?.cancel()
        pending = null
        alarm.cancel()
        // The alarm is inexact and the job does not run while the process is frozen.
        lockIfDue()
        backgroundSince = 0
    }

    /** Locks the vault if the app has been in the background for the timeout in force. */
    fun lockIfDue() {
        if (backgroundSince > 0 && seconds >= 0 && clock() - backgroundSince >= seconds * 1000L) vault.lock()
    }

    /**
     * Returns a release for a hold. While held, "lock immediately" and short timeouts stretch
     * to [HOLD_SECONDS], so a trip through a system picker does not lock the vault, but leaving
     * the app with the picker in front still does, after that.
     */
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

    private companion object {
        const val HOLD_SECONDS = 300
    }
}

/**
 * Whether the vault going from [was] to [now] should close the screen: it locked while the screen
 * was stopped. A stopped screen is not redrawn until the user comes back, so until then it would
 * hold what it showed, a page in the built-in browser or the key of a file being played.
 */
internal fun closesOnLock(was: VaultState?, now: VaultState, started: Boolean): Boolean =
    was is VaultState.Unlocked && now !is VaultState.Unlocked && !started
