package io.github.firebirks.booruvault.download

import io.github.firebirks.booruvault.booru.BooruApi
import io.github.firebirks.booruvault.booru.BooruEngine
import io.github.firebirks.booruvault.booru.BooruSource
import io.github.firebirks.booruvault.booru.Post
import io.github.firebirks.booruvault.booru.Rating
import io.github.firebirks.booruvault.booru.TagSuggestion
import io.github.firebirks.booruvault.settings.SettingsRepository
import io.github.firebirks.booruvault.util.Problem
import io.github.firebirks.booruvault.util.ProblemException
import io.github.firebirks.booruvault.vault.VaultManager
import io.github.firebirks.booruvault.vault.VaultState
import io.github.firebirks.booruvault.vault.VaultStore
import io.github.firebirks.booruvault.vault.crypto.Kdf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Downloading a search's results follows its pages to the last one. Danbooru drops the posts it
 * does not show after paging, so a page shorter than asked for is not the last: only an empty one
 * is. A page that fails stops the collection where it is, and says why.
 */
class DownloadSearchTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** The manager's own: it takes the unlocked vault at once, so a search can start right away. */
    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val fastKdf get() = Kdf.newParams(memoryKiB = 1024, iterations = 1)
    private val password = "pw-123456"
    private val source = BooruSource("safebooru-donmai", "Safebooru", BooruEngine.DANBOORU, "https://safebooru.donmai.us")

    private lateinit var vault: VaultManager

    @After
    fun tearDown() {
        managerScope.cancel()
        scope.cancel()
    }

    /** A post without files: its download fails at once, with nothing sent anywhere. */
    private fun post(id: Long, rating: Rating = Rating.GENERAL) =
        Post(source.id, id, null, null, null, 1, 1, "jpg", rating = rating, postUrl = "https://safebooru.donmai.us/posts/$id")

    /** Pages of the given sizes, numbered on from 1, and nothing after them. */
    private fun sized(vararg sizes: Int): (Int) -> List<Post> = { page ->
        val start = sizes.take(page - 1).sum()
        if (page > sizes.size) emptyList() else (1..sizes[page - 1]).map { post((start + it).toLong()) }
    }

    private class FakeApi(private val pages: (Int) -> List<Post>) : BooruApi {
        val asked = CopyOnWriteArrayList<Int>()

        override suspend fun posts(tags: String, page: Int, limit: Int): List<Post> {
            asked += page
            return pages(page)
        }

        override suspend fun autocomplete(query: String) = emptyList<TagSuggestion>()
    }

    private suspend fun manager(api: BooruApi): DownloadManager {
        val root = tmp.newFolder()
        vault = VaultManager(root, json)
        val settings = SettingsRepository(root, vault, json, scope)
        vault.create(password.toCharArray(), fastKdf)
        withTimeout(5000) { settings.ready.first { it } }
        val store = VaultStore(root, vault, json, scope)
        return DownloadManager({}, { api }, OkHttpClient(), { false }, { _, _ -> false }, store, vault, settings, json, root, managerScope)
    }

    /** Waits until no batch is still collecting. */
    private suspend fun DownloadManager.settled() = withTimeout(10_000) { batches.first { list -> list.none { it.active } } }

    private fun DownloadManager.queuedIds() = tasks.value.map { it.post.id }.toSet()

    @Test
    fun `short pages that are not the last are followed to the end`() = runBlocking {
        val api = FakeApi(sized(42, 100, 37))
        val downloads = manager(api)

        downloads.downloadSearch(source, "pikachu", 500)
        downloads.settled()

        assertEquals((1L..179L).toSet(), downloads.queuedIds())
        assertEquals(listOf(1, 2, 3, 4), api.asked)
        assertTrue(downloads.batches.value.isEmpty())
    }

    @Test
    fun `short pages fill the batch up to its maximum`() = runBlocking {
        val downloads = manager(FakeApi(sized(42, 100, 37)))

        downloads.downloadSearch(source, "pikachu", 150)
        downloads.settled()

        assertEquals((1L..150L).toSet(), downloads.queuedIds())
    }

    @Test
    fun `posts a page repeats from the one before count once toward the maximum`() = runBlocking {
        // Uploads while collecting push newest-first pages along: each starts where the one before ended.
        val downloads = manager(FakeApi { page -> if (page > 3) emptyList() else (1..100).map { post((page - 1) * 95L + it) } })

        downloads.downloadSearch(source, "pikachu", 200)
        downloads.settled()

        assertEquals((1L..200L).toSet(), downloads.queuedIds())
    }

    @Test
    fun `a page that fails keeps what came before and is reported on the batch`() = runBlocking {
        val pages = sized(42, 100, 37)
        val downloads = manager(FakeApi { page -> if (page == 2) throw ProblemException(Problem.Code.RATE_LIMITED) else pages(page) })

        downloads.downloadSearch(source, "pikachu", 500)
        downloads.settled()

        assertEquals((1L..42L).toSet(), downloads.queuedIds())
        val batch = downloads.batches.value.singleOrNull()
        assertEquals(Problem(Problem.Code.RATE_LIMITED), batch?.problem)
        assertEquals(42, batch?.found)
        assertEquals(2, batch?.nextPage)
    }

    @Test
    fun `a stopped batch picks up where it stopped when retried`() = runBlocking {
        val pages = sized(42, 100, 37)
        var failing = true
        val api = FakeApi { page -> if (page == 2 && failing) throw ProblemException(Problem.Code.RATE_LIMITED) else pages(page) }
        val downloads = manager(api)
        downloads.downloadSearch(source, "pikachu", 500)
        val stopped = downloads.settled().single()

        failing = false
        downloads.retryBatch(stopped.key)
        downloads.settled()

        assertEquals((1L..179L).toSet(), downloads.queuedIds())
        assertEquals(listOf(1, 2, 2, 3, 4), api.asked)
        assertTrue(downloads.batches.value.isEmpty())
    }

    @Test
    fun `a stopped batch is still there, stopped, after a lock`() = runBlocking {
        val pages = sized(42, 100, 37)
        val api = FakeApi { page -> if (page == 2) throw ProblemException(Problem.Code.RATE_LIMITED) else pages(page) }
        val downloads = manager(api)
        downloads.downloadSearch(source, "pikachu", 500)
        downloads.settled()
        withTimeout(10_000) { downloads.tasks.first { list -> list.none { it.active } } }

        vault.lock()
        assertEquals(0, downloads.paused.value)
        assertTrue(vault.unlock(password.toCharArray()))
        withTimeout(5000) { vault.state.first { it is VaultState.Unlocked } }

        val batch = downloads.batches.value.singleOrNull()
        assertEquals(Problem(Problem.Code.RATE_LIMITED), batch?.problem)
        assertEquals(42, batch?.found)
        assertEquals(2, batch?.nextPage)
        assertEquals("not started again by the unlock", listOf(1, 2), api.asked)
    }

    @Test
    fun `ten pages in a row the filters empty end the collection`() = runBlocking {
        // A site of fifty full pages, none of whose posts the default filters allow.
        val api = FakeApi { page -> if (page > 50) emptyList() else (1..100).map { post(page * 1000L + it, Rating.EXPLICIT) } }
        val downloads = manager(api)

        downloads.downloadSearch(source, "pikachu", 500)
        downloads.settled()

        assertEquals((1..10).toList(), api.asked)
        assertTrue(downloads.queuedIds().isEmpty())
        assertTrue(downloads.batches.value.isEmpty())
    }

    @Test
    fun `a page with something allowed starts the count of empty ones again`() = runBlocking {
        val api = FakeApi { page ->
            when {
                page > 50 -> emptyList()
                page == 10 -> listOf(post(10_001))
                else -> (1..100).map { post(page * 1000L + it, Rating.EXPLICIT) }
            }
        }
        val downloads = manager(api)

        downloads.downloadSearch(source, "pikachu", 500)
        downloads.settled()

        assertEquals((1..20).toList(), api.asked)
        assertEquals(setOf(10_001L), downloads.queuedIds())
    }
}
