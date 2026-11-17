package io.github.firebirks.booruvault.download

import io.github.firebirks.booruvault.booru.BooruApi
import io.github.firebirks.booruvault.booru.BooruEngine
import io.github.firebirks.booruvault.booru.BooruSource
import io.github.firebirks.booruvault.booru.Post
import io.github.firebirks.booruvault.booru.Rating
import io.github.firebirks.booruvault.booru.Tag
import io.github.firebirks.booruvault.booru.TagSuggestion
import io.github.firebirks.booruvault.settings.SettingsRepository
import io.github.firebirks.booruvault.util.Problem
import io.github.firebirks.booruvault.vault.VaultManager
import io.github.firebirks.booruvault.vault.VaultState
import io.github.firebirks.booruvault.vault.VaultStore
import io.github.firebirks.booruvault.vault.crypto.Kdf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A post queued from an HTML list page is known only by its thumbnail until its own page is
 * read, and that page may say a rating or tags the filters hide: such a post is not saved. The
 * filters are the user's, not the defaults, also for the downloads a lock left queued.
 */
class DownloadFilterTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** The settings' own, so a test can stop them being read back at an unlock. */
    private val settingsScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val fastKdf get() = Kdf.newParams(memoryKiB = 1024, iterations = 1)
    private val password = "pw-123456"
    private val source = BooruSource("gelbooru", "Gelbooru", BooruEngine.GELBOORU_HTML, "https://gelbooru.example")

    private lateinit var vault: VaultManager
    private lateinit var settings: SettingsRepository
    private lateinit var store: VaultStore

    @After
    fun tearDown() {
        managerScope.cancel()
        settingsScope.cancel()
        scope.cancel()
    }

    /** As an HTML list gives it: no rating, no tags, no file. Nothing is ever sent anywhere. */
    private fun listed(id: Long) =
        Post(source.id, id, null, null, null, 1, 1, "jpg", postUrl = "https://gelbooru.example/post/$id", needsResolve = true)

    /** Reads the post's page as [page] says, once [open] lets it. */
    private class PageApi(
        private val open: CompletableDeferred<Unit> = CompletableDeferred(Unit),
        private val page: (Post) -> Post,
    ) : BooruApi {
        val reading = CompletableDeferred<Unit>()

        override suspend fun posts(tags: String, page: Int, limit: Int) = emptyList<Post>()
        override suspend fun autocomplete(query: String) = emptyList<TagSuggestion>()
        override suspend fun resolve(post: Post): Post {
            reading.complete(Unit)
            open.await()
            return page(post).copy(needsResolve = false)
        }
    }

    private suspend fun manager(api: BooruApi, ratings: Set<Rating>, blacklist: String = ""): DownloadManager {
        val root = tmp.newFolder()
        vault = VaultManager(root, json)
        settings = SettingsRepository(root, vault, json, settingsScope)
        vault.create(password.toCharArray(), fastKdf)
        withTimeout(5000) { settings.ready.first { it } }
        settings.setRatings(ratings)
        settings.setBlacklist(blacklist)
        store = VaultStore(root, vault, json, scope)
        return DownloadManager({}, { api }, OkHttpClient(), { false }, { _, _ -> false }, store, vault, settings, json, root, managerScope, timeoutRetryPauseMs = 0)
    }

    private suspend fun DownloadManager.settled() =
        withTimeout(10_000) { tasks.first { list -> list.isNotEmpty() && list.none { it.active } }.single() }

    @Test
    fun `a post whose page says a rating the filters hide is not saved`() = runBlocking {
        val downloads = manager(PageApi { it.copy(rating = Rating.EXPLICIT) }, setOf(Rating.GENERAL, Rating.UNKNOWN))

        downloads.enqueue(listOf(listed(1)), source)
        val task = downloads.settled()

        assertEquals(DownloadStatus.SKIPPED, task.status)
        assertEquals(Problem.Code.FILTERED, task.problem?.code)
        assertEquals(Rating.EXPLICIT, task.post.rating)
        assertTrue(store.items.value.isEmpty())
    }

    @Test
    fun `a post whose page names a blacklisted tag is not saved`() = runBlocking {
        val api = PageApi { it.copy(rating = Rating.GENERAL, tags = listOf(Tag("landscape"), Tag("gore"))) }
        val downloads = manager(api, setOf(Rating.GENERAL, Rating.UNKNOWN), blacklist = "gore")

        downloads.enqueue(listOf(listed(1)), source)
        val task = downloads.settled()

        assertEquals(DownloadStatus.SKIPPED, task.status)
        assertEquals(Problem.Code.FILTERED, task.problem?.code)
    }

    @Test
    fun `a post whose page says nothing the filters hide goes on`() = runBlocking {
        val downloads = manager(PageApi { it.copy(rating = Rating.GENERAL) }, setOf(Rating.GENERAL, Rating.UNKNOWN))

        downloads.enqueue(listOf(listed(1)), source)
        val task = downloads.settled()

        // On to the file, which this post has none of.
        assertEquals(DownloadStatus.FAILED, task.status)
        assertEquals(Problem.Code.NO_FILE, task.problem?.code)
    }

    /** Leaves the vault locked with the one download [api] is reading the page of, sealed as queued. */
    private suspend fun DownloadManager.lockedWhileReading(api: PageApi) {
        enqueue(listOf(listed(1)), source)
        withTimeout(5000) { api.reading.await() }
        vault.lock()
        withTimeout(5000) { paused.first { it == 1 } }
    }

    @Test
    fun `a download a lock left queued waits at the next unlock for the user's settings`() = runBlocking {
        val open = CompletableDeferred<Unit>()
        val api = PageApi(open) { it.copy(rating = Rating.EXPLICIT) }
        val downloads = manager(api, setOf(Rating.GENERAL, Rating.EXPLICIT))
        downloads.lockedWhileReading(api)
        open.complete(Unit)
        // The settings are never read back: with the defaults, which hide explicit posts, the
        // download would be turned away.
        withTimeout(5000) { settings.ready.first { !it } }
        settingsScope.cancel()

        assertTrue(vault.unlock(password.toCharArray()))

        val finished = withTimeoutOrNull(1000) { downloads.tasks.first { list -> list.isNotEmpty() && list.none { it.active } } }
        assertEquals(null, finished)
        assertEquals(DownloadStatus.QUEUED, downloads.tasks.value.single().status)
    }

    @Test
    fun `a download a lock left queued follows the user's filters at the next unlock`() = runBlocking {
        val open = CompletableDeferred<Unit>()
        val api = PageApi(open) { it.copy(rating = Rating.EXPLICIT) }
        val downloads = manager(api, setOf(Rating.GENERAL, Rating.EXPLICIT))
        downloads.lockedWhileReading(api)
        open.complete(Unit)

        assertTrue(vault.unlock(password.toCharArray()))
        assertTrue(vault.state.value is VaultState.Unlocked)

        // Through the filters, which allow explicit posts, on to the file, which it has none of.
        assertEquals(Problem.Code.NO_FILE, downloads.settled().problem?.code)
    }
}
