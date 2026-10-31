package io.github.firebirks.booruvault.ui.browse

import androidx.compose.runtime.mutableStateListOf

/**
 * The grids Back returns to in Browse, latest last: each new search, and each page opened on the
 * spot, keeps the grid it replaced. Only the latest [limit] are kept, as each holds its results.
 */
internal class BrowseHistory<T>(private val limit: Int = 20) {
    private class Entry<T>(val grid: T, val beforeWebPage: Boolean)

    private val entries = mutableStateListOf<Entry<T>>()

    val canGoBack: Boolean get() = entries.isNotEmpty()

    fun push(grid: T, beforeWebPage: Boolean = false) {
        entries += Entry(grid, beforeWebPage)
        if (entries.size > limit) entries.removeAt(0)
    }

    fun pop(): T? = entries.removeLastOrNull()?.grid

    /** The grid shown before the latest page opened on the spot; what came after it goes too. */
    fun popToBeforeWebPage(): T? {
        val at = entries.indexOfLast { it.beforeWebPage }
        if (at < 0) return null
        val grid = entries[at].grid
        entries.removeRange(at, entries.size)
        return grid
    }

    fun clear() = entries.clear()
}
