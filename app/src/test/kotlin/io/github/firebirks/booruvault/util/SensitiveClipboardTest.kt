package io.github.firebirks.booruvault.util

import org.junit.Assert.assertEquals
import org.junit.Test

class SensitiveClipboardTest {
    private val copied = mutableListOf<String>()
    private var cleared = 0
    private val clipboard = SensitiveClipboard(put = { _, text -> copied += text }, clear = { cleared++ })

    @Test
    fun aLockTakesOffTheClipboardWhatTheAppPutOnIt() {
        clipboard.copy("tag", "pikachu")
        clipboard.forget()

        assertEquals(listOf("pikachu"), copied)
        assertEquals(1, cleared)
    }

    @Test
    fun aLockLeavesTheClipboardAloneWhenTheAppPutNothingOnItSinceTheLastOne() {
        clipboard.forget()
        assertEquals(0, cleared)

        clipboard.copy("link", "https://safebooru.donmai.us/posts?tags=eevee")
        clipboard.forget()
        clipboard.forget()
        assertEquals(1, cleared)
    }
}
