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
)

/** Where a post stands with respect to the vault. */
enum class SaveState { NONE, QUEUED, SAVED }

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
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    /** A web page opened on the spot: browsed like a source, never saved among the sources. */
    private val webPage = MutableStateFlow<BooruSource?>(null)

    private val _resolveErrors = MutableStateFlow<Map<Long, Problem>>(emptyMap())

    /** Posts from HTML lists whose page could not be read, with the reason. */
    val resolveErrors: StateFlow<Map<Long, Problem>> = _resolveErrors.asStateFlow()
    private val resolving = HashSet<Long>()

    private var page = 0
    private val seen = HashSet<Long>()
    private var loadJob: Job? = null
    private var suggestJob: Job? = null

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
                        combine(c.settings.settings.map { it.selectedSource }, webPage) { selected, page -> page ?: selected }
                            .distinctUntilChanged().collect { source ->
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
        resolving.clear()
        _resolveErrors.value = emptyMap()
        webPage.value = null
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
        page = 0
        seen.clear()
        _selection.value = emptySet()
        _state.update {
            it.copy(
                posts = emptyList(), loading = false, refreshing = refreshing, endReached = false,
                error = null, challengeUrl = null, hidden = 0, generation = it.generation + 1,
            )
        }
        loadMore()
    }

    fun refresh() = restart(refreshing = true)

    /** Reads the post's own page for its file, once; the post is replaced in the list. */
    fun resolve(post: Post) {
        if (!post.needsResolve || !resolving.add(post.id)) return
        val source = _state.value.source ?: return
        val generation = _state.value.generation
        _resolveErrors.update { it - post.id }
        viewModelScope.launch {
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
            }
        }
    }

    /** Shows the images of any page, as a source of the moment that is not saved. */
    fun openWebPage(address: String) {
        val url = address.trim().let { if (it.startsWith("http://") || it.startsWith("https://")) it else "https://$it" }
        val host = url.substringAfter("://").substringBefore('/').removePrefix("www.")
        val source = BooruSource(WEB_PAGE_SOURCE, host, BooruEngine.HTML_GENERIC, url)
        input = TextFieldValue("")
        _suggestions.value = emptyList()
        _state.update { it.copy(query = "") }
        // The same page again: the collector sees no change, so reload it here.
        if (webPage.value == source) refresh() else webPage.value = source
    }

    companion object {
        const val WEB_PAGE_SOURCE = "web-page"
    }

    fun search(query: String) {
        val normalized = query.trim().replace(Regex("\\s+"), " ")
        input = TextFieldValue(normalized, TextRange(normalized.length))
        _suggestions.value = emptyList()
        _state.update { it.copy(query = normalized) }
        restart(refreshing = false)
    }

    fun submit() = search(input.text)

    fun selectSource(id: String) {
        webPage.value = null
        viewModelScope.launch { c.settings.selectSource(id) }
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
                c.api(source).autocomplete(token)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
        }
    }

    fun applySuggestion(suggestion: TagSuggestion) {
        val text = input.text
        val head = text.substringBeforeLast(' ', "")
        val (prefix, _) = autocompleteToken(text)
        val replaced = (if (head.isEmpty()) "" else "$head ") + prefix + suggestion.name + " "
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

    fun requestScroll(index: Int) {
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
