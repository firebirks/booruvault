package io.github.firebirks.booruvault.ui.vault

import io.github.firebirks.booruvault.ui.common.ViewerTextShadow
import io.github.firebirks.booruvault.ui.common.ViewerChromeScrim
import io.github.firebirks.booruvault.ui.common.labelRes
import io.github.firebirks.booruvault.R
import androidx.compose.ui.res.stringResource
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import io.github.firebirks.booruvault.ui.common.LocalContainer
import io.github.firebirks.booruvault.ui.common.VideoPlayer
import io.github.firebirks.booruvault.ui.common.ViewerSystemBars
import io.github.firebirks.booruvault.ui.common.containerViewModel
import io.github.firebirks.booruvault.ui.common.LocalLinkOpener
import io.github.firebirks.booruvault.ui.common.resolve
import io.github.firebirks.booruvault.util.formatBytes
import io.github.firebirks.booruvault.util.formatDate
import io.github.firebirks.booruvault.vault.SealedDataSource
import io.github.firebirks.booruvault.vault.VaultImage
import io.github.firebirks.booruvault.vault.VaultItem
import io.github.firebirks.booruvault.vault.VaultState
import coil3.compose.AsyncImage
import me.saket.telephoto.zoomable.coil3.ZoomableAsyncImage
import me.saket.telephoto.zoomable.rememberZoomableImageState

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun VaultViewer(startId: String, onClose: () -> Unit, onSearchTag: (String) -> Unit) {
    val container = LocalContainer.current
    val vm = containerViewModel { VaultViewModel(it) }
    val items = vm.items.collectAsStateWithLifecycle().value.orEmpty()
    val vaultState by container.vault.state.collectAsStateWithLifecycle()
    if (vaultState !is VaultState.Unlocked || items.isEmpty()) {
        LaunchedEffect(Unit) { onClose() }
        return
    }
    val start = remember { items.indexOfFirst { it.id == startId }.coerceAtLeast(0) }
    val pager = rememberPagerState(initialPage = start) { items.size }
    var chrome by rememberSaveable { mutableStateOf(true) }
    // The bar's height, measured: the player's sound button sits under it, whatever the font size makes of it.
    var chromeHeight by remember { mutableIntStateOf(0) }
    val soundButtonTop = if (chromeHeight > 0) with(LocalDensity.current) { chromeHeight.toDp() } + 8.dp else null
    var info by remember { mutableStateOf<VaultItem?>(null) }
    var confirmDelete by remember { mutableStateOf<VaultItem?>(null) }
    val videoSource = remember { SealedDataSource.Factory(container.vaultStore) }
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    // The vault screen's own snackbar sits under this viewer, where nothing is seen.
    LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it.resolve(context)) } }
    // The view model keeps the vault open while the picker is up, and the item it was opened for.
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        vm.pickerClosed()
        val item = vm.exportItem
        vm.exportItem = null
        if (uri != null && item != null) vm.exportOne(item, uri)
    }

    val current = items.getOrNull(pager.currentPage)
    fun close() {
        current?.let { vm.requestScroll(it.id) }
        onClose()
    }
    BackHandler { close() }
    ViewerSystemBars(hidden = !chrome)

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(
            state = pager,
            beyondViewportPageCount = 1,
            key = { items.getOrNull(it)?.id ?: it },
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            val item = items[page]
            if (item.isVideo) {
                if (page == pager.currentPage) {
                    VideoPlayer(SealedDataSource.uriFor(item.id), videoSource, Modifier.fillMaxSize(), onControlsVisible = { chrome = it }, soundButtonTop = soundButtonTop)
                } else if (item.hasThumbnail) {
                    AsyncImage(VaultImage(item.id, thumbnail = true), null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                }
            } else {
                val zoomState = rememberZoomableImageState()
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (!zoomState.isImageDisplayed) {
                        if (item.hasThumbnail) {
                            AsyncImage(VaultImage(item.id, thumbnail = true), null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                        }
                        CircularProgressIndicator(Modifier.size(36.dp), color = Color.White)
                    }
                    ZoomableAsyncImage(
                        model = VaultImage(item.id, thumbnail = false),
                        contentDescription = item.fileName,
                        state = zoomState,
                        onClick = { chrome = !chrome },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }

        AnimatedVisibility(chrome && current != null, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
            val item = current ?: return@AnimatedVisibility
            Row(
                Modifier
                    .fillMaxWidth()
                    .onSizeChanged { chromeHeight = it.height }
                    .background(ViewerChromeScrim)
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = ::close) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.action_back), tint = Color.White) }
                Column(Modifier.weight(1f)) {
                    Text(item.fileName, color = Color.White, style = MaterialTheme.typography.titleMedium.copy(shadow = ViewerTextShadow), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${pager.currentPage + 1}/${items.size}", color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.labelMedium.copy(shadow = ViewerTextShadow))
                }
                IconButton(onClick = { info = item }) { Icon(Icons.Outlined.Info, stringResource(R.string.vault_details), tint = Color.White) }
                IconButton(onClick = {
                    vm.exportItem = item
                    vm.pickerOpened()
                    exportLauncher.launch(item.fileName)
                }) { Icon(Icons.Outlined.FileDownload, stringResource(R.string.vault_export_clear), tint = Color.White) }
                IconButton(onClick = { confirmDelete = item }) { Icon(Icons.Outlined.Delete, stringResource(R.string.action_delete), tint = Color.White) }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
    }

    info?.let { item ->
        VaultInfoSheet(item, onDismiss = { info = null }, onTag = { tag ->
            info = null
            onSearchTag(tag)
        })
    }

    confirmDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(stringResource(R.string.vault_delete_item_title)) },
            text = { Text(stringResource(R.string.vault_delete_item_text, item.fileName)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = null
                    vm.delete(setOf(item.id))
                }) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun VaultInfoSheet(item: VaultItem, onDismiss: () -> Unit, onTag: (String) -> Unit) {
    val context = LocalContext.current
    val links = LocalLinkOpener.current
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(item.fileName, style = MaterialTheme.typography.titleLarge)
            val added = stringResource(R.string.vault_added_on, formatDate(item.addedAt))
            val rating = stringResource(item.rating.labelRes())
            val facts = buildList {
                if (item.width > 0) add("${item.width}×${item.height}")
                add(item.mimeType)
                add(formatBytes(context, item.size))
                add(added)
                if (item.sourceName != null) add(rating)
            }
            Text(facts.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            item.postUrl?.let { url ->
                TextButton(onClick = { links.open(url) }) {
                    Icon(Icons.Outlined.OpenInBrowser, null)
                    Text("  ${item.sourceName ?: stringResource(R.string.vault_source_fallback)} #${item.postId ?: ""}")
                }
            }
            if (item.tags.isNotEmpty()) {
                HorizontalDivider()
                Text(stringResource(R.string.vault_tags), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    item.tags.forEach { tag -> AssistChip(onClick = { onTag(tag) }, label = { Text(tag.replace('_', ' ')) }) }
                }
            }
        }
    }
}
