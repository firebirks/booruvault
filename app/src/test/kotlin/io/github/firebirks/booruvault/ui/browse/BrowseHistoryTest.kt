package io.github.firebirks.booruvault.ui.browse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Back in Browse returns to the grids a search, or a page opened on the spot, replaced. */
class BrowseHistoryTest {
    @Test
    fun `back returns the grids in the reverse order they were left`() {
        val history = BrowseHistory<String>()
        assertFalse(history.canGoBack)
        history.push("first")
        history.push("second")

        assertTrue(history.canGoBack)
        assertEquals("second", history.pop())
        assertEquals("first", history.pop())
        assertNull(history.pop())
        assertFalse(history.canGoBack)
    }

    @Test
    fun `only the latest grids are kept`() {
        val history = BrowseHistory<Int>(limit = 3)
        (1..5).forEach(history::push)

        assertEquals(listOf(5, 4, 3), List(3) { history.pop() })
        assertNull(history.pop())
    }

    @Test
    fun `closing a page opened on the spot returns to the grid before it and drops what came after`() {
        val history = BrowseHistory<String>()
        history.push("search")
        history.push("before the page", beforeWebPage = true)
        history.push("first look at the page")

        assertEquals("before the page", history.popToBeforeWebPage())
        assertEquals("search", history.pop())
        assertNull(history.pop())
    }

    @Test
    fun `closing a page with nothing kept from before it leaves the history as it is`() {
        val history = BrowseHistory<String>()
        history.push("search")

        assertNull(history.popToBeforeWebPage())
        assertEquals("search", history.pop())
    }

    @Test
    fun `clearing forgets every grid`() {
        val history = BrowseHistory<String>()
        history.push("search")
        history.clear()

        assertFalse(history.canGoBack)
        assertNull(history.pop())
    }
}
