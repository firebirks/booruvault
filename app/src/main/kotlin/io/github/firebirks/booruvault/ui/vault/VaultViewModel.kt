package io.github.firebirks.booruvault.ui.vault

import java.util.UUID
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
import io.github.firebirks.booruvault.vault.TagQuery
import io.github.firebirks.booruvault.vault.TagCollection
import io.github.firebirks.booruvault.util.Problem
import io.github.firebirks.booruvault.util.toProblem
import io.github.firebirks.booruvault.ui.common.UiText
import io.github.firebirks.booruvault.R
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.firebirks.booruvault.AppContainer
import io.github.firebirks.booruvault.vault.VaultItem
import io.github.firebirks.booruvault.vault.VaultState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Progress of an import or export: [done] of [total]. */
data class Transfer(val importing: Boolean, val done: Int, val total: Int)

class VaultViewModel(private val c: AppContainer) : ViewModel() {
    var query by mutableStateOf("")
    var newestFirst by mutableStateOf(true)

    /** The collection being looked at, or null for everything. */
    var collectionId by mutableStateOf<String?>(null)

    val collections: StateFlow<List<TagCollection>> = c.settings.settings.map { it.collections }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * What the grid shows: the collection looked at, filtered and in order. Null until the first
     * list is worked out, so a vault opening on many items does not say for a moment that
     * nothing matches.
     */
    val items: StateFlow<List<VaultItem>?> = combine(
        c.vaultStore.items,
        snapshotFlow { query },
        snapshotFlow { newestFirst },
        snapshotFlow { collectionId },
        collections,
    ) { items, query, newest, id, collections ->
        val collection = collections.firstOrNull { it.id == id }
        val inCollection = collection?.let { items.filter(TagQuery.matcher(it.query)) } ?: items
        val filtered = inCollection.filter(TagQuery.matcher(query, loose = true))
        if (newest) filtered else filtered.reversed()
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** How many items each collection holds right now. */
    val counts: StateFlow<Map<String, Int>> = combine(c.vaultStore.items, collections) { items, collections ->
        collections.associate { collection -> collection.id to items.count(TagQuery.matcher(collection.query)) }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    private val _selection = MutableStateFlow<Set<String>>(emptySet())
    val selection: StateFlow<Set<String>> = _selection.asStateFlow()

    // What a system picker was opened for. Kept here, not in the screen: the picker puts the app
    // in the background, and a screen recreated meanwhile would come back knowing nothing of it.

    /** Files picked for import, awaiting the question about their originals. */
    var pendingImport by mutableStateOf<List<Uri>>(emptyList())

    /** Items to export into the folder being picked. */
    var exportIds by mutableStateOf<Set<String>>(emptySet())

    /** The item to export into the file being picked. */
    var exportItem by mutableStateOf<VaultItem?>(null)

    private var pickerHold: (() -> Unit)? = null

    /** Keeps the vault open while a system picker is in front; [pickerClosed] ends that. */
    fun pickerOpened() {
        pickerHold?.invoke()
        pickerHold = c.autoLock.hold()
    }

    fun pickerClosed() {
        pickerHold?.invoke()
        pickerHold = null
    }

    private val _transfer = MutableStateFlow<Transfer?>(null)
    val transfer: StateFlow<Transfer?> = _transfer.asStateFlow()

    private val _messages = MutableSharedFlow<UiText>(extraBufferCapacity = 4)
    val messages: SharedFlow<UiText> = _messages

    private val _scrollTo = MutableSharedFlow<String>(extraBufferCapacity = 1)

    /** The id of the item the viewer was closed on; the grid brings it into view. */
    val scrollTo: SharedFlow<String> = _scrollTo

    fun requestScroll(id: String) {
        _scrollTo.tryEmit(id)
    }

    init {
        // Locking forgets the selection and the filter along with everything else. A password
        // change publishes a new unlocked state, which is no reason to drop them.
        viewModelScope.launch {
            c.vault.state.collect { state ->
                if (state is VaultState.Unlocked) return@collect
                _selection.value = emptySet()
                query = ""
                collectionId = null
                pendingImport = emptyList()
                exportIds = emptySet()
                exportItem = null
            }
        }
    }

    fun toggle(id: String) = _selection.update { if (id in it) it - id else it + id }
    fun startSelection(id: String) = _selection.update { it + id }
    fun selectAll() = _selection.update { items.value.orEmpty().mapTo(HashSet()) { it.id } }
    fun clearSelection() {
        _selection.value = emptySet()
    }

    fun delete(ids: Set<String>) {
        viewModelScope.launch {
            c.vaultStore.delete(ids)
            _selection.update { it - ids }
            _messages.tryEmit(if (ids.size == 1) UiText.Res(R.string.vault_deleted_one) else UiText.Plural(R.plurals.vault_deleted, ids.size))
        }
    }

    fun import(uris: List<Uri>, deleteOriginals: Boolean) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val result = try {
                c.transfer.import(uris, deleteOriginals) { done, total -> _transfer.value = Transfer(true, done, total) }
            } finally {
                _transfer.value = null
            }
            // The counts, and for what failed the first file's name and reason: enough to know
            // what to do about it, without a list that would not fit a message.
            val first = result.failures.firstOrNull()
            _messages.tryEmit(
                UiText.Join(
                    listOfNotNull(
                        UiText.Plural(R.plurals.vault_imported, result.imported),
                        if (result.failed > 0) UiText.Plural(R.plurals.vault_import_failed, result.failed) else null,
                        first?.let { UiText.Res(R.string.vault_import_failure_detail, listOf(it.name, UiText.Of(it.problem))) },
                        if (result.originalsNotDeleted > 0) UiText.Plural(R.plurals.vault_originals_kept, result.originalsNotDeleted) else null,
                    ),
                ),
            )
        }
    }

