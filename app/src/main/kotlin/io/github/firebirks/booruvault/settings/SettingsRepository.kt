package io.github.firebirks.booruvault.settings

import io.github.firebirks.booruvault.booru.BooruSource
import io.github.firebirks.booruvault.booru.Presets
import io.github.firebirks.booruvault.booru.Rating
import io.github.firebirks.booruvault.vault.SealedJson
import io.github.firebirks.booruvault.vault.TagCollection
import io.github.firebirks.booruvault.vault.VaultManager
import io.github.firebirks.booruvault.vault.VaultState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
enum class GridSize(val minCellDp: Int) {
    SMALL(96),
    MEDIUM(132),
    LARGE(180),
}

@Serializable
data class AppSettings(
    val sources: List<BooruSource> = Presets.defaults,
    val selectedSourceId: String? = null,
    val allowedRatings: Set<Rating> = setOf(Rating.GENERAL),
    /** One rule per line; a post is hidden when it has every tag of any rule. */
    val blacklist: String = "",
    val gridSize: GridSize = GridSize.MEDIUM,
    val viewerOriginal: Boolean = false,
    val downloadOriginal: Boolean = true,
    val pageSize: Int = 40,
    /** Seconds in background before the app locks; 0 locks at once. */
    val autoLockSeconds: Int = 300,
    /**
     * Keep downloading after the vault locks. Off by default: the queue, the sources it uses and
     * their cookies must then stay in memory until it drains.
     */
    val downloadWhileLocked: Boolean = false,
    /** Google Safe Browsing in the built-in browser. Off: no URL hashes go to Google. */
    val webSafeBrowsing: Boolean = false,
    /** Read pages that keep demanding an anti-bot check through a hidden WebView. */
    val hiddenBrowser: Boolean = false,
    /** With Tor on and no Tor Browser, let a link open in a normal browser after a warning. */
    val linksOutsideTor: Boolean = false,
    /** Bookmarked tag sets, shown as collections in the vault. */
    val collections: List<TagCollection> = emptyList(),
    val secureScreen: Boolean = true,
) {
    val selectedSource: BooruSource?
        get() = sources.firstOrNull { it.id == selectedSourceId } ?: sources.firstOrNull()

    val blacklistRules: List<Set<String>>
        get() = blacklist.lines().map { line -> line.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.map { it.lowercase() }.toSet() }
            .filter { it.isNotEmpty() }
}

/**
 * Settings, sources and their API keys, sealed inside the vault (`config.bin`). They exist in
 * memory only while the vault is unlocked; locking resets them to defaults and [ready] to false.
 */
class SettingsRepository(root: File, private val vault: VaultManager, json: Json, scope: CoroutineScope) {
    private val store = SealedJson(File(root, "config.bin"), AppSettings.serializer(), json)
    private val mutex = Mutex()

    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val _ready = MutableStateFlow(false)

    /** True while the vault is unlocked and its settings have been read. */
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    init {
        scope.launch(Dispatchers.IO) {
            vault.state.collect { state ->
                mutex.withLock {
                    if (state is VaultState.Unlocked) {
                        _settings.value = store.read(state.keys) ?: AppSettings()
                        _ready.value = true
                    } else {
                        // Forgotten first: whoever sees ready drop must not find the old values.
                        _settings.value = AppSettings()
                        _ready.value = false
                    }
                }
            }
        }
    }

    private suspend fun edit(transform: (AppSettings) -> AppSettings) = mutex.withLock {
        // Edits only make sense on settings that came from the vault.
        if (!_ready.value) return@withLock
        val next = transform(_settings.value)
        _settings.value = next
        val recipient = vault.recipient ?: return@withLock
        withContext(Dispatchers.IO) { store.write(recipient, next) }
    }

    suspend fun setSources(sources: List<BooruSource>) = edit { it.copy(sources = sources) }
    suspend fun selectSource(id: String) = edit { it.copy(selectedSourceId = id) }
    suspend fun setRatings(ratings: Set<Rating>) = edit { it.copy(allowedRatings = ratings) }
    suspend fun setBlacklist(text: String) = edit { it.copy(blacklist = text) }
    suspend fun setGridSize(size: GridSize) = edit { it.copy(gridSize = size) }
    suspend fun setViewerOriginal(value: Boolean) = edit { it.copy(viewerOriginal = value) }
    suspend fun setDownloadOriginal(value: Boolean) = edit { it.copy(downloadOriginal = value) }
    suspend fun setPageSize(value: Int) = edit { it.copy(pageSize = value) }
    suspend fun setAutoLock(seconds: Int) = edit { it.copy(autoLockSeconds = seconds) }
    suspend fun setSecureScreen(value: Boolean) = edit { it.copy(secureScreen = value) }
    suspend fun setDownloadWhileLocked(value: Boolean) = edit { it.copy(downloadWhileLocked = value) }
    suspend fun setWebSafeBrowsing(value: Boolean) = edit { it.copy(webSafeBrowsing = value) }
    suspend fun setHiddenBrowser(value: Boolean) = edit { it.copy(hiddenBrowser = value) }
    suspend fun setLinksOutsideTor(value: Boolean) = edit { it.copy(linksOutsideTor = value) }

    suspend fun saveCollection(collection: TagCollection) = edit { current ->
        val others = current.collections.filterNot { it.id == collection.id }
        val index = current.collections.indexOfFirst { it.id == collection.id }
        current.copy(collections = if (index < 0) others + collection else others.toMutableList().apply { add(index, collection) })
    }

    suspend fun deleteCollection(id: String) = edit { current -> current.copy(collections = current.collections.filterNot { it.id == id }) }

    suspend fun addToBlacklist(tag: String) = edit { current ->
        if (current.blacklist.lines().any { it.trim() == tag }) current
        else current.copy(blacklist = (current.blacklist.trimEnd() + "\n" + tag).trim())
    }
}
