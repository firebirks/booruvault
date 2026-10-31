package io.github.firebirks.booruvault.ui.common

import android.webkit.WebSettings
import org.junit.Assert.assertEquals
import org.junit.Test

/** Where, and how, the built-in browser opens for each kind of check. */
class ChallengeStartTest {
    private val file = "https://cdn.donmai.us/original/36/0c/360cf286248a3f253df7155558f92d98.jpg"

    @Test
    fun `a file host's check opens on the very file it guards`() {
        assertEquals(file, challengeStart(file, ChallengeMode.ADDRESS))
    }

    @Test
    fun `a site's check opens at its home, so an API address and its key stay out of the browser`() {
        val api = "https://gelbooru.com/index.php?page=dapi&s=post&q=index&json=1&api_key=secret&user_id=1"
        assertEquals("https://gelbooru.com/", challengeStart(api, ChallengeMode.SITE))
    }

    @Test
    fun `browsing opens the address itself`() {
        val options = "https://gelbooru.com/index.php?page=account&s=options"
        assertEquals(options, challengeStart(options, ChallengeMode.BROWSE))
    }

    @Test
    fun `a file host's check is never answered from the browser's cache`() {
        assertEquals(WebSettings.LOAD_NO_CACHE, challengeCacheMode(ChallengeMode.ADDRESS))
    }

    @Test
    fun `a site's check and browsing use the cache as usual`() {
        assertEquals(WebSettings.LOAD_DEFAULT, challengeCacheMode(ChallengeMode.SITE))
        assertEquals(WebSettings.LOAD_DEFAULT, challengeCacheMode(ChallengeMode.BROWSE))
    }
}
