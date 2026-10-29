package io.github.firebirks.booruvault.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The built-in browser opened on a guarded file never fetches it: the first request asks for a
 * range past its end, and the request made once the check is passed is answered empty.
 */
class GuardedFileTest {
    private val file = "https://cdn.donmai.us/original/36/0c/360cf286248a3f253df7155558f92d98.jpg"

    @Test
    fun `the file asked for again after the check, with the check's own query, is the guarded file`() {
        assertTrue(Challenge.isGuardedFile(file, "$file?__cf_chl_rt_tk=abc"))
        assertTrue(Challenge.isGuardedFile(file, file))
    }

    @Test
    fun `the check's own scripts and other files are not`() {
        assertFalse(Challenge.isGuardedFile(file, "https://cdn.donmai.us/cdn-cgi/challenge-platform/h/b/orchestrate/chl_page/v1"))
        assertFalse(Challenge.isGuardedFile(file, "https://cdn.donmai.us/original/6e/5f/6e5f153db3867195c069e45d7c2d4f8b.jpg"))
        assertFalse(Challenge.isGuardedFile(file, "https://challenges.cloudflare.com/turnstile/v0/api.js"))
        assertFalse(Challenge.isGuardedFile(file, "not a url"))
    }

    @Test
    fun `the file is answered empty only once the check is passed`() {
        assertFalse(Challenge.answersEmpty(file, file) { false })
        assertTrue(Challenge.answersEmpty(file, "$file?__cf_chl_rt_tk=abc") { true })
    }

    @Test
    fun `the clearance is not even looked up for requests that are not the file`() {
        var asked = false
        assertFalse(Challenge.answersEmpty(file, "https://challenges.cloudflare.com/x") { asked = true; true })
        assertFalse(asked)
    }

    @Test
    fun `the first request asks for a range that starts past the end of any file`() {
        val start = Challenge.NO_BYTES_RANGE.removePrefix("bytes=").removeSuffix("-").toLong()
        assertTrue(Challenge.NO_BYTES_RANGE.matches(Regex("bytes=\\d+-")))
        assertTrue("past a terabyte", start >= 1L shl 40)
    }
}
