package io.github.firebirks.booruvault.ui.browse

import java.util.UUID
import io.github.firebirks.booruvault.vault.TagCollection
import io.github.firebirks.booruvault.ui.vault.CollectionDialog
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.ui.platform.LocalResources
import io.github.firebirks.booruvault.util.Problem
import io.github.firebirks.booruvault.ui.common.problemText
import io.github.firebirks.booruvault.ui.common.labelRes
import io.github.firebirks.booruvault.R
import androidx.compose.ui.res.stringResource
import android.content.ClipData
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import io.github.firebirks.booruvault.booru.MediaKind
import io.github.firebirks.booruvault.booru.Post
import io.github.firebirks.booruvault.booru.Tag
import io.github.firebirks.booruvault.booru.TagCategory
import io.github.firebirks.booruvault.booru.USER_AGENT
import io.github.firebirks.booruvault.ui.common.LocalContainer
import io.github.firebirks.booruvault.ui.common.VideoPlayer
import io.github.firebirks.booruvault.ui.common.ViewerSystemBars
import io.github.firebirks.booruvault.ui.common.categoryColor
import io.github.firebirks.booruvault.ui.common.containerViewModel
import io.github.firebirks.booruvault.ui.common.openUrl
import io.github.firebirks.booruvault.ui.common.rememberNotificationPermission
import io.github.firebirks.booruvault.util.formatBytes
import io.github.firebirks.booruvault.util.formatDate
import io.github.firebirks.booruvault.vault.VaultState
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import me.saket.telephoto.zoomable.coil3.ZoomableAsyncImage
import me.saket.telephoto.zoomable.rememberZoomableImageState

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun PostViewer(startIndex: Int, onClose: () -> Unit, onSearchTag: (String) -> Unit, onNeedVault: () -> Unit) {
    val container = LocalContainer.current
    val context = LocalContext.current
    val vm = containerViewModel { BrowseViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val settings by container.settings.settings.collectAsStateWithLifecycle()
    val saveStates by vm.saveStates.collectAsStateWithLifecycle()
    val resolveErrors by vm.resolveErrors.collectAsStateWithLifecycle()
    val vaultState by container.vault.state.collectAsStateWithLifecycle()
    val askNotifications = rememberNotificationPermission()
    val resources = LocalResources.current
    val posts = state.posts
    if (posts.isEmpty()) {
        LaunchedEffect(Unit) { onClose() }
        return
    }
    val pager = rememberPagerState(initialPage = startIndex.coerceIn(0, posts.lastIndex)) { posts.size }
    var chrome by rememberSaveable { mutableStateOf(true) }
    var info by remember { mutableStateOf<Post?>(null) }
    var collectionFromTag by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val videoSource = remember { OkHttpDataSource.Factory(container.http).setUserAgent(USER_AGENT) }

    fun close() {
        vm.requestScroll(pager.currentPage)
        onClose()
    }
    BackHandler { close() }
    ViewerSystemBars(hidden = !chrome)
    LaunchedEffect(pager.currentPage, posts.size) {
        if (pager.currentPage >= posts.size - 5) vm.loadMore()
    }

    val current = posts.getOrNull(pager.currentPage)
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(
            state = pager,
            beyondViewportPageCount = 1,
            key = { posts.getOrNull(it)?.id ?: it },
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            val post = posts[page]
            val url = post.viewUrl(settings.viewerOriginal)
            if (post.needsResolve) {
                // This and the neighbouring pages fetch their post page while being looked at.
                LaunchedEffect(post.id) { vm.resolve(post) }
                PendingPost(post, resolveErrors[post.id], onRetry = { vm.resolve(post) })
            } else if (post.kind == MediaKind.VIDEO && url != null) {
                if (page == pager.currentPage) {
                    VideoPlayer(Uri.parse(url), videoSource, Modifier.fillMaxSize())
                } else {
                    AsyncImage(post.previewUrl, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                }
            } else {
                ZoomablePostImage(post, url, onTap = { chrome = !chrome })
            }
        }

        AnimatedVisibility(chrome && current != null, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
            val post = current ?: return@AnimatedVisibility
            val saveState = saveStates["${post.sourceId}/${post.id}"] ?: SaveState.NONE
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent)))
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = ::close) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.action_back), tint = Color.White) }
                Column(Modifier.weight(1f)) {
                    Text("#${post.id}", color = Color.White, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${state.source?.name.orEmpty()} · ${pager.currentPage + 1}/${posts.size}",
                        color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.labelMedium,
                    )
                }
                IconButton(onClick = { context.openUrl(post.postUrl) }) { Icon(Icons.Outlined.OpenInBrowser, stringResource(R.string.viewer_open_browser), tint = Color.White) }
                IconButton(onClick = { info = post }) { Icon(Icons.Outlined.Info, stringResource(R.string.viewer_details), tint = Color.White) }
                val alreadySaved = stringResource(R.string.problem_already_in_vault)
                when (saveState) {
                    SaveState.SAVED -> IconButton(onClick = { Toast.makeText(context, alreadySaved, Toast.LENGTH_SHORT).show() }) {
                        Icon(Icons.Outlined.Lock, stringResource(R.string.viewer_in_vault), tint = MaterialTheme.colorScheme.primaryContainer)
                    }
                    SaveState.QUEUED -> Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.Schedule, stringResource(R.string.viewer_downloading), tint = Color.White)
                    }
                    SaveState.NONE -> IconButton(onClick = {
                        if (vaultState is VaultState.Missing) {
                            onNeedVault()
                        } else {
                            askNotifications()
                            vm.download(listOf(post))
                        }
                    }) { Icon(Icons.Outlined.Download, stringResource(R.string.action_download_to_vault), tint = Color.White) }
                }
            }
        }
    }

    info?.let { post ->
        PostInfoSheet(
            post = post,
            sourceName = state.source?.name.orEmpty(),
            onDismiss = { info = null },
            onSearch = { tag ->
                info = null
                onSearchTag(tag)
            },
            onAddToSearch = { tag ->
                info = null
                onClose()
                vm.addToSearch(tag)
            },
            onExclude = { tag ->
                info = null
                onClose()
                vm.excludeFromSearch(tag)
            },
            onCreateCollection = { tag ->
                info = null
                collectionFromTag = tag
            },
            onBlacklist = { tag ->
                vm.blacklist(tag)
                Toast.makeText(context, resources.getString(R.string.viewer_blacklisted, tag), Toast.LENGTH_SHORT).show()
            },
        )
    }

    collectionFromTag?.let { tag ->
        CollectionDialog(
            initial = null,
            prefillQuery = tag,
            onDismiss = { collectionFromTag = null },
            onSave = { name, query ->
                collectionFromTag = null
                scope.launch {
                    container.settings.saveCollection(TagCollection(UUID.randomUUID().toString(), name, query))
                    Toast.makeText(context, resources.getString(R.string.collection_saved, name), Toast.LENGTH_SHORT).show()
                }
            },
        )
    }
}

