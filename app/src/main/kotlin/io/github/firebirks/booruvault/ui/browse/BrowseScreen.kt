package io.github.firebirks.booruvault.ui.browse

import java.util.UUID
import androidx.compose.material.icons.outlined.BookmarkAdd
import io.github.firebirks.booruvault.vault.TagCollection
import io.github.firebirks.booruvault.ui.vault.CollectionDialog
import androidx.compose.ui.platform.LocalResources
import io.github.firebirks.booruvault.ui.common.problemText
import io.github.firebirks.booruvault.R
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.itemsIndexed
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ManageSearch
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.DownloadForOffline
import androidx.compose.material.icons.outlined.Gif
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.firebirks.booruvault.booru.MediaKind
import io.github.firebirks.booruvault.booru.Post
import io.github.firebirks.booruvault.booru.TagSuggestion
import io.github.firebirks.booruvault.ui.common.CenteredProgress
import io.github.firebirks.booruvault.ui.common.LocalChallenge
import io.github.firebirks.booruvault.ui.common.browserStart
import io.github.firebirks.booruvault.ui.common.LocalContainer
import io.github.firebirks.booruvault.ui.common.MessageState
import io.github.firebirks.booruvault.ui.common.categoryColor
import io.github.firebirks.booruvault.ui.common.containerViewModel
import io.github.firebirks.booruvault.ui.common.rememberNotificationPermission
import io.github.firebirks.booruvault.util.formatCount
import io.github.firebirks.booruvault.vault.VaultState
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowseScreen(onOpenPost: (Int) -> Unit, onNeedVault: () -> Unit) {
    val container = LocalContainer.current
    val vm = containerViewModel { BrowseViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val settings by container.settings.settings.collectAsStateWithLifecycle()
    val selection by vm.selection.collectAsStateWithLifecycle()
    val suggestions by vm.suggestions.collectAsStateWithLifecycle()
    val saveStates by vm.saveStates.collectAsStateWithLifecycle()
    val vaultState by container.vault.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val askNotifications = rememberNotificationPermission()
    val gridState = rememberLazyStaggeredGridState()
    var searchFocused by remember { mutableStateOf(false) }
    var showBatch by rememberSaveable { mutableStateOf(false) }
    var showWebPage by rememberSaveable { mutableStateOf(false) }
    var saveSearch by rememberSaveable { mutableStateOf(false) }

    BackHandler(enabled = selection.isNotEmpty()) { vm.clearSelection() }
    BackHandler(enabled = searchFocused) { focus.clearFocus() }

    LaunchedEffect(state.generation) { gridState.scrollToItem(0) }
    LaunchedEffect(vm) {
        vm.scrollTo.collect { index ->
            val visible = gridState.layoutInfo.visibleItemsInfo.map { it.index }
            if (index !in visible) gridState.scrollToItem(index)
        }
    }

    val nearEnd by remember {
        derivedStateOf {
            val info = gridState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            info.totalItemsCount > 0 && last >= info.totalItemsCount - 12
        }
    }
    LaunchedEffect(nearEnd, state.posts.size) { if (nearEnd) vm.loadMore() }

    val challenge = LocalChallenge.current
    fun verify(url: String) = challenge.open(state.source, url) { vm.loadMore(force = true) }

    val resources = LocalResources.current
    fun queue(action: () -> Int) {
        if (vaultState is VaultState.Missing) {
            scope.launch {
                val result = snackbar.showSnackbar(resources.getString(R.string.browse_need_vault), actionLabel = resources.getString(R.string.browse_create))
                if (result == SnackbarResult.ActionPerformed) onNeedVault()
            }
            return
        }
        askNotifications()
        val added = action()
        val message = when {
            added < 0 -> resources.getString(R.string.browse_batch_started)
            added == 0 -> resources.getString(R.string.browse_already_queued)
            else -> resources.getQuantityString(R.plurals.browse_added, added, added)
        }
        scope.launch { snackbar.showSnackbar(message) }
    }

    Scaffold(
        topBar = {
            if (selection.isNotEmpty()) {
                TopAppBar(
                    title = { Text(pluralStringResource(R.plurals.selected_count, selection.size, selection.size)) },
                    navigationIcon = { IconButton(onClick = vm::clearSelection) { Icon(Icons.Outlined.Close, stringResource(R.string.action_cancel_selection)) } },
                    actions = {
                        IconButton(onClick = vm::selectAll) { Icon(Icons.Outlined.SelectAll, stringResource(R.string.action_select_all)) }
                        IconButton(onClick = { queue(vm::downloadSelected) }) { Icon(Icons.Outlined.Download, stringResource(R.string.action_download_to_vault)) }
                    },
                )
            } else {
                BrowseTopBar(
                    sourceName = state.source?.name ?: stringResource(R.string.browse_no_source),
                    sources = settings.sources.map { it.id to it.name },
                    selectedId = state.source?.id,
                    // A plain page has nothing to search; its address stands where the field would be.
                    pageAddress = state.source?.takeIf { !it.searchable }?.baseUrl,
                    onSelectSource = vm::selectSource,
                    // Login and site preferences without a trip through Settings; the results
                    // reload with the cookies the visit brought back.
                    onOpenSite = state.source?.let { source -> { challenge.open(source, browserStart(source), browse = true) { vm.refresh() } } },
                    input = vm.input,
                    onInput = vm::onInput,
                    onSubmit = {
                        vm.submit()
                        focus.clearFocus()
                    },
                    onClear = { vm.search("") },
                    onFocus = { searchFocused = it },
                    onRefresh = vm::refresh,
                    onDownloadAll = { showBatch = true },
                    onOpenWebPage = { showWebPage = true },
                    onCloseWebPage = if (state.source?.id == BrowseViewModel.WEB_PAGE_SOURCE) vm::closeWebPage else null,
                    onSaveSearch = { saveSearch = true },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
        contentWindowInsets = WindowInsets(0),
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = vm::refresh, modifier = Modifier.fillMaxSize()) {
                when {
                    state.source == null -> MessageState(
                        Icons.Outlined.CloudOff, stringResource(R.string.browse_no_sources_title), stringResource(R.string.browse_no_sources_text),
                    )
                    state.posts.isEmpty() && state.challengeUrl != null -> MessageState(
                        Icons.Outlined.VerifiedUser, stringResource(R.string.browse_check_needed), state.error?.let { problemText(it) },
                        actionLabel = stringResource(R.string.action_verify_in_browser), onAction = { verify(state.challengeUrl!!) },
                    )
                    state.posts.isEmpty() && state.error != null -> MessageState(
                        Icons.Outlined.CloudOff, stringResource(R.string.browse_load_failed), state.error?.let { problemText(it) },
                        actionLabel = stringResource(R.string.action_retry), onAction = { vm.loadMore(force = true) },
                    )
                    state.posts.isEmpty() && state.endReached -> MessageState(
                        Icons.Outlined.SearchOff, stringResource(R.string.browse_no_results),
                        if (state.hidden > 0) {
                            pluralStringResource(R.plurals.browse_hidden, state.hidden, state.hidden)
                        } else {
                            stringResource(R.string.browse_try_other_tags)
                        },
                    )
                    state.posts.isEmpty() -> CenteredProgress()
                    else -> LazyVerticalStaggeredGrid(
                        columns = StaggeredGridCells.Adaptive(settings.gridSize.minCellDp.dp),
                        state = gridState,
                        contentPadding = PaddingValues(6.dp),
                        verticalItemSpacing = 6.dp,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        itemsIndexed(state.posts, key = { _, post -> post.id }) { index, post ->
                            PostCell(
                                post = post,
                                selected = post.id in selection,
                                selecting = selection.isNotEmpty(),
                                saveState = saveStates["${post.sourceId}/${post.id}"] ?: SaveState.NONE,
                                onClick = {
                                    if (selection.isNotEmpty()) vm.toggleSelection(post.id) else onOpenPost(index)
                                },
                                onLongClick = { vm.startSelection(post.id) },
                            )
                        }
                        item(span = StaggeredGridItemSpan.FullLine, key = "footer") {
                            GridFooter(
                                loading = state.loading,
                                error = state.error?.let { problemText(it) },
                                endReached = state.endReached,
                                hidden = state.hidden,
                                challenge = state.challengeUrl != null,
                                onRetry = { state.challengeUrl?.let(::verify) ?: vm.loadMore(force = true) },
                            )
                        }
                    }
                }
            }
            if (searchFocused && suggestions.isNotEmpty()) {
                SuggestionList(suggestions, onPick = vm::applySuggestion)
            }
        }
    }

    if (saveSearch) {
        CollectionDialog(
            initial = null,
            prefillQuery = vm.input.text,
            onDismiss = { saveSearch = false },
            onSave = { name, query ->
                saveSearch = false
                scope.launch {
                    container.settings.saveCollection(TagCollection(UUID.randomUUID().toString(), name, query))
                    snackbar.showSnackbar(resources.getString(R.string.collection_saved, name))
                }
            },
        )
    }

    if (showWebPage) {
        var address by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showWebPage = false },
            icon = { Icon(Icons.Outlined.Language, null) },
            title = { Text(stringResource(R.string.browse_open_web_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.browse_open_web_text))
                    androidx.compose.material3.OutlinedTextField(
                        value = address,
                        onValueChange = { address = it },
                        label = { Text(stringResource(R.string.browse_address)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri, autoCorrectEnabled = false),
                    )
                }
            },
            confirmButton = {
                TextButton(enabled = address.isNotBlank(), onClick = {
                    showWebPage = false
                    vm.openWebPage(address)
                }) { Text(stringResource(R.string.action_open)) }
            },
            dismissButton = { TextButton(onClick = { showWebPage = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    if (showBatch) {
        BatchDialog(
            query = state.query,
            sourceName = state.source?.name.orEmpty(),
            initial = settings.batchMax,
            onDismiss = { showBatch = false },
            onConfirm = { max ->
                showBatch = false
                scope.launch { container.settings.setBatchMax(max) }
                queue {
                    vm.downloadAll(max)
                    -1
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BrowseTopBar(
    sourceName: String,
    sources: List<Pair<String, String>>,
    selectedId: String?,
    pageAddress: String?,
    onSelectSource: (String) -> Unit,
    onOpenSite: (() -> Unit)?,
    input: androidx.compose.ui.text.input.TextFieldValue,
    onInput: (androidx.compose.ui.text.input.TextFieldValue) -> Unit,
    onSubmit: () -> Unit,
    onClear: () -> Unit,
    onFocus: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onDownloadAll: () -> Unit,
    onOpenWebPage: () -> Unit,
    /** Non-null while a page opened on the spot is being browsed. */
    onCloseWebPage: (() -> Unit)?,
    onSaveSearch: () -> Unit,
) {
    var sourceMenu by remember { mutableStateOf(false) }
    var overflow by remember { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
        Column(Modifier.windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = 12.dp).padding(bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box {
                    TextButton(onClick = { sourceMenu = true }) {
                        Text(sourceName, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Icon(Icons.Outlined.ArrowDropDown, contentDescription = stringResource(R.string.browse_change_source))
                    }
                    DropdownMenu(expanded = sourceMenu, onDismissRequest = { sourceMenu = false }) {
                        sources.forEach { (id, name) ->
                            DropdownMenuItem(
                                text = { Text(name) },
                                onClick = {
                                    sourceMenu = false
                                    onSelectSource(id)
                                },
                                trailingIcon = { if (id == selectedId) Icon(Icons.Outlined.Check, null) },
                            )
                        }
                    }
                }
                if (onOpenSite != null) {
                    IconButton(onClick = onOpenSite) { Icon(Icons.Outlined.OpenInBrowser, stringResource(R.string.source_open_browser)) }
                }
                Spacer(Modifier.weight(1f))
                Box {
                    IconButton(onClick = { overflow = true }) { Icon(Icons.Outlined.MoreVert, stringResource(R.string.action_more)) }
                    DropdownMenu(expanded = overflow, onDismissRequest = { overflow = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.browse_download_all)) },
                            leadingIcon = { Icon(Icons.Outlined.DownloadForOffline, null) },
                            onClick = {
                                overflow = false
                                onDownloadAll()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.browse_save_search)) },
                            leadingIcon = { Icon(Icons.Outlined.BookmarkAdd, null) },
                            enabled = input.text.isNotBlank(),
                            onClick = {
                                overflow = false
                                onSaveSearch()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.browse_open_web)) },
                            leadingIcon = { Icon(Icons.Outlined.Language, null) },
                            onClick = {
                                overflow = false
                                onOpenWebPage()
                            },
                        )
                        if (onCloseWebPage != null) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.browse_close_web)) },
                                leadingIcon = { Icon(Icons.Outlined.Close, null) },
                                onClick = {
                                    overflow = false
                                    onCloseWebPage()
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.browse_refresh)) },
                            leadingIcon = { Icon(Icons.Outlined.Refresh, null) },
                            onClick = {
                                overflow = false
                                onRefresh()
                            },
                        )
                    }
                }
            }
            if (pageAddress != null) {
                Text(
                    pageAddress,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
                return@Column
            }
            TextField(
                value = input,
                onValueChange = onInput,
                placeholder = { Text(stringResource(R.string.browse_search_hint)) },
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                trailingIcon = {
                    if (input.text.isNotEmpty()) IconButton(onClick = onClear) { Icon(Icons.Outlined.Close, stringResource(R.string.action_clear)) }
                },
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
                modifier = Modifier.fillMaxWidth().onFocusChanged { onFocus(it.isFocused) },
            )
        }
    }
}

