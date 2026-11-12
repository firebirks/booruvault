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
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.firebirks.booruvault.download.BatchJob
import io.github.firebirks.booruvault.download.DownloadStatus
import io.github.firebirks.booruvault.download.DownloadTask
import io.github.firebirks.booruvault.ui.common.ChallengeMode
import io.github.firebirks.booruvault.ui.common.LocalChallenge
import io.github.firebirks.booruvault.ui.common.LocalContainer
import io.github.firebirks.booruvault.ui.common.MessageState
import io.github.firebirks.booruvault.util.Problem
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
    var confirmCancelAll by remember { mutableStateOf(false) }
    val ordered = remember(tasks) { tasks.sortedWith(compareBy<DownloadTask> { !it.active }.thenByDescending { it.key }) }
    val active = tasks.count { it.active }
    val done = tasks.count { it.status == DownloadStatus.DONE }
    val failed = tasks.count { it.status == DownloadStatus.FAILED }
    val retriable = failed + tasks.count { it.status == DownloadStatus.CANCELLED }
    // A collection a problem stopped is an error too, listed with the downloads.
    val errors = failed + batches.count { it.problem != null }
    val context = LocalContext.current
    val challenge = LocalChallenge.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.downloads_title))
                        if (tasks.isNotEmpty() || batches.isNotEmpty()) {
                            Text(
                                listOfNotNull(
                                    pluralStringResource(R.plurals.downloads_active, active, active),
                                    pluralStringResource(R.plurals.downloads_saved, done, done),
                                    if (errors > 0) pluralStringResource(R.plurals.downloads_errors, errors, errors) else null,
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                actions = {
                    if (retriable > 0) IconButton(onClick = downloads::retryFailed) { Icon(Icons.Outlined.Refresh, stringResource(R.string.downloads_retry_failed)) }
                    IconButton(onClick = downloads::clearFinished) { Icon(Icons.Outlined.ClearAll, stringResource(R.string.downloads_clear_finished)) }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, stringResource(R.string.action_more)) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.downloads_cancel_all)) },
                                leadingIcon = { Icon(Icons.Outlined.Cancel, null) },
                                enabled = active > 0 || batches.any { it.active },
                                onClick = {
                                    menu = false
                                    confirmCancelAll = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.downloads_clear_failed)) },
                                leadingIcon = { Icon(Icons.Outlined.DeleteSweep, null) },
                                enabled = retriable > 0 || batches.any { it.retriable },
                                onClick = {
                                    menu = false
                                    downloads.clearFailed()
                                },
                            )
                        }
                    }
                },
            )
        },
        contentWindowInsets = WindowInsets(0),
    ) { padding ->
        if (confirmCancelAll) {
            AlertDialog(
                onDismissRequest = { confirmCancelAll = false },
                title = { Text(stringResource(R.string.downloads_cancel_all_title)) },
                text = { Text(stringResource(R.string.downloads_cancel_all_text)) },
                confirmButton = {
                    TextButton(onClick = {
                        confirmCancelAll = false
                        downloads.cancelAll()
                    }) { Text(stringResource(R.string.downloads_cancel_all), color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = { TextButton(onClick = { confirmCancelAll = false }) { Text(stringResource(R.string.action_keep_going)) } },
            )
        }
        if (tasks.isEmpty() && batches.isEmpty()) {
            MessageState(
                Icons.Outlined.CloudDownload, stringResource(R.string.downloads_empty_title),
                stringResource(R.string.downloads_empty_text),
                modifier = Modifier.padding(padding),
            )
            return@Scaffold
        }
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
            items(batches, key = { "batch-${it.key}" }) { batch ->
                BatchRow(
                    batch,
                    onCancel = { downloads.cancelBatch(batch.key) },
                    onRetry = { downloads.retryBatch(batch.key) },
                    onRemove = { downloads.removeBatch(batch.key) },
                    // A check met reading the search is the site's own, passed at its home.
                    onVerify = if (batch.problem?.code == Problem.Code.CHALLENGE) {
                        { challenge.open(batch.source, batch.source.baseUrl, ChallengeMode.SITE) { downloads.retryBatch(batch.key) } }
                    } else {
                        null
                    },
                )
            }
            items(ordered, key = { it.key }) { task ->
                val check = task.problem?.takeIf { task.status == DownloadStatus.FAILED && it.code == Problem.Code.CHALLENGE }
                TaskRow(
                    task, { formatBytes(context, it) },
                    onCancel = { downloads.cancel(task.key) },
                    onRetry = { downloads.retry(task.key) },
                    onRemove = { downloads.remove(task.key) },
                    onVerify = check?.let { problem ->
                        {
                            // A file host guards the file itself and is passed there; a check met on
                            // the site's own pages is passed at its home.
                            val mode = if (problem.detail != null) ChallengeMode.ADDRESS else ChallengeMode.SITE
                            challenge.open(task.source, problem.detail ?: task.source.baseUrl, mode) { downloads.retryAfterCheck(task.source.id) }
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun BatchRow(batch: BatchJob, onCancel: () -> Unit, onRetry: () -> Unit, onRemove: () -> Unit, onVerify: (() -> Unit)?) {
    ListItem(
        headlineContent = {
            val title = stringResource(R.string.batch_title, batch.tags.ifBlank { stringResource(R.string.batch_everything) })
            if (batch.active) Text(title) else StoppedHeadline(title, onRetry, onRemove)
        },
        supportingContent = {
            Column {
                Text(stringResource(R.string.batch_progress, batch.source.name, batch.found, batch.target))
                val problem = batch.problem
                when {
                    problem != null -> Text(problemText(problem), color = MaterialTheme.colorScheme.error)
                    batch.stopped -> Text(stringResource(R.string.task_cancelled), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
                }
                if (onVerify != null) {
                    TextButton(onClick = onVerify, contentPadding = PaddingValues(0.dp)) { Text(stringResource(R.string.action_verify_in_browser)) }
                }
            }
        },
        leadingContent = { Icon(Icons.Outlined.CloudDownload, null) },
        trailingContent = if (batch.active) {
            { IconButton(onClick = onCancel) { Icon(Icons.Outlined.Close, stringResource(R.string.batch_stop)) } }
        } else {
            null
        },
    )
}

/**
 * The title of a stopped row with what the row offers beside it: try it again, or take just this
 * one off the list. The buttons sit on the title's line rather than at the row's end, where two
 * of them would narrow the whole row: what stopped it is said below, across the full width.
 */
@Composable
private fun StoppedHeadline(title: String, onRetry: () -> Unit, onRemove: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
        IconButton(onClick = onRetry) { Icon(Icons.Outlined.Refresh, stringResource(R.string.action_retry)) }
        IconButton(onClick = onRemove) { Icon(Icons.Outlined.Close, stringResource(R.string.action_remove)) }
    }
}

@Composable
private fun TaskRow(task: DownloadTask, bytes: (Long) -> String, onCancel: () -> Unit, onRetry: () -> Unit, onRemove: () -> Unit, onVerify: (() -> Unit)?) {
    ListItem(
        headlineContent = {
            val title = "${task.source.name} #${task.post.id}"
            if (task.retriable) StoppedHeadline(title, onRetry, onRemove) else Text(title)
        },
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
                if (onVerify != null) {
                    TextButton(onClick = onVerify, contentPadding = PaddingValues(0.dp)) { Text(stringResource(R.string.action_verify_in_browser)) }
                }
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
        trailingContent = when (task.status) {
            DownloadStatus.QUEUED, DownloadStatus.RUNNING -> {
                { IconButton(onClick = onCancel) { Icon(Icons.Outlined.Close, stringResource(R.string.action_cancel)) } }
            }
            // Its buttons are beside the title: see StoppedHeadline.
            DownloadStatus.FAILED, DownloadStatus.CANCELLED -> null
            // As wide as the buttons on the other rows, so the icons line up.
            DownloadStatus.DONE, DownloadStatus.SKIPPED -> {
                { Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { Icon(Icons.Outlined.CheckCircle, null, tint = MaterialTheme.colorScheme.primary) } }
            }
        },
    )
}
