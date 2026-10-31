package io.github.firebirks.booruvault.ui.browse

import io.github.firebirks.booruvault.util.toProblem
import io.github.firebirks.booruvault.util.Problem
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.firebirks.booruvault.AppContainer
import io.github.firebirks.booruvault.booru.BooruChallengeException
import io.github.firebirks.booruvault.booru.BooruEngine
import io.github.firebirks.booruvault.booru.BooruSource
import io.github.firebirks.booruvault.booru.Post
import io.github.firebirks.booruvault.booru.TagSuggestion
import io.github.firebirks.booruvault.booru.autocompleteToken
import io.github.firebirks.booruvault.download.DownloadStatus
import io.github.firebirks.booruvault.settings.allows
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class BrowseState(
    val source: BooruSource? = null,
    /** The search that produced [posts]; the text field may already say something else. */
    val query: String = "",
    val posts: List<Post> = emptyList(),
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val endReached: Boolean = false,
    val error: Problem? = null,
    /** Set when [error] is an anti-bot check the user can pass in the built-in browser. */
    val challengeUrl: String? = null,
    /** Posts dropped by the rating filter or the blacklist. */
    val hidden: Int = 0,
    /** Bumped on every new search so the grid can jump back to the top. */
    val generation: Int = 0,
    /** Where the grid of this [generation] opens: the top, or where Back found it left. */
    val startIndex: Int = 0,
    val startOffset: Int = 0,
)

/** The same source, or the same page opened on the spot: those all share one id. */
private fun BooruSource?.sameAs(other: BooruSource?): Boolean =
    if (this?.id == BrowseViewModel.WEB_PAGE_SOURCE) this == other else this?.id == other?.id

/** A grid as it was left: its results, how far they were read, and where it was scrolled to. */
private class LeftGrid(val state: BrowseState, val page: Int, val seen: Set<Long>, val index: Int, val offset: Int, val filters: Any)

/** Where a post stands with respect to the vault. */
enum class SaveState { NONE, QUEUED, SAVED }

/** A tag offered under the search field: starred by the user, or among the source's most searched. */
data class ShelfTag(val name: String, val favourite: Boolean)

/**
 * Kept while a screen shows it and for a moment after, then dropped. The view model outlives a
 * lock while the screen is open, and a value nothing collects is not recomputed when the vault
 * locks, so it must not be held on to.
 */
internal fun whileShown(stopTimeoutMillis: Long = 5000): SharingStarted =
    SharingStarted.WhileSubscribed(stopTimeoutMillis, replayExpirationMillis = 0)

