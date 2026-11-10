package io.github.firebirks.booruvault.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.firebirks.booruvault.R
import io.github.firebirks.booruvault.ui.common.LocalContainer
import io.github.firebirks.booruvault.ui.common.problemText
import io.github.firebirks.booruvault.ui.common.rememberNotificationPermission
import io.github.firebirks.booruvault.util.formatBytes
import io.github.firebirks.booruvault.vault.BackupState
import io.github.firebirks.booruvault.vault.VaultBackup
import java.time.LocalDate

/**
 * The vault as one file to keep elsewhere. What it holds, and that the password is all that
 * protects it once it has left the phone, is said before the system asks where to put it. The
 * row then shows how far the backup is, with a way to stop it, and how the last one ended: the
 * work is the app's, not this page's, so it is still there after a lock or a walk through
 * other screens.
 */
@Composable
internal fun BackupRow() {
    val container = LocalContainer.current
    val context = LocalContext.current
    val state by container.backups.state.collectAsStateWithLifecycle()
    var explain by rememberSaveable { mutableStateOf(false) }
    val askNotifications = rememberNotificationPermission()
    // The system's picker puts the app in the background: the vault is kept open through it.
    var pickerHold by remember { mutableStateOf<(() -> Unit)?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(VaultBackup.MIME)) { uri ->
        pickerHold?.invoke()
        pickerHold = null
        if (uri != null) container.backups.backUp(uri)
    }
    val own = state.takeIf { !it.restoring }
    val running = own as? BackupState.Running

    ListItem(
        headlineContent = { Text(stringResource(R.string.settings_backup)) },
        supportingContent = {
            // Always there, so that what appears in it, an end or a failure, is said aloud.
            Column(Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (own) {
                    is BackupState.Running -> {
                        if (own.total > 0) {
                            Text(stringResource(R.string.backup_progress, formatBytes(context, own.done), formatBytes(context, own.total)))
                            LinearProgressIndicator(progress = { (own.done.toFloat() / own.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                        } else {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                    }
                    is BackupState.Done -> Text(stringResource(R.string.backup_done, formatBytes(context, own.bytes)))
                    is BackupState.Failed -> Text(
                        stringResource(R.string.backup_failed, context.problemText(own.problem)),
                        color = MaterialTheme.colorScheme.error,
                    )
                    else -> Text(stringResource(R.string.settings_backup_hint))
                }
            }
        },
        leadingContent = { Icon(Icons.Outlined.Archive, null) },
        trailingContent = if (running != null) {
            { TextButton(onClick = container.backups::cancel) { Text(stringResource(R.string.action_cancel)) } }
        } else {
            null
        },
        modifier = if (running == null) Modifier.clickable { explain = true } else Modifier,
    )

    if (explain) {
        AlertDialog(
            onDismissRequest = { explain = false },
            icon = { Icon(Icons.Outlined.Archive, null) },
            title = { Text(stringResource(R.string.settings_backup)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(stringResource(R.string.backup_text, stringResource(R.string.restore_action)))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    explain = false
                    askNotifications()
                    pickerHold?.invoke()
                    pickerHold = container.autoLock.hold()
                    picker.launch("booruvault-backup-${LocalDate.now()}.zip")
                }) { Text(stringResource(R.string.backup_choose)) }
            },
            dismissButton = { TextButton(onClick = { explain = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}
