package io.github.firebirks.booruvault.ui.vault

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.firebirks.booruvault.R
import io.github.firebirks.booruvault.vault.TagCollection

/** Creates a collection from [prefillQuery], or edits [initial] and offers to delete it. */
@Composable
fun CollectionDialog(
    initial: TagCollection?,
    prefillQuery: String,
    onDismiss: () -> Unit,
    onSave: (name: String, query: String) -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    var name by remember { mutableStateOf(initial?.name ?: prefillQuery.trim().replace('_', ' ')) }
    var query by remember { mutableStateOf(initial?.query ?: prefillQuery.trim()) }
    var confirmDelete by remember { mutableStateOf(false) }

    if (confirmDelete && initial != null && onDelete != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.collection_delete_title, initial.name)) },
            text = { Text(stringResource(R.string.collection_delete_text)) },
            confirmButton = {
                TextButton(onClick = onDelete) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (initial == null) R.string.collection_new else R.string.collection_edit)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.collection_name)) }, singleLine = true)
                OutlinedTextField(
                    query, { query = it },
                    label = { Text(stringResource(R.string.collection_tags)) },
                    supportingText = { Text(stringResource(R.string.collection_tags_hint)) },
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                    maxLines = 3,
                )
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank() && query.isNotBlank(), onClick = { onSave(name.trim(), query.trim()) }) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            Row {
                if (initial != null && onDelete != null) {
                    TextButton(onClick = { confirmDelete = true }) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            }
        },
    )
}
