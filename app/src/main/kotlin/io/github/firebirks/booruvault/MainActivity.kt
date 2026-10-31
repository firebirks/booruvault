package io.github.firebirks.booruvault

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.github.firebirks.booruvault.settings.AppLanguage
import io.github.firebirks.booruvault.ui.AppRoot
import io.github.firebirks.booruvault.ui.common.IncognitoKeyboard
import io.github.firebirks.booruvault.ui.theme.BooruVaultTheme
import io.github.firebirks.booruvault.vault.VaultState
import io.github.firebirks.booruvault.vault.closesOnLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** A FragmentActivity because BiometricPrompt needs one. */
class MainActivity : FragmentActivity() {
    /** A tab a notification asked to open, consumed by the UI. */
    val requestedTab = MutableStateFlow<String?>(null)

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as BooruVaultApp).container
        requestedTab.value = intent?.getStringExtra(EXTRA_TAB)

        // Keep the vault out of screenshots and the recent-apps preview. Until the vault is open
        // the user's choice is unknown, so the screen is protected.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                combine(container.settings.ready, container.settings.settings) { ready, settings -> !ready || settings.secureScreen }
                    .distinctUntilChanged().collect { secure ->
                    if (secure) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }
        }

        // A lock that finds the screen stopped closes it and takes it out of recent apps, which
        // releases the browser and the player with it; the next visit starts at the lock screen.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                var last: VaultState? = null
                container.vault.state.collect { state ->
                    if (closesOnLock(last, state, lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))) finishAndRemoveTask()
                    last = state
                }
            }
        }

        setContent {
            BooruVaultTheme {
                IncognitoKeyboard {
                    AppRoot(container = container, requestedTab = requestedTab)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_TAB)?.let { requestedTab.value = it }
    }

    companion object {
        const val EXTRA_TAB = "tab"
    }
}
