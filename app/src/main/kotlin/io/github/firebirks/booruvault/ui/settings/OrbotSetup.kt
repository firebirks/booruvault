package io.github.firebirks.booruvault.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.firebirks.booruvault.R
import io.github.firebirks.booruvault.net.TorManager
import io.github.firebirks.booruvault.net.socks5Listening
import io.github.firebirks.booruvault.ui.common.LocalContainer
import io.github.firebirks.booruvault.ui.common.LocalLinkOpener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Where to get Orbot: from an installed store, or from its own site; [text] says what to do next. */
@Composable
fun OrbotInstallDialog(text: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val links = LocalLinkOpener.current
    val chooserTitle = stringResource(R.string.tor_install_with)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.tor_need_orbot_title)) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                openStoreFor(context, TorManager.ORBOT_PACKAGE, chooserTitle) { links.open(ORBOT_DOWNLOAD) }
            }) { Text(stringResource(R.string.tor_search_stores)) }
        },
        dismissButton = {
            TextButton(onClick = {
                onDismiss()
                links.open(ORBOT_DOWNLOAD)
            }) { Text(stringResource(R.string.tor_orbot_site)) }
        },
    )
}

/**
 * Right after Tor was chosen at the first start: without Orbot, the way to install it is offered
 * as in Settings; with Orbot installed but not answering, it is opened once. Nothing goes out
 * meanwhile: with Tor on, the app waits for Orbot and for its exit to be verified.
 */
@Composable
fun OrbotSetup(onDone: () -> Unit) {
    val tor = LocalContainer.current.tor
    val context = LocalContext.current
    val installed = remember { tor.isOrbotInstalled() }
    if (!installed) {
        OrbotInstallDialog(stringResource(R.string.route_orbot_missing), onDismiss = onDone)
        return
    }
    LaunchedEffect(Unit) {
        val port = tor.state.value.socksPort
        if (!withContext(Dispatchers.IO) { socks5Listening(port) }) {
            tor.orbotLaunchIntent()?.let { runCatching { context.startActivity(it) } }
        }
        tor.refreshStatus()
        onDone()
    }
}

private const val ORBOT_DOWNLOAD = "https://orbot.app/download/"

/**
 * Opens [packageName]'s page in an installed app store, letting the user choose when there are
 * several (F-Droid, Droid-ify, Aurora, Play...), or runs [fallback] when there is none.
 */
private fun openStoreFor(context: Context, packageName: String, chooserTitle: String, fallback: () -> Unit) {
    val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName"))
    val stores = context.packageManager.queryIntentActivities(market, 0)
    when {
        stores.isEmpty() -> fallback()
        stores.size == 1 -> context.startActivity(market)
        else -> context.startActivity(Intent.createChooser(market, chooserTitle))
    }
}
