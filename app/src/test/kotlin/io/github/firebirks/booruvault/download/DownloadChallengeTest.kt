package io.github.firebirks.booruvault.download

import io.github.firebirks.booruvault.booru.BooruEngine
import io.github.firebirks.booruvault.booru.BooruSource
import io.github.firebirks.booruvault.booru.Post
import io.github.firebirks.booruvault.booru.UserAgents
import io.github.firebirks.booruvault.booru.withSiteHeaders
import io.github.firebirks.booruvault.util.Problem
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A download asks for its file as the grid asks for images, and an anti-bot check met on the
 * file host is reported so the Downloads screen can offer it, once, at the address it guards.
 */
class DownloadChallengeTest {
    private val safebooru = BooruSource("safebooru-donmai", "Safebooru", BooruEngine.DANBOORU, "https://safebooru.donmai.us")
    private val gelbooru = BooruSource("custom-gel", "Gelbooru", BooruEngine.GELBOORU, "https://gelbooru.com")
    private val original = "https://cdn.donmai.us/original/36/0c/360cf286248a3f253df7155558f92d98.jpg"

    @Test
    fun `a download names no referer of its own`() {
        val request = downloadRequest(original, safebooru)
        assertNull(request.header("Referer"))
        assertEquals(UserAgents.forSource(safebooru), request.header("User-Agent"))
    }

    @Test
    fun `the client gives a download the file host's own origin as referer`() {
        val sent = withSiteHeaders(downloadRequest(original, safebooru), passedAgent = null, cleared = false)
        assertEquals("https://cdn.donmai.us/", sent.header("Referer"))
    }

    @Test
    fun `a gelbooru file is asked for with its image host's origin, which the host accepts`() {
        val file = "https://img4.gelbooru.com/images/5a/37/5a377672ea986cccbaa55c629a400081.jpg"
        val sent = withSiteHeaders(downloadRequest(file, gelbooru), passedAgent = null, cleared = false)
        assertEquals("https://img4.gelbooru.com/", sent.header("Referer"))
    }

    @Test
    fun `the agent that passed a site's check wins, and a referer already set is kept`() {
        val request = Request.Builder().url(original).header("User-Agent", "app").header("Referer", "https://example.org/").build()
        val sent = withSiteHeaders(request, passedAgent = "webview", cleared = true)
        assertEquals("webview", sent.header("User-Agent"))
        assertEquals("https://example.org/", sent.header("Referer"))
    }

    private val now = 10_000_000_000L
    private val minute = 60_000L

    @Test
    fun `a first check is reported with the address it guards`() {
        val problem = Problem(Problem.Code.CHALLENGE, original)
        assertEquals(problem, downloadProblem(problem, checkedAt = null, passExpired = false, now = now, tor = false))
        assertEquals(problem, downloadProblem(problem, checkedAt = null, passExpired = false, now = now, tor = true))
    }

    @Test
    fun `a check met again after it was passed is not offered once more`() {
        val problem = Problem(Problem.Code.CHALLENGE, original)
        assertEquals(Problem(Problem.Code.CHALLENGE_PASS_REFUSED), downloadProblem(problem, checkedAt = now - minute, passExpired = false, now = now, tor = false))
        assertEquals(Problem(Problem.Code.CHALLENGE_PASS_REFUSED_TOR), downloadProblem(problem, checkedAt = now - minute, passExpired = false, now = now, tor = true))
    }

    @Test
    fun `a download retried by hand after the check is still told the check will not help`() {
        // The block belongs to the source and the time of the pass, not to the retry made on the
        // way back from the browser: the row's Retry, "retry all" or an unlock lead to the same answer.
        val problem = Problem(Problem.Code.CHALLENGE, original)
        val byHand = now + 20 * minute
        assertEquals(Problem(Problem.Code.CHALLENGE_PASS_REFUSED), downloadProblem(problem, checkedAt = now, passExpired = false, now = byHand, tor = false))
    }

    @Test
    fun `a pass refused before it ever worked is not offered again`() {
        val problem = Problem(Problem.Code.CHALLENGE, original)
        assertEquals(Problem(Problem.Code.CHALLENGE_PASS_REFUSED), downloadProblem(problem, checkedAt = now - minute, passExpired = false, now = now, tor = false))
    }

    @Test
    fun `a pass that worked and was then refused is offered again, at the address it guards`() {
        // The address changed or the pass ran out: a new one will work, so the browser is offered.
        val problem = Problem(Problem.Code.CHALLENGE, original)
        assertEquals(problem, downloadProblem(problem, checkedAt = now - minute, passExpired = true, now = now, tor = false))
        assertEquals(problem, downloadProblem(problem, checkedAt = now - minute, passExpired = true, now = now, tor = true))
    }

    @Test
    fun `once the pass has run out, the check is offered again`() {
        val problem = Problem(Problem.Code.CHALLENGE, original)
        assertEquals(problem, downloadProblem(problem, checkedAt = now - CHECK_HOLDS_MS - minute, passExpired = false, now = now, tor = false))
    }

    @Test
    fun `other failures after a check are reported as they are`() {
        val problem = Problem(Problem.Code.HTTP_STATUS, "404")
        assertEquals(problem, downloadProblem(problem, checkedAt = now, passExpired = false, now = now, tor = true))
    }

    @Test
    fun `a passed check queues again only that source's downloads the check stopped`() {
        val e926 = BooruSource("e926", "e926", BooruEngine.E621, "https://e926.net")
        fun task(key: Long, source: BooruSource, status: DownloadStatus, code: Problem.Code?) =
            DownloadTask(key, post(key, source), source, status, problem = code?.let { Problem(it) })
        val tasks = listOf(
            task(1, safebooru, DownloadStatus.FAILED, Problem.Code.CHALLENGE),
            task(2, safebooru, DownloadStatus.FAILED, Problem.Code.HTTP_STATUS),
            task(3, safebooru, DownloadStatus.DONE, null),
            task(4, safebooru, DownloadStatus.FAILED, Problem.Code.CHALLENGE_PASS_REFUSED),
            task(5, e926, DownloadStatus.FAILED, Problem.Code.CHALLENGE),
            task(6, safebooru, DownloadStatus.FAILED, Problem.Code.CHALLENGE),
        )
        assertEquals(listOf(1L, 6L), challengedKeys(tasks, safebooru.id))
    }

    private fun post(id: Long, source: BooruSource) = Post(
        sourceId = source.id, id = id, previewUrl = null, sampleUrl = null, fileUrl = original,
        width = 1, height = 1, fileExt = "jpg", postUrl = "${source.baseUrl}/posts/$id",
    )
}
