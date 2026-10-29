package io.github.firebirks.booruvault.ui.common

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.firebirks.booruvault.R
import io.github.firebirks.booruvault.util.SensitiveClipboard
import kotlinx.coroutines.delay
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Opens a link outside the app, keeping to Tor when Tor is on. */
fun interface LinkOpener {
    fun open(url: String)
}

val LocalLinkOpener = staticCompositionLocalOf<LinkOpener> { LinkOpener { } }

private val TOR_BROWSERS = listOf("org.torproject.torbrowser", "org.torproject.torbrowser_alpha")

/** Seconds the warning stays up before a normal browser may be opened with Tor on. */
private const val WARNING_SECONDS = 5

/**
 * With Tor off, links open in the default browser. With Tor on they open in Tor Browser. If it
 * is missing, the link can be copied; opening it in a normal browser is possible only with the
 * risky option on, and then behind a warning that holds the way out for a few seconds, so it is
 * read rather than dismissed.
 */
@Composable
fun ProvideLinkOpener(content: @Composable () -> Unit) {
    val container = LocalContainer.current
    val context = LocalContext.current
    val settings by container.settings.settings.collectAsStateWithLifecycle()
    var pending by remember { mutableStateOf<String?>(null) }
    val opener = remember(context) {
        LinkOpener { url ->
            when {
                !isWebLink(url) -> Toast.makeText(context, context.getString(R.string.no_app_for_link), Toast.LENGTH_SHORT).show()
                !container.tor.enabled -> context.openUrl(url)
                else -> torBrowser(context)?.let { context.openIn(url, it) } ?: run { pending = url }
            }
        }
    }
    CompositionLocalProvider(LocalLinkOpener provides opener) { content() }
    pending?.let { url ->
        if (settings.linksOutsideTor) {
            OutsideTorWarning(
                onOpen = {
                    pending = null
                    context.openUrl(url)
                },
                onCopy = {
                    pending = null
                    copySensitive(context, container.clipboard, url)
                },
                onDismiss = { pending = null },
            )
        } else {
            NoTorBrowserDialog(
                onCopy = {
                    pending = null
                    copySensitive(context, container.clipboard, url)
                },
                onDismiss = { pending = null },
            )
        }
    }
}

/** Tor Browser is missing and leaving Tor is not allowed: the link can only be copied. */
@Composable
private fun NoTorBrowserDialog(onCopy: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.WarningAmber, null) },
        title = { Text(stringResource(R.string.external_blocked_title)) },
        text = { Text(stringResource(R.string.external_blocked_text)) },
        confirmButton = { TextButton(onClick = onCopy) { Text(stringResource(R.string.external_copy_link)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun OutsideTorWarning(onOpen: () -> Unit, onCopy: () -> Unit, onDismiss: () -> Unit) {
    var remaining by remember { mutableIntStateOf(WARNING_SECONDS) }
    LaunchedEffect(Unit) {
        while (remaining > 0) {
            delay(1000)
            remaining--
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.WarningAmber, null, tint = MaterialTheme.colorScheme.error) },
        title = { Text(stringResource(R.string.external_warning_title)) },
        text = { Text(stringResource(R.string.external_warning_text)) },
        confirmButton = {
            TextButton(enabled = remaining == 0, onClick = onOpen) {
                Text(
                    if (remaining > 0) stringResource(R.string.external_open_anyway_wait, remaining) else stringResource(R.string.external_open_anyway),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onCopy) { Text(stringResource(R.string.external_copy_link)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            }
        },
    )
}

/** Only web pages leave the app: a site's page may carry tel:, intent: or file: links too. */
internal fun isWebLink(url: String): Boolean = url.toHttpUrlOrNull() != null

private fun torBrowser(context: Context): String? = TOR_BROWSERS.firstOrNull { name ->
    try {
        context.packageManager.getPackageInfo(name, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }
}

private fun Context.openIn(url: String, packageName: String) {
    try {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Exception) {
        Toast.makeText(this, getString(R.string.no_app_for_link), Toast.LENGTH_SHORT).show()
    }
}

private fun copySensitive(context: Context, clipboard: SensitiveClipboard, text: String) {
    clipboard.copy("link", text)
    Toast.makeText(context, context.getString(R.string.external_link_copied), Toast.LENGTH_SHORT).show()
}
