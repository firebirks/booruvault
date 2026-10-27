package app.booruvault

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.booruvault.ui.AppRoot
import app.booruvault.ui.common.IncognitoKeyboard
import app.booruvault.ui.theme.BooruVaultTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** A FragmentActivity because BiometricPrompt needs one. */
class MainActivity : FragmentActivity() {
    /** A tab a notification asked to open, consumed by the UI. */
    val requestedTab = MutableStateFlow<String?>(null)

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