@Composable
private fun PendingPost(post: Post, error: Problem?, onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AsyncImage(post.previewUrl, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        if (error == null) {
            CircularProgressIndicator(Modifier.size(36.dp), color = Color.White)
        } else {
            Column(
                Modifier.background(Color.Black.copy(alpha = 0.7f)).padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(problemText(error), color = Color.White, style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
            }
        }
    }
}

@Composable
private fun ZoomablePostImage(post: Post, url: String?, onTap: () -> Unit) {
    val context = LocalContext.current
    val zoomState = rememberZoomableImageState()
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (!zoomState.isImageDisplayed) {
            AsyncImage(post.previewUrl, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            CircularProgressIndicator(Modifier.size(36.dp), color = Color.White)
        }
        ZoomableAsyncImage(
            model = remember(url) { ImageRequest.Builder(context).data(url).build() },
            contentDescription = post.tagNames.take(8).joinToString(" "),
            state = zoomState,
            onClick = { onTap() },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun PostInfoSheet(
    post: Post,
    sourceName: String,
    onDismiss: () -> Unit,
    onSearch: (String) -> Unit,
    onAddToSearch: (String) -> Unit,
    onExclude: (String) -> Unit,
    onCreateCollection: (String) -> Unit,
    onBlacklist: (String) -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("$sourceName #${post.id}", style = MaterialTheme.typography.titleLarge)
            val rating = stringResource(post.rating.labelRes())
            val score = post.score?.let { stringResource(R.string.viewer_score, it) }
            val facts = buildList {
                if (post.width > 0) add("${post.width}×${post.height}")
                add(post.fileExt.uppercase())
                post.fileSize?.let { add(formatBytes(context, it)) }
                add(rating)
                score?.let(::add)
                post.createdAt?.let { add(formatDate(it)) }
            }
            Text(facts.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            post.source?.let { source ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.viewer_source), style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { if (source.startsWith("http")) context.openUrl(source) }) {
                        Text(source, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            HorizontalDivider()
            val groups = post.tags.groupBy { it.category }
            TagCategory.entries.forEach { category ->
                val tags = groups[category] ?: return@forEach
                Text(stringResource(category.labelRes()), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    tags.forEach { tag ->
                        TagChip(
                            tag = tag,
                            onSearch = onSearch,
                            onAddToSearch = onAddToSearch,
                            onExclude = onExclude,
                            onCreateCollection = onCreateCollection,
                            onBlacklist = onBlacklist,
                            onCopy = { text ->
                                val clip = ClipData.newPlainText("tag", text).apply {
                                    description.extras = android.os.PersistableBundle().apply {
                                        putBoolean("android.content.extra.IS_SENSITIVE", true)
                                    }
                                }
                                scope.launch { clipboard.setClipEntry(ClipEntry(clip)) }
                            },
                        )
                    }
                }
            }
            Spacer(Modifier.size(8.dp))
        }
    }
}

@Composable
private fun TagChip(
    tag: Tag,
    onSearch: (String) -> Unit,
    onAddToSearch: (String) -> Unit,
    onExclude: (String) -> Unit,
    onCreateCollection: (String) -> Unit,
    onBlacklist: (String) -> Unit,
    onCopy: (String) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Box {
        AssistChip(
            onClick = { menu = true },
            label = { Text(tag.name.replace('_', ' '), color = categoryColor(tag.category)) },
            colors = AssistChipDefaults.assistChipColors(),
        )
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.tag_search)) }, leadingIcon = { Icon(Icons.Outlined.Search, null) }, onClick = {
                menu = false
                onSearch(tag.name)
            })
            DropdownMenuItem(text = { Text(stringResource(R.string.tag_add_to_search)) }, leadingIcon = { Icon(Icons.Outlined.AddCircleOutline, null) }, onClick = {
                menu = false
                onAddToSearch(tag.name)
            })
            DropdownMenuItem(text = { Text(stringResource(R.string.tag_exclude)) }, leadingIcon = { Icon(Icons.Outlined.RemoveCircleOutline, null) }, onClick = {
                menu = false
                onExclude(tag.name)
            })
            DropdownMenuItem(text = { Text(stringResource(R.string.tag_create_collection)) }, leadingIcon = { Icon(Icons.Outlined.BookmarkAdd, null) }, onClick = {
                menu = false
                onCreateCollection(tag.name)
            })
            DropdownMenuItem(text = { Text(stringResource(R.string.tag_blacklist)) }, leadingIcon = { Icon(Icons.Outlined.Block, null) }, onClick = {
                menu = false
                onBlacklist(tag.name)
            })
            DropdownMenuItem(text = { Text(stringResource(R.string.tag_copy)) }, leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) }, onClick = {
                menu = false
                onCopy(tag.name)
            })
        }
    }
}