    fun exportToFolder(ids: Set<String>, tree: Uri) {
        val chosen = c.vaultStore.items.value.filter { it.id in ids }
        if (chosen.isEmpty()) return
        viewModelScope.launch {
            val result = try {
                c.transfer.exportToFolder(chosen, tree) { done, total -> _transfer.value = Transfer(false, done, total) }
            } catch (e: Exception) {
                _messages.tryEmit(exportFailed(e))
                return@launch
            } finally {
                _transfer.value = null
            }
            clearSelection()
            val folder = result.folderName ?: UiText.Res(R.string.vault_export_folder_fallback)
            _messages.tryEmit(
                if (result.failed == 0) {
                    UiText.Plural(R.plurals.vault_exported_to, result.done, listOf(result.done, folder))
                } else {
                    UiText.Res(R.string.vault_export_partial, listOf(result.done, result.total, folder, UiText.Of(result.firstProblem ?: Problem(Problem.Code.UNKNOWN))))
                },
            )
        }
    }

    fun exportOne(item: VaultItem, target: Uri) {
        viewModelScope.launch {
            runCatching { c.transfer.exportTo(item, target) }
                .onSuccess { _messages.tryEmit(UiText.Res(R.string.vault_exported_one)) }
                .onFailure { _messages.tryEmit(exportFailed(it)) }
        }
    }

    fun lock() = c.vault.lock()

    fun saveCollection(existing: TagCollection?, name: String, query: String) {
        val collection = TagCollection(existing?.id ?: UUID.randomUUID().toString(), name, query, existing?.createdAt ?: System.currentTimeMillis())
        viewModelScope.launch {
            c.settings.saveCollection(collection)
            collectionId = collection.id
            _messages.tryEmit(UiText.Res(R.string.collection_saved, listOf(name)))
        }
    }

    fun deleteCollection(id: String) {
        if (collectionId == id) collectionId = null
        viewModelScope.launch { c.settings.deleteCollection(id) }
    }

    private fun exportFailed(e: Throwable): UiText = UiText.Res(R.string.vault_export_failed, listOf(UiText.Of(e.toProblem())))
}
