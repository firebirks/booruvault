package io.github.firebirks.booruvault.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** Back goes to the tab visited before the current one, each tab counted once. */
class TabTrailTest {
    @Test
    fun `a visited tab becomes the current one`() {
        assertEquals(listOf("browse", "downloads"), listOf("browse").visit("downloads"))
    }

    @Test
    fun `a tab visited again moves to the end rather than appearing twice`() {
        val trail = listOf("browse").visit("downloads").visit("settings").visit("downloads")
        assertEquals(listOf("browse", "settings", "downloads"), trail)
    }

    @Test
    fun `visiting the current tab changes nothing`() {
        assertEquals(listOf("browse", "vault"), listOf("browse", "vault").visit("vault"))
    }
}
