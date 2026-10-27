package io.github.firebirks.booruvault.ui.downloads

import io.github.firebirks.booruvault.ui.common.problemText
import io.github.firebirks.booruvault.R
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ClearAll
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.firebirks.booruvault.download.BatchJob
import io.github.firebirks.booruvault.download.DownloadStatus
import io.github.firebirks.booruvault.download.DownloadTask
import io.github.firebirks.booruvault.ui.common.LocalContainer
import io.github.firebirks.booruvault.ui.common.MessageState
import io.github.firebirks.booruvault.util.formatBytes
import coil3.compose.AsyncImage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen() {
    val container = LocalContainer.current
    val downloads = container.downloads
    val tasks by downloads.tasks.collectAsStateWithLifecycle()
    val batches by downloads.batches.collectAsStateWithLifecycle()
    var menu by remember { mutableStateOf(false) }
    val ordered = remember(tasks) { tasks.sortedWith(compareBy<DownloadTask> { !it.active }.thenByDescending { it.key }) }
    val active = tasks.count { it.active }
    val done = tasks.count { it.status == DownloadStatus.DONE }
    val failed = tasks.count { it.status == DownloadStatus.FAILED }
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.downloads_title))
                        if (tasks.isNotEmpty()) {
                            Text(
                                listOfNotNull(
                                    pluralStringResource(R.plurals.downloads_active, active, active),
                                    pluralStringResource(R.plurals.downloads_saved, done, done),
                                    if (failed > 0) pluralStringResource(R.plurals.downloads_errors, failed, failed) else null,
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                actions = {
                    if (failed > 0) IconButton(onClick = downloads::retryFailed) { Icon(Icons.Outlined.Refresh, stringResource(R.string.downloads_retry_failed)) }
                    IconButton(onClick = downloads::clearFinished) { Icon(Icons.Outlined.ClearAll, stringResource(R.string.downloads_clear_finished)) }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, stringResource(R.string.action_more)) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.downloads_cancel_all)) },
                                leadingIcon = { Icon(Icons.Outlined.Cancel, null) },
                                onClick = {
                                    menu = false
                                    downloads.cancelAll()
                                },
                            )
                        }
                    }
                },
            )
        },
        contentWindowInsets = WindowInsets(0),
    ) { padding ->
        if (tasks.isEmpty() && batches.isEmpty()) {
            MessageState(
                Icons.Outlined.CloudDownload, stringResource(R.string.downloads_empty_title),
                stringResource(R.string.downloads_empty_text),
                modifier = Modifier.padding(padding),
            )
            return@Scaffold
        }
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
            items(batches, key = { "batch-${it.key}" }) { batch -> BatchRow(batch, onCancel = { downloads.cancelBatch(batch.key) }) }
            items(ordered, key = { it.key }) { task ->
                TaskRow(task, { formatBytes(context, it) }, onCancel = { downloads.cancel(task.key) }, onRetry = { downloads.retry(task.key) })
            }
        }
    }
}

@Composable
private fun BatchRow(batch: BatchJob, onCancel: () -> Unit) {
    ListItem(
        headlineContent = { Text(stringResource(R.string.batch_title, batch.tags.ifBlank { stringResource(R.string.batch_everything) })) },
        supportingContent = {
            Column {
                Text(stringResource(R.string.batch_progress, batch.source.name, batch.found, batch.target))
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
            }
        },
        leadingContent = { Icon(Icons.Outlined.CloudDownload, null) },
        trailingContent = { IconButton(onClick = onCancel) { Icon(Icons.Outlined.Close, stringResource(R.string.batch_stop)) } },
    )
}

@Composable
private fun TaskRow(task: DownloadTask, bytes: (Long) -> String, onCancel: () -> Unit, onRetry: () -> Unit) {
    ListItem(
        headlineContent = { Text("${task.source.name} #${task.post.id}") },
        supportingContent = {
            Column {
                val status = when (task.status) {
                    DownloadStatus.QUEUED -> stringResource(R.string.task_queued)
                    DownloadStatus.RUNNING -> if (task.total > 0) stringResource(R.string.task_progress, bytes(task.bytes), bytes(task.total)) else bytes(task.bytes)
                    DownloadStatus.DONE -> stringResource(R.string.task_saved, bytes(task.bytes))
                    DownloadStatus.SKIPPED -> task.problem?.let { problemText(it) } ?: stringResource(R.string.task_skipped)
                    DownloadStatus.FAILED -> task.problem?.let { problemText(it) } ?: stringResource(R.string.task_failed)
                    DownloadStatus.CANCELLED -> stringResource(R.string.task_cancelled)
                }
                Text(
                    status,
                    color = if (task.status == DownloadStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (task.status == DownloadStatus.RUNNING) {
                    val progress = task.progress
                    if (progress != null) {
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
                    }
                }
            }
        },
        leadingContent = {
            AsyncImage(
                model = task.post.previewUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(52.dp).clip(RoundedCornerShape(8.dp)),
            )
        },
        trailingContent = {
            when (task.status) {
                DownloadStatus.QUEUED, DownloadStatus.RUNNING -> IconButton(onClick = onCancel) { Icon(Icons.Outlined.Close, stringResource(R.string.action_cancel)) }
                DownloadStatus.FAILED, DownloadStatus.CANCELLED -> IconButton(onClick = onRetry) { Icon(Icons.Outlined.Refresh, stringResource(R.string.action_retry)) }
                DownloadStatus.DONE, DownloadStatus.SKIPPED -> Icon(Icons.Outlined.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
            }
        },
    )
}
