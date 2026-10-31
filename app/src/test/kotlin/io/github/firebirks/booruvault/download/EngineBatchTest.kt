package io.github.firebirks.booruvault.download

import io.github.firebirks.booruvault.booru.BooruApiFactory
import io.github.firebirks.booruvault.booru.BooruEngine
import io.github.firebirks.booruvault.booru.BooruSource
import io.github.firebirks.booruvault.booru.Rating
import io.github.firebirks.booruvault.settings.SettingsRepository
import io.github.firebirks.booruvault.vault.VaultManager
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
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Downloading many results works on every engine: each fake site below pages its posts the way
 * the real one does, page sizes and the answer past the last page included, and the batch has to
 * queue every post exactly once.
 */
class EngineBatchTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    /** Every address asked for, in order. */
    private val asked = CopyOnWriteArrayList<HttpUrl>()

    @After
    fun tearDown() {
        managerScope.cancel()
        scope.cancel()
    }

    /** A site that answers each address with [answer]'s status and body; anything else is a 404. */
    private fun site(answer: (HttpUrl) -> Pair<Int, String>?) = OkHttpClient.Builder().addInterceptor { chain ->
        val url = chain.request().url
        asked += url
        val (code, body) = answer(url) ?: (404 to "")
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("x")
            .body(body.toResponseBody((if (body.startsWith("<")) "text/html" else "application/json").toMediaType())).build()
    }.build()

    /** Collects up to [max] results of [source] from [client], and returns the ids it queued. */
    private fun collect(source: BooruSource, client: OkHttpClient, max: Int = 500, ratings: Set<Rating> = setOf(Rating.GENERAL)): List<Long> = runBlocking {
        val root = tmp.newFolder()
        val vault = VaultManager(root, json)
        val settings = SettingsRepository(root, vault, json, scope)
        vault.create("pw-123456".toCharArray(), Kdf.newParams(memoryKiB = 1024, iterations = 1))
        withTimeout(5000) { settings.ready.first { it } }
        settings.setRatings(ratings)
        val store = VaultStore(root, vault, json, scope)
        val api = BooruApiFactory.create(source, client, json)
        val downloads = DownloadManager({}, { api }, client, { false }, { _, _ -> false }, store, vault, settings, json, root, managerScope)
        downloads.downloadSearch(source, "t", max)
        withTimeout(20_000) { downloads.batches.first { list -> list.none { it.active } } }
        assertEquals("a batch stopped by a problem: ${downloads.batches.value.map { it.problem }}", emptyList<Any>(), downloads.batches.value)
        downloads.tasks.value.map { it.post.id }
    }

    private fun HttpUrl.int(name: String) = queryParameter(name)?.toIntOrNull()

    /** Ids [from] to [to] of a site of [total] posts, as the page asked for holds them. */
    private fun page(total: Int, offset: Int, size: Int): LongRange = (offset + 1).toLong()..minOf(total, offset + size).toLong()

    @Test
    fun danbooru() {
        val total = 250
        val client = site { url ->
            if (url.encodedPath != "/posts.json") return@site null
            val limit = minOf(url.int("limit")!!, 200)
            200 to page(total, (url.int("page")!! - 1) * limit, limit).joinToString(",", "[", "]") {
                """{"id":$it,"rating":"g","file_ext":"jpg","file_url":"https://cdn.danbooru.test/$it.jpg","tag_string":"t"}"""
            }
        }
        val queued = collect(BooruSource("dan", "Dan", BooruEngine.DANBOORU, "https://danbooru.test"), client)
        assertEquals((1L..total).toList(), queued.sorted())
    }

    @Test
    fun moebooru() {
        val total = 230
        val client = site { url ->
            if (url.encodedPath != "/post.json") return@site null
            val limit = minOf(url.int("limit")!!, 100)
            200 to page(total, (url.int("page")!! - 1) * limit, limit).joinToString(",", "[", "]") {
                """{"id":$it,"rating":"s","file_url":"https://files.moe.test/$it.jpg","tags":"t"}"""
            }
        }
        val queued = collect(BooruSource("moe", "Moe", BooruEngine.MOEBOORU, "https://moe.test"), client)
        assertEquals((1L..total).toList(), queued.sorted())
    }

    @Test
    fun `a gelbooru fork, whose API answers bare arrays`() {
        val total = 210
        val client = site { url ->
            if (url.queryParameter("page") != "dapi") return@site null
            val limit = minOf(url.int("limit")!!, 100)
            200 to page(total, url.int("pid")!! * limit, limit).joinToString(",", "[", "]") {
                """{"id":$it,"rating":"general","file_url":"https://img.fork.test/$it.jpg","image":"$it.jpg","directory":"aa","tags":"t"}"""
            }
        }
        val queued = collect(BooruSource("fork", "Fork", BooruEngine.GELBOORU, "https://fork.test"), client)
        assertEquals((1L..total).toList(), queued.sorted())
    }

    @Test
    fun `gelbooru with an API key, whose API wraps the posts and leaves them out past the end`() {
        val total = 205
        val client = site { url ->
            if (url.queryParameter("page") != "dapi") return@site null
            val limit = minOf(url.int("limit")!!, 100)
            val ids = page(total, url.int("pid")!! * limit, limit)
            val attributes = """"@attributes":{"limit":$limit,"offset":${url.int("pid")!! * limit},"count":$total}"""
            200 to if (ids.isEmpty()) "{$attributes}" else ids.joinToString(",", "{$attributes,\"post\":[", "]}") {
                """{"id":$it,"rating":"general","file_url":"https://img.gel.test/$it.jpg","image":"$it.jpg","directory":"aa","tags":"t"}"""
            }
        }
        val queued = collect(BooruSource("gel-key", "Gel", BooruEngine.GELBOORU, "https://gelkey.test", "1", "key"), client)
        assertEquals((1L..total).toList(), queued.sorted())
    }

    /**
     * A list page as Gelbooru draws it: [perPage] thumbnails from [offset], and a paginator that
     * links first, previous, the pages around this one, next and last, but not this one.
     */
    private fun gelbooruList(total: Int, offset: Int, perPage: Int, thumb: (Long) -> String): String {
        val ids = page(total, offset, perPage)
        if (ids.isEmpty()) return "<div><h1>Nobody here but us chickens!</h1></div>"
        val pages = (total + perPage - 1) / perPage
        val current = offset / perPage
        fun link(page: Int, text: String) = """<a href="?page=post&amp;s=list&amp;tags=t&amp;pid=${page * perPage}">$text</a>"""
        val paginator = buildString {
            if (current > 0) append(link(0, "&laquo;")).append(link(current - 1, "&lsaquo;"))
            for (p in maxOf(0, current - 4)..minOf(pages - 1, current + 4)) append(if (p == current) "<b>${p + 1}</b>" else link(p, "${p + 1}"))
            if (current < pages - 1) append(link(current + 1, "&rsaquo;")).append(link(pages - 1, "&raquo;"))
        }
        return """<div class="thumbnail-container">${ids.joinToString("", transform = thumb)}</div><div id="paginator">$paginator</div>"""
    }

    @Test
    fun `gelbooru without an API key, read from its 42-post web pages`() {
        val total = 200
        val client = site { url ->
            when (url.queryParameter("page")) {
                "dapi" -> 401 to ""
                "post" -> if (url.queryParameter("s") != "list") null else 200 to gelbooruList(total, url.int("pid")!!, 42) {
                    """<article class="thumbnail-preview"><a id="p$it" href="https://gelweb.test/index.php?page=post&amp;s=view&amp;id=$it&amp;tags=t"><img src="https://img.gelweb.test/thumbnails/aa/thumbnail_$it.jpg" title=" t score:0 rating:general"/></a></article>"""
                }
                else -> null
            }
        }
        val queued = collect(BooruSource("gel-web", "Gelbooru", BooruEngine.GELBOORU, "https://gelweb.test"), client)
        assertEquals((1L..total).toList(), queued.sorted())
        assertEquals(listOf(0, 42, 84, 126, 168, 210), asked.filter { it.queryParameter("s") == "list" }.map { it.int("pid") })
    }

    @Test
    fun `a booru_org site, which has only web pages of 20 posts`() {
        val total = 75
        val client = site { url ->
            if (url.queryParameter("page") != "post" || url.queryParameter("s") != "list") return@site null
            200 to gelbooruList(total, url.int("pid")!!, 20) {
                """<span class="thumb"><a id="p$it" href="index.php?page=post&amp;s=view&amp;id=$it"><img src="https://thumbs.booru.test/thumbnail_$it.png" title=" t score:0 rating:Safe"/></a></span>"""
            }
        }
        val queued = collect(BooruSource("borg", "Borg", BooruEngine.GELBOORU_HTML, "https://site.booru.test"), client)
        assertEquals((1L..total).toList(), queued.sorted())
    }

    @Test
    fun e621() {
        val total = 330
        val client = site { url ->
            if (url.encodedPath != "/posts.json") return@site null
            val limit = minOf(url.int("limit")!!, 320)
            200 to page(total, (url.int("page")!! - 1) * limit, limit).joinToString(",", "{\"posts\":[", "]}") {
                """{"id":$it,"rating":"s","file":{"url":"https://static.e6.test/$it.jpg","ext":"jpg"},"tags":{"general":["t"]}}"""
            }
        }
        val queued = collect(BooruSource("e6", "E6", BooruEngine.E621, "https://e6.test"), client)
        assertEquals((1L..total).toList(), queued.sorted())
    }

    @Test
    fun `a shimmie site, which pages by path and answers 404 past the end`() {
        val total = 100
        val perPage = 24
        val client = site { url ->
            val segments = url.pathSegments
            if (segments.take(2) != listOf("post", "list")) return@site null
            val ids = page(total, (segments.last().toInt() - 1) * perPage, perPage)
            if (ids.isEmpty()) return@site 404 to "<p>No posts matched</p>"
            200 to ids.joinToString("", "<div class='shm-image-list'>", "</div>") {
                """<div class='shm-thumb thumb' data-mime='image/jpeg' data-post-id='$it'><a class='shm-thumb-link' href='/post/view/$it'><img title='t 10x10 // 1KB // jpg' src='/_thumbs/$it.jpg'/></a></div>"""
            }
        }
        val queued = collect(BooruSource("shm", "Shm", BooruEngine.SHIMMIE, "https://shimmie.test"), client, ratings = setOf(Rating.GENERAL, Rating.UNKNOWN))
        assertEquals((1L..total).toList(), queued.sorted())
    }

    @Test
    fun `a web page whose address names its page`() {
        val total = 45
        val perPage = 20
        val client = site { url ->
            if (url.encodedPath != "/gallery") return@site null
            200 to page(total, (url.int("p")!! - 1) * perPage, perPage).joinToString("", "<div>", "</div>") {
                """<a href="https://pages.test/full/$it.jpg"><img src="https://pages.test/thumb/$it.jpg"></a>"""
            }
        }
        val queued = collect(BooruSource("web", "Web", BooruEngine.HTML_GENERIC, "https://pages.test/gallery?p={page}"), client, ratings = setOf(Rating.GENERAL, Rating.UNKNOWN))
        assertEquals(total, queued.toSet().size)
    }
}
