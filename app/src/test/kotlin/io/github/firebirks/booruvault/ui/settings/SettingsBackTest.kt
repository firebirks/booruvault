package io.github.firebirks.booruvault.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsBackTest {
    @Test
    fun `a page opened from the hub goes back to the hub`() {
        assertEquals("main" to false, settingsBack("network", returnPage = null))
        assertEquals("main" to false, settingsBack("content", returnPage = "network"))
    }

    @Test
    fun `a page another tab opened goes back to that tab, leaving the hub behind`() {
        assertEquals("main" to true, settingsBack("network", returnPage = "network"))
        assertEquals("main" to true, settingsBack("sources", returnPage = "sources"))
    }

    @Test
    fun `a source's editor goes back to the sources first, wherever they were opened from`() {
        assertEquals("sources" to false, settingsBack("source:new", returnPage = null))
        assertEquals("sources" to false, settingsBack("source:e926", returnPage = "sources"))
    }
}