@Composable
private fun SuggestionList(suggestions: List<TagSuggestion>, onPick: (TagSuggestion) -> Unit) {
    ElevatedCard(Modifier.padding(horizontal = 12.dp).fillMaxWidth().heightIn(max = 320.dp)) {
        LazyColumn {
            items(suggestions, key = { it.name }) { suggestion ->
                Row(
                    Modifier.fillMaxWidth().clickable { onPick(suggestion) }.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(categoryColor(suggestion.category)))
                    Spacer(Modifier.size(12.dp))
                    Text(
                        suggestion.name, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = categoryColor(suggestion.category).takeIf { suggestion.category != io.github.firebirks.booruvault.booru.TagCategory.GENERAL }
                            ?: MaterialTheme.colorScheme.onSurface,
                    )
                    suggestion.count?.let {
                        Text(formatCount(it), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                HorizontalDivider()
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PostCell(
    post: Post,
    selected: Boolean,
    selecting: Boolean,
    saveState: SaveState,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(post.aspectRatio)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .then(if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, shape) else Modifier)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        AsyncImage(
            model = post.previewUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        if (post.kind != MediaKind.IMAGE) {
            Badge(
                icon = if (post.kind == MediaKind.VIDEO) Icons.Outlined.PlayArrow else Icons.Outlined.Gif,
                modifier = Modifier.align(Alignment.TopStart).padding(6.dp),
            )
        }
        when (saveState) {
            SaveState.SAVED -> Badge(Icons.Outlined.Lock, Modifier.align(Alignment.BottomEnd).padding(6.dp))
            SaveState.QUEUED -> Badge(Icons.Outlined.Schedule, Modifier.align(Alignment.BottomEnd).padding(6.dp))
            SaveState.NONE -> Unit
        }
        if (selecting) {
            Box(Modifier.fillMaxSize().background(if (selected) Color.Black.copy(alpha = 0.25f) else Color.Transparent))
            Icon(
                if (selected) Icons.Outlined.CheckCircle else Icons.Outlined.Check,
                contentDescription = null,
                tint = if (selected) MaterialTheme.colorScheme.primary else Color.White,
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(26.dp)
                    .background(if (selected) Color.White else Color.Black.copy(alpha = 0.35f), CircleShape).padding(2.dp),
            )
        }
    }
}

@Composable
private fun Badge(icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier = Modifier) {
    Icon(
        icon, contentDescription = null, tint = Color.White,
        modifier = modifier.size(22.dp).background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(6.dp)).padding(3.dp),
    )
}

@Composable
private fun GridFooter(loading: Boolean, error: String?, endReached: Boolean, hidden: Int, challenge: Boolean, onRetry: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
        when {
            loading -> CircularProgressIndicator(Modifier.size(28.dp))
            error != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onRetry) { Text(stringResource(if (challenge) R.string.action_verify_in_browser else R.string.action_retry)) }
            }
            endReached -> Text(
                stringResource(R.string.browse_end) + if (hidden > 0) " · " + pluralStringResource(R.plurals.browse_end_hidden, hidden, hidden) else "",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BatchDialog(query: String, sourceName: String, initial: Int, onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    val options = listOf(50, 100, 250, 500, 1000)
    var max by remember { mutableIntStateOf(if (initial in options) initial else 100) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.AutoMirrored.Outlined.ManageSearch, null) },
        title = { Text(stringResource(R.string.batch_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.batch_dialog_text, query.ifBlank { stringResource(R.string.batch_all_posts) }, sourceName))
                Text(stringResource(R.string.batch_max), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    options.forEach { n ->
                        FilterChip(selected = max == n, onClick = { max = n }, label = { Text("$n") })
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(max) }) { Text(stringResource(R.string.batch_download), fontWeight = FontWeight.SemiBold) } },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
