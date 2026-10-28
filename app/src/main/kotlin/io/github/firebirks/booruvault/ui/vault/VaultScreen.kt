package io.github.firebirks.booruvault.ui.vault

import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyRow
import io.github.firebirks.booruvault.vault.TagCollection
import io.github.firebirks.booruvault.ui.common.resolve
import io.github.firebirks.booruvault.R
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Gif
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.firebirks.booruvault.ui.common.CenteredProgress
import io.github.firebirks.booruvault.ui.common.LocalContainer
import io.github.firebirks.booruvault.ui.common.MessageState
import io.github.firebirks.booruvault.ui.common.containerViewModel
import io.github.firebirks.booruvault.util.formatBytes
import io.github.firebirks.booruvault.vault.VaultImage
import io.github.firebirks.booruvault.vault.VaultItem
import coil3.compose.AsyncImage

/** The vault tab. The whole app is behind the vault lock, so it is always open when shown. */
@Composable
fun VaultTab(onOpenItem: (String) -> Unit) {
    VaultContents(onOpenItem = onOpenItem)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VaultContents(onOpenItem: (String) -> Unit) {
    val container = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val vm = containerViewModel { VaultViewModel(it) }
    val items by vm.items.collectAsStateWithLifecycle()
    val total by container.vaultStore.items.collectAsStateWithLifecycle()
    val loading by container.vaultStore.loading.collectAsStateWithLifecycle()
    val selection by vm.selection.collectAsStateWithLifecycle()
    val transfer by vm.transfer.collectAsStateWithLifecycle()
    val settings by container.settings.settings.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val gridState = rememberLazyGridState()
    var pendingImport by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var confirmDelete by remember { mutableStateOf(false) }
    var releaseHold by remember { mutableStateOf<(() -> Unit)?>(null) }
    var exportIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    val collections by vm.collections.collectAsStateWithLifecycle()
    val counts by vm.counts.collectAsStateWithLifecycle()
    var creating by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<TagCollection?>(null) }
    val selectedCollection = collections.firstOrNull { it.id == vm.collectionId }

    LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it.resolve(context)) } }
    LaunchedEffect(vm) {
        vm.scrollTo.collect { id ->
            val index = vm.items.value.indexOfFirst { it.id == id }
            val visible = gridState.layoutInfo.visibleItemsInfo.map { it.index }
            if (index >= 0 && index !in visible) gridState.scrollToItem(index)
        }
    }
    BackHandler(enabled = selection.isNotEmpty()) { vm.clearSelection() }

    // System pickers put the app in the background; keep the vault open while they are up.
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        releaseHold?.invoke()
        releaseHold = null
        if (uris.isNotEmpty()) pendingImport = uris
    }
    val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        releaseHold?.invoke()
        releaseHold = null
        if (uri != null) vm.exportToFolder(exportIds, uri)
    }

    Scaffold(
        topBar = {
            if (selection.isNotEmpty()) {
                TopAppBar(
                    title = { Text(pluralStringResource(R.plurals.selected_count, selection.size, selection.size)) },
                    navigationIcon = { IconButton(onClick = vm::clearSelection) { Icon(Icons.Outlined.Close, stringResource(R.string.action_cancel_selection)) } },
                    actions = {
                        IconButton(onClick = vm::selectAll) { Icon(Icons.Outlined.SelectAll, stringResource(R.string.action_select_all)) }
                        IconButton(onClick = {
                            exportIds = selection
                            releaseHold = container.autoLock.hold()
                            folderLauncher.launch(null)
                        }) { Icon(Icons.Outlined.FolderOpen, stringResource(R.string.vault_export_folder)) }
                        IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Outlined.Delete, stringResource(R.string.action_delete)) }
                    },
                )
            } else {
                TopAppBar(
                    title = {
                        Column {
                            Text(stringResource(R.string.vault_title))
                            Text(
                                pluralStringResource(R.plurals.vault_items, total.size, total.size) + " · " +
                                    formatBytes(context, total.sumOf { it.size }),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = {
                            releaseHold = container.autoLock.hold()
                            importLauncher.launch(arrayOf("image/*", "video/*"))
                        }) { Icon(Icons.Outlined.Add, stringResource(R.string.vault_import)) }
                        IconButton(onClick = { vm.newestFirst = !vm.newestFirst }) {
                            Icon(
                                Icons.AutoMirrored.Outlined.Sort,
                                stringResource(if (vm.newestFirst) R.string.vault_oldest_first else R.string.vault_newest_first),
                            )
                        }
                        IconButton(onClick = vm::lock) { Icon(Icons.Outlined.Lock, stringResource(R.string.vault_lock_now)) }
                    },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
        contentWindowInsets = WindowInsets(0),
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (total.isNotEmpty()) {
                OutlinedTextField(
                    value = vm.query,
                    onValueChange = { vm.query = it },
                    placeholder = { Text(stringResource(R.string.vault_filter_hint)) },
                    leadingIcon = { Icon(Icons.Outlined.Search, null) },
                    trailingIcon = {
                        if (vm.query.isNotEmpty()) {
                            Row {
                                IconButton(onClick = { creating = vm.query }) { Icon(Icons.Outlined.BookmarkAdd, stringResource(R.string.collection_save)) }
                                IconButton(onClick = { vm.query = "" }) { Icon(Icons.Outlined.Close, stringResource(R.string.action_clear)) }
                            }
                        }
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, autoCorrectEnabled = false),
                    shape = RoundedCornerShape(28.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                )
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item(key = "all") {
                        FilterChip(selected = vm.collectionId == null, onClick = { vm.collectionId = null }, label = { Text(stringResource(R.string.collections_all)) })
                    }
                    items(collections, key = { it.id }) { collection ->
                        FilterChip(
                            selected = vm.collectionId == collection.id,
                            onClick = { vm.collectionId = if (vm.collectionId == collection.id) null else collection.id },
                            label = { Text("${collection.name} · ${counts[collection.id] ?: 0}") },
                        )
                    }
                    selectedCollection?.let { collection ->
                        item(key = "edit") {
                            AssistChip(
                                onClick = { editing = collection },
                                label = { Text(stringResource(R.string.action_edit)) },
                                leadingIcon = { Icon(Icons.Outlined.Edit, null) },
                            )
                        }
                    }
                    item(key = "new") {
                        AssistChip(
                            onClick = { creating = vm.query },
                            label = { Text(stringResource(R.string.collection_new)) },
                            leadingIcon = { Icon(Icons.Outlined.Add, null) },
                        )
                    }
                }
            }
            transfer?.let { t ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(
                        stringResource(if (t.importing) R.string.transfer_importing else R.string.transfer_exporting, t.done, t.total),
                        style = MaterialTheme.typography.labelMedium,
                    )
                    LinearProgressIndicator(
                        progress = { if (t.total == 0) 0f else t.done.toFloat() / t.total },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    )
                }
            }
            when {
                loading && total.isEmpty() -> CenteredProgress(stringResource(R.string.vault_decrypting_index))
                total.isEmpty() -> MessageState(
                    Icons.Outlined.Inventory2, stringResource(R.string.vault_empty_title),
                    stringResource(R.string.vault_empty_text),
                )
                items.isEmpty() && selectedCollection != null && vm.query.isBlank() -> MessageState(
                    Icons.Outlined.CollectionsBookmark,
                    stringResource(R.string.collection_empty_title),
                    stringResource(R.string.collection_empty_text, selectedCollection.query),
                )
                items.isEmpty() -> MessageState(Icons.Outlined.SearchOff, stringResource(R.string.vault_no_match))
                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(settings.gridSize.minCellDp.dp),
                    state = gridState,
                    contentPadding = PaddingValues(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(items, key = { it.id }) { item ->
                        VaultCell(
                            item = item,
                            selected = item.id in selection,
                            selecting = selection.isNotEmpty(),
                            onClick = { if (selection.isNotEmpty()) vm.toggle(item.id) else onOpenItem(item.id) },
                            onLongClick = { vm.startSelection(item.id) },
                        )
                    }
                }
            }
        }
    }

    creating?.let { prefill ->
        CollectionDialog(
            initial = null,
            prefillQuery = prefill,
            onDismiss = { creating = null },
            onSave = { name, query ->
                creating = null
                vm.query = ""
                vm.saveCollection(null, name, query)
            },
        )
    }
    editing?.let { collection ->
        CollectionDialog(
            initial = collection,
            prefillQuery = collection.query,
            onDismiss = { editing = null },
            onSave = { name, query ->
                editing = null
                vm.saveCollection(collection, name, query)
            },
            onDelete = {
                editing = null
                vm.deleteCollection(collection.id)
            },
        )
    }

    if (pendingImport.isNotEmpty()) {
        var deleteOriginals by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { pendingImport = emptyList() },
            title = { Text(pluralStringResource(R.plurals.vault_import_title, pendingImport.size, pendingImport.size)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.vault_import_text))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = deleteOriginals, onCheckedChange = { deleteOriginals = it })
                        Text(stringResource(R.string.vault_import_delete_originals))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.import(pendingImport, deleteOriginals)
                    pendingImport = emptyList()
                }) { Text(stringResource(R.string.action_import)) }
            },
            dismissButton = { TextButton(onClick = { pendingImport = emptyList() }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    if (confirmDelete) {
        val count = selection.size
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            icon = { Icon(Icons.Outlined.Delete, null) },
            title = { Text(pluralStringResource(R.plurals.vault_delete_title, count, count)) },
            text = { Text(stringResource(R.string.vault_delete_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    vm.delete(selection)
                }) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VaultCell(item: VaultItem, selected: Boolean, selecting: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        Modifier
            .aspectRatio(1f)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .then(if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, shape) else Modifier)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        if (item.hasThumbnail || !item.isVideo) {
            AsyncImage(
                model = VaultImage(item.id, thumbnail = item.hasThumbnail),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(Icons.Outlined.Movie, null, Modifier.align(Alignment.Center).size(36.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        when {
            item.isVideo -> CellBadge(Icons.Outlined.PlayArrow, Modifier.align(Alignment.TopStart).padding(6.dp))
            item.isAnimated -> CellBadge(Icons.Outlined.Gif, Modifier.align(Alignment.TopStart).padding(6.dp))
        }
        if (selecting) {
            Box(Modifier.fillMaxSize().background(if (selected) Color.Black.copy(alpha = 0.25f) else Color.Transparent))
            Icon(
                if (selected) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                contentDescription = null,
                tint = if (selected) MaterialTheme.colorScheme.primary else Color.White,
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(24.dp)
                    .background(if (selected) Color.White else Color.Black.copy(alpha = 0.3f), CircleShape),
            )
        }
    }
}

@Composable
internal fun CellBadge(icon: ImageVector, modifier: Modifier = Modifier) {
    Icon(
        icon, contentDescription = null, tint = Color.White,
        modifier = modifier.size(22.dp).background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(6.dp)).padding(3.dp),
    )
}
