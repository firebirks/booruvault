package app.booruvault.ui.vault

import app.booruvault.util.toProblem
import app.booruvault.ui.common.UiText
import app.booruvault.R
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.booruvault.AppContainer
import app.booruvault.vault.VaultItem
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

    val items: StateFlow<List<VaultItem>> = combine(
        c.vaultStore.items,
        snapshotFlow { query },
        snapshotFlow { newestFirst },
    ) { items, query, newest ->
        val filtered = filter(items, query)
        if (newest) filtered else filtered.reversed()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _selection = MutableStateFlow<Set<String>>(emptySet())
    val selection: StateFlow<Set<String>> = _selection.asStateFlow()

    private val _transfer = MutableStateFlow<Transfer?>(null)
    val transfer: StateFlow<Transfer?> = _transfer.asStateFlow()

    private val _messages = MutableSharedFlow<UiText>(extraBufferCapacity = 4)
    val messages: SharedFlow<UiText> = _messages

    init {
        // Locking forgets the selection and the filter along with everything else.
        viewModelScope.launch {
            c.vault.state.collect {
                _selection.value = emptySet()
                query = ""
            }
        }
    }

    fun toggle(id: String) = _selection.update { if (id in it) it - id else it + id }
    fun startSelection(id: String) = _selection.update { it + id }
    fun selectAll() = _selection.update { items.value.mapTo(HashSet()) { it.id } }
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
            _messages.tryEmit(
                UiText.Join(
                    listOfNotNull(
                        UiText.Plural(R.plurals.vault_imported, result.imported),
                        if (result.failed > 0) UiText.Plural(R.plurals.vault_import_failed, result.failed) else null,
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
            val done = try {
                c.transfer.exportToFolder(chosen, tree) { done, total -> _transfer.value = Transfer(false, done, total) }
            } catch (e: Exception) {
                _messages.tryEmit(exportFailed(e))
                return@launch
            } finally {
                _transfer.value = null
            }
            clearSelection()
            _messages.tryEmit(UiText.Plural(R.plurals.vault_exported, done))
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

    private fun exportFailed(e: Throwable): UiText = UiText.Res(R.string.vault_export_failed, listOf(UiText.Of(e.toProblem())))

    companion object {
        /**
         * Space-separated terms, all of which must match: plain words match tags, file names and
         * sources; `-word` excludes; `type:video|gif|image` and `source:name` narrow further.
         */
        fun filter(items: List<VaultItem>, query: String): List<VaultItem> {
            val terms = query.lowercase().split(' ').filter { it.isNotBlank() }
            if (terms.isEmpty()) return items
            return items.filter { item ->
                terms.all { term ->
                    val negate = term.startsWith('-') && term.length > 1
                    val t = if (negate) term.drop(1) else term
                    val hit = when {
                        t.startsWith("type:") -> when (t.removePrefix("type:")) {
                            "video" -> item.isVideo
                            "gif" -> item.isAnimated
                            "image" -> !item.isVideo && !item.isAnimated
                            else -> false
                        }
                        t.startsWith("source:") -> item.sourceName?.lowercase()?.contains(t.removePrefix("source:")) == true
                        else -> item.tags.any { it.lowercase().contains(t) } ||
                            item.fileName.lowercase().contains(t) ||
                            item.sourceName?.lowercase()?.contains(t) == true
                    }
                    hit != negate
                }
            }
        }
    }
}