class BrowseViewModel(private val c: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(BrowseState())
    val state: StateFlow<BrowseState> = _state.asStateFlow()

    var input by mutableStateOf(TextFieldValue(""))
        private set

    private val _suggestions = MutableStateFlow<List<TagSuggestion>>(emptyList())
    val suggestions: StateFlow<List<TagSuggestion>> = _suggestions.asStateFlow()

    private val _selection = MutableStateFlow<Set<Long>>(emptySet())
    val selection: StateFlow<Set<Long>> = _selection.asStateFlow()

    /** Asks the grid to bring a post into view, e.g. the one a viewer was closed on. */
    private val _scrollTo = MutableSharedFlow<Int>(extraBufferCapacity = 1)
    val scrollTo: SharedFlow<Int> = _scrollTo

    /** The current source's starred tags, then its most searched ones not already starred. */
    val tagShelf: StateFlow<List<ShelfTag>> = combine(_state.map { it.source?.id }.distinctUntilChanged(), c.settings.settings) { sourceId, settings ->
        if (sourceId == null) return@combine emptyList()
        val favourites = settings.favouriteTags[sourceId].orEmpty()
        val used = settings.tagUses[sourceId].orEmpty().entries
            .filter { it.key !in favourites }.sortedByDescending { it.value }.take(MAX_SHELF_USED).map { it.key }
        favourites.map { ShelfTag(it, favourite = true) } + used.map { ShelfTag(it, favourite = false) }
    }.stateIn(viewModelScope, whileShown(), emptyList())

    /** "sourceId/postId" → whether it is in the vault or on its way there. */
    val saveStates: StateFlow<Map<String, SaveState>> = combine(c.vaultStore.items, c.downloads.tasks) { items, tasks ->
        buildMap {
            tasks.forEach { task ->
                val state = when (task.status) {
                    DownloadStatus.QUEUED, DownloadStatus.RUNNING -> SaveState.QUEUED
                    DownloadStatus.DONE, DownloadStatus.SKIPPED -> SaveState.SAVED
                    else -> null
                }
                if (state != null) put("${task.source.id}/${task.post.id}", state)
            }
            items.forEach { item -> if (item.sourceId != null && item.postId != null) put("${item.sourceId}/${item.postId}", SaveState.SAVED) }
        }
    }.stateIn(viewModelScope, whileShown(), emptyMap())

    /** A web page opened on the spot: browsed like a source, never saved among the sources. */
    private val webPage = MutableStateFlow<BooruSource?>(null)

    private val _resolveErrors = MutableStateFlow<Map<Long, Problem>>(emptyMap())

    /** Posts from HTML lists whose page could not be read, with the reason. */
    val resolveErrors: StateFlow<Map<Long, Problem>> = _resolveErrors.asStateFlow()
    private val resolving = HashSet<Long>()
    private val resolveJobs = HashMap<Long, Job>()

    private var page = 0
    private val seen = HashSet<Long>()
    private var loadJob: Job? = null
    private var suggestJob: Job? = null

    private val history = BrowseHistory<LeftGrid>()

    /** Whether Back has a grid to return to. */
    val canGoBack: Boolean get() = history.canGoBack

    /** The grid Back is returning to, shown once its source is the one selected. */
    private var returning: LeftGrid? = null

    /** The first item the grid shows, and how far it is scrolled past. */
    private var position = 0 to 0

    init {
        viewModelScope.launch {
            // Settings exist only while the vault is open; so does everything browsed with them.
            c.settings.ready.collectLatest { ready ->
                if (!ready) {
                    forget()
                    return@collectLatest
                }
                // Early builds saved the opened page as a source; it no longer belongs there.
                val sources = c.settings.settings.value.sources
                if (sources.any { it.id == WEB_PAGE_SOURCE }) c.settings.setSources(sources.filterNot { it.id == WEB_PAGE_SOURCE })
                coroutineScope {
                    launch {
                        var selectedId: String? = null
                        combine(c.settings.settings.map { it.selectedSource }, webPage) { selected, page -> page ?: selected }
                            .distinctUntilChanged().collect { source ->
                                val back = returning?.takeIf { it.state.source.sameAs(source) }
                                returning = null
                                if (back != null) return@collect show(back, source)
                                // Another source chosen: what Back kept was found on a different site.
                                if (source?.id != WEB_PAGE_SOURCE && source?.id != selectedId) {
                                    history.clear()
                                    selectedId = source?.id
                                }
                                _state.update { it.copy(source = source) }
                                restart(refreshing = false)
                            }
                    }
                    launch {
                        // Changing what is allowed re-runs the search rather than filtering what is
                        // loaded, so loosening the filter brings back posts that were dropped.
                        var previous: Any? = null
                        c.settings.settings.map { Triple(it.allowedRatings, it.blacklist, it.pageSize) }.distinctUntilChanged().collect { key ->
                            if (previous != null && previous != key) restart(refreshing = false)
                            previous = key
                        }
                    }
                }
            }
        }
    }

    /** Drops the search, its results and the typed text. */
    private fun forget() {
        loadJob?.cancel()
        suggestJob?.cancel()
        page = 0
        seen.clear()
        input = TextFieldValue("")
        _suggestions.value = emptyList()
        _selection.value = emptySet()
        cancelResolves()
        _resolveErrors.value = emptyMap()
        webPage.value = null
        history.clear()
        returning = null
        position = 0 to 0
        _state.value = BrowseState(generation = _state.value.generation + 1)
    }

    fun loadMore(force: Boolean = false) {
        val current = _state.value
        if (current.loading || current.endReached || current.source == null) return
        if (current.error != null && !force) return
        loadJob = viewModelScope.launch { fetchNext() }
    }

    private suspend fun fetchNext() {
        val source = _state.value.source ?: return
        // A restart bumps the generation; a fetch from an older one must not touch the new state.
        val generation = _state.value.generation
        fun update(transform: (BrowseState) -> BrowseState) =
            _state.update { if (it.generation == generation) transform(it) else it }
        update { it.copy(loading = true, error = null, challengeUrl = null) }
        try {
            val api = c.api(source)
            val settings = c.settings.settings.value
            var emptyPages = 0
            while (true) {
                val next = page + 1
                val raw = api.posts(_state.value.query, next, settings.pageSize)
                if (_state.value.generation != generation) return
                page = next
                val fresh = raw.filter { seen.add(it.id) }
                val visible = fresh.filter { settings.allows(it) }
                update {
                    it.copy(posts = it.posts + visible, hidden = it.hidden + fresh.size - visible.size, endReached = raw.isEmpty())
                }
                // A page the filters emptied entirely would leave nothing to scroll to; fetch on.
                if (raw.isEmpty() || visible.isNotEmpty() || ++emptyPages >= 5) break
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            update { it.copy(error = e.toProblem(), challengeUrl = (e as? BooruChallengeException)?.url) }
        } finally {
            update { it.copy(loading = false, refreshing = false) }
        }
    }

    private fun restart(refreshing: Boolean) {
        loadJob?.cancel()
        cancelResolves()
        page = 0
        seen.clear()
        _selection.value = emptySet()
        _state.update {
            it.copy(
                posts = emptyList(), loading = false, refreshing = refreshing, endReached = false,
                error = null, challengeUrl = null, hidden = 0, generation = it.generation + 1,
                startIndex = 0, startOffset = 0,
            )
        }
        loadMore()
    }

    private fun filters(): Any = c.settings.settings.value.let { Triple(it.allowedRatings, it.blacklist, it.pageSize) }

    /** Keeps the grid shown, for Back to return to. */
    private fun leave(beforeWebPage: Boolean = false) {
        val current = _state.value
        if (current.source == null) return
        val (index, offset) = position
        history.push(LeftGrid(current, page, seen.toSet(), index, offset, filters()), beforeWebPage)
    }

    /** Back to the grid the last search, or page opened on the spot, replaced. */
    fun back() {
        history.pop()?.let(::returnTo)
    }

    private fun returnTo(grid: LeftGrid) {
        val target = grid.state.source
        val current = _state.value.source
        if (target.sameAs(current)) return show(grid, current)
        // Another source: shown once the switch to it comes through.
        returning = grid
        webPage.value = target?.takeIf { it.id == WEB_PAGE_SOURCE }
    }

    /** Shows a grid as it was left; one found under other filters, or with nothing yet, is searched again. */
    private fun show(grid: LeftGrid, source: BooruSource?) {
        val query = grid.state.query
        input = TextFieldValue(query, TextRange(query.length))
        _suggestions.value = emptyList()
        if (grid.state.posts.isEmpty() || grid.filters != filters()) {
            _state.update { it.copy(source = source, query = query) }
            restart(refreshing = false)
            return
        }
        loadJob?.cancel()
        cancelResolves()
        page = grid.page
        seen.clear()
        seen.addAll(grid.seen)
        _selection.value = emptySet()
        position = grid.index to grid.offset
        _state.update {
            grid.state.copy(
                source = source, loading = false, refreshing = false, generation = it.generation + 1,
                startIndex = grid.index, startOffset = grid.offset,
            )
        }
    }

    /** Where the grid is scrolled to, kept with it when a search replaces it. */
    fun scrolled(index: Int, offset: Int) {
        position = index to offset
    }

    fun refresh() = restart(refreshing = true)

    /** Reads the post's own page for its file, once; the post is replaced in the list. */
    fun resolve(post: Post) {
        if (!post.needsResolve || !resolving.add(post.id)) return
        val source = _state.value.source ?: return
        val generation = _state.value.generation
        _resolveErrors.update { it - post.id }
        resolveJobs[post.id] = viewModelScope.launch {
            try {
                val resolved = c.api(source).resolve(post)
                _state.update { s ->
                    if (s.generation != generation) s else s.copy(posts = s.posts.map { if (it.id == post.id) resolved else it })
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _resolveErrors.update { it + (post.id to e.toProblem()) }
            } finally {
                resolving.remove(post.id)
                resolveJobs.remove(post.id)
            }
        }
    }

    /** A new search or a lock: the pages still being read belong to the old one. */
    private fun cancelResolves() {
        resolveJobs.values.toList().forEach { it.cancel() }
        resolveJobs.clear()
        resolving.clear()
    }

    /** Shows the images of any page, as a source of the moment that is not saved. */
    fun openWebPage(address: String) {
        val url = address.trim().let { if (it.startsWith("http://") || it.startsWith("https://")) it else "https://$it" }
        val host = url.substringAfter("://").substringBefore('/').removePrefix("www.")
        val source = BooruSource(WEB_PAGE_SOURCE, host, BooruEngine.HTML_GENERIC, url)
        input = TextFieldValue("")
        _suggestions.value = emptyList()
        // The same page again: the collector sees no change, so reload it here.
        if (webPage.value == source) {
            _state.update { it.copy(query = "") }
            return refresh()
        }
        // Closing the page returns to the source it was opened from, past any other page opened from it.
        leave(beforeWebPage = _state.value.source?.id != WEB_PAGE_SOURCE)
        _state.update { it.copy(query = "") }
        webPage.value = source
    }

    companion object {
        const val WEB_PAGE_SOURCE = "web-page"

        /** How many most-used tags join the favourites on the shelf. */
        const val MAX_SHELF_USED = 10
    }

    fun search(query: String) {
        val normalized = query.trim().replace(Regex("\\s+"), " ")
        input = TextFieldValue(normalized, TextRange(normalized.length))
        _suggestions.value = emptyList()
        // The same search again only reloads it: there is nothing to go back to.
        if (normalized != _state.value.query) leave()
        _state.update { it.copy(query = normalized) }
        rememberTags(normalized)
        restart(refreshing = false)
    }

    /** Counts the plain tags of a search for the source, so the most used can be offered later. */
    private fun rememberTags(query: String) {
        val source = _state.value.source?.takeIf { it.id != WEB_PAGE_SOURCE } ?: return
        val tags = query.split(' ').map { it.trimStart('-', '~') }.filter { it.isNotEmpty() && ':' !in it && '*' !in it }
        if (tags.isNotEmpty()) viewModelScope.launch { c.settings.recordTagUses(source.id, tags) }
    }

    fun isFavourite(tag: String): Boolean {
        val sourceId = _state.value.source?.id ?: return false
        return tag in c.settings.settings.value.favouriteTags[sourceId].orEmpty()
    }

    fun toggleFavourite(tag: String) {
        val source = _state.value.source?.takeIf { it.id != WEB_PAGE_SOURCE } ?: return
        viewModelScope.launch { c.settings.toggleFavouriteTag(source.id, tag) }
    }

    fun submit() = search(input.text)

    fun selectSource(id: String) {
        webPage.value = null
        viewModelScope.launch { c.settings.selectSource(id) }
    }

    /** Back to the selected source from a page opened on the spot, as it was before the page opened. */
    fun closeWebPage() {
        val before = history.popToBeforeWebPage()
        if (before != null) returnTo(before) else webPage.value = null
    }

    fun onInput(value: TextFieldValue) {
        val changed = value.text != input.text
        input = value
        if (changed) suggest(value.text)
    }

    fun dismissSuggestions() {
        suggestJob?.cancel()
        _suggestions.value = emptyList()
    }

    private fun suggest(text: String) {
        suggestJob?.cancel()
        val source = _state.value.source
        val (_, token) = autocompleteToken(text)
        if (source == null || text.endsWith(' ') || token.length < 2 || ':' in token) {
            _suggestions.value = emptyList()
            return
        }
        suggestJob = viewModelScope.launch {
            delay(300)
            _suggestions.value = try {
                // A site may list a tag twice (as itself and as an alias); the list is keyed by name.
                c.api(source).autocomplete(token).distinctBy { it.name }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
        }
    }

    fun applySuggestion(suggestion: TagSuggestion) = insertTag(suggestion.name)

    /** Puts [tag] in place of the word being typed, or after the last one, ready for the next. */
    fun insertTag(tag: String) {
        val text = input.text
        val head = if (text.endsWith(' ')) text.trimEnd() else text.substringBeforeLast(' ', "")
        val (prefix, _) = if (text.endsWith(' ')) "" to "" else autocompleteToken(text)
        val replaced = (if (head.isEmpty()) "" else "$head ") + prefix + tag + " "
        input = TextFieldValue(replaced, TextRange(replaced.length))
        _suggestions.value = emptyList()
    }

    // Tag actions offered by the viewer's info sheet.
    fun addToSearch(tag: String) = search("${_state.value.query} $tag")
    fun excludeFromSearch(tag: String) = search("${_state.value.query} -$tag")
    fun blacklist(tag: String) {
        viewModelScope.launch { c.settings.addToBlacklist(tag) }
    }

    fun toggleSelection(id: Long) = _selection.update { if (id in it) it - id else it + id }
    fun startSelection(id: Long) = _selection.update { it + id }
    fun selectAll() = _selection.update { _state.value.posts.mapTo(HashSet()) { it.id } }
    fun clearSelection() {
        _selection.value = emptySet()
    }

    /** Brings a post into view, and makes it where the grid is left if a search replaces it now. */
    fun requestScroll(index: Int) {
        position = index to 0
        _scrollTo.tryEmit(index)
    }

    /** Queues posts for the vault. Returns how many were new. */
    fun download(posts: List<Post>): Int {
        val source = _state.value.source ?: return 0
        return c.downloads.enqueue(posts, source)
    }

    fun downloadSelected(): Int {
        val ids = _selection.value
        val count = download(_state.value.posts.filter { it.id in ids })
        clearSelection()
        return count
    }

    fun downloadAll(max: Int) {
        val source = _state.value.source ?: return
        c.downloads.downloadSearch(source, _state.value.query, max)
    }
}
