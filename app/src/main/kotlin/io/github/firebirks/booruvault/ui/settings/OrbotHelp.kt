package io.github.firebirks.booruvault.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.firebirks.booruvault.R
import io.github.firebirks.booruvault.net.TorManager
import io.github.firebirks.booruvault.net.TorStatus
import io.github.firebirks.booruvault.ui.common.CenteredProgress
import io.github.firebirks.booruvault.ui.common.LocalContainer
import io.github.firebirks.booruvault.ui.common.LocalLinkOpener
import io.github.firebirks.booruvault.ui.common.MessageState
import io.github.firebirks.booruvault.ui.common.problemText
import kotlinx.coroutines.delay

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
 * What stands in the way while Tor is on and nothing can go out: Orbot missing, Orbot not
 * running, or an exit check that failed. Each says what to do and offers to do it, and a way
 * round, a direct connection, is offered on each after a word on what it gives up. Orbot says
 * nothing by itself, so while this is on screen the port is greeted every few seconds; once the
 * exit check passes, the screen that shows this reloads by itself.
 */
@Composable
fun TorBlockedState(modifier: Modifier = Modifier) {
    val container = LocalContainer.current
    val tor = container.tor
    val state by tor.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var askInstall by remember { mutableStateOf(false) }
    var askDirect by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            tor.refreshStatus()
            delay(3000)
        }
    }
    val direct = stringResource(R.string.tor_connect_directly)
    when {
        state.status == TorStatus.NOT_INSTALLED -> MessageState(
            Icons.Outlined.Public, stringResource(R.string.tor_need_orbot_title), stringResource(R.string.route_orbot_missing), modifier,
            actionLabel = stringResource(R.string.tor_install_orbot), onAction = { askInstall = true },
            secondaryLabel = direct, onSecondary = { askDirect = true },
        )
        state.status == TorStatus.OFF -> MessageState(
            Icons.Outlined.PowerSettingsNew, stringResource(R.string.tor_orbot_off_title), stringResource(R.string.tor_orbot_off_text), modifier,
            actionLabel = stringResource(R.string.tor_open_orbot), onAction = { openOrbot(context, tor) },
            secondaryLabel = direct, onSecondary = { askDirect = true },
        )
        state.verifyError != null -> MessageState(
            Icons.Outlined.ErrorOutline, stringResource(R.string.tor_verify_failed_title), problemText(state.verifyError!!), modifier,
            actionLabel = stringResource(R.string.action_retry), onAction = tor::verifyNow,
            secondaryLabel = direct, onSecondary = { askDirect = true },
        )
        else -> CenteredProgress(stringResource(R.string.problem_tor_unverified), modifier)
    }
    if (askInstall) OrbotInstallDialog(stringResource(R.string.tor_need_orbot_text), onDismiss = { askInstall = false })
    if (askDirect) {
        AlertDialog(
            onDismissRequest = { askDirect = false },
            title = { Text(stringResource(R.string.tor_direct_confirm_title)) },
            text = { Text(stringResource(R.string.tor_direct_confirm_text, stringResource(R.string.settings_section_network))) },
            confirmButton = {
                TextButton(onClick = {
                    askDirect = false
                    tor.setEnabled(false)
                }) { Text(stringResource(R.string.route_direct)) }
            },
            dismissButton = { TextButton(onClick = { askDirect = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

/** Brings Orbot to the front; it cannot be started from here, only shown, since Orbot 17.9.6. */
fun openOrbot(context: Context, tor: TorManager) {
    tor.orbotLaunchIntent()?.let { runCatching { context.startActivity(it) } }
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
