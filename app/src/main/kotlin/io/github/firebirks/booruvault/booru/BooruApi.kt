package io.github.firebirks.booruvault.booru

import io.github.firebirks.booruvault.util.Problem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Reads a page some other way when the HTTP client keeps being sent to an anti-bot check. */
fun interface HtmlFetcher {
    suspend fun fetch(url: String): String
}

interface BooruApi {
    /** [page] starts at 1. */
    suspend fun posts(tags: String, page: Int, limit: Int): List<Post>

    suspend fun autocomplete(query: String): List<TagSuggestion>

    /** Fills in a post that came from an HTML list ([Post.needsResolve]); others pass through. */
    suspend fun resolve(post: Post): Post = post
}

abstract class BaseBooruApi(
    protected val source: BooruSource,
    protected val client: OkHttpClient,
    protected val json: Json,
) : BooruApi {
    protected val base: HttpUrl by lazy {
        try {
            source.baseUrl.trim().trimEnd('/').toHttpUrl()
        } catch (e: IllegalArgumentException) {
            throw BooruException(Problem.Code.BAD_URL, source.baseUrl)
        }
    }

    protected val baseString: String by lazy { base.toString().trimEnd('/') }

    protected open fun request(url: HttpUrl): Request = Request.Builder().url(url).header("User-Agent", UserAgents.forSource(source)).build()

    protected suspend fun get(url: HttpUrl) = client.getJson(request(url), json)

    /** Set by the factory when the user lets challenged pages be read in a hidden browser. */
    internal var pages: HtmlFetcher? = null

    protected suspend fun getHtml(url: HttpUrl): String = try {
        client.getText(request(url), json)
    } catch (e: BooruChallengeException) {
        pages?.fetch(url.toString()) ?: throw e
    }

    /** Some sites hand out protocol-relative or root-relative links. */
    protected fun absolute(url: String?): String? = when {
        url.isNullOrBlank() -> null
        url.startsWith("//") -> "https:$url"
        url.startsWith("/") -> baseString + url
        else -> url
    }

    protected fun parseIso(value: String?): Long? = value?.let {
        runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull()
    }
}

object BooruApiFactory {
    fun create(source: BooruSource, client: OkHttpClient, json: Json, pages: HtmlFetcher? = null): BooruApi =
        OffMainThread(engine(source, client, json).also { (it as? BaseBooruApi)?.pages = pages })

    private fun engine(source: BooruSource, client: OkHttpClient, json: Json): BooruApi = when (source.engine) {
        BooruEngine.DANBOORU -> DanbooruApi(source, client, json)
        BooruEngine.MOEBOORU -> MoebooruApi(source, client, json)
        BooruEngine.GELBOORU -> GelbooruApi(source, client, json)
        BooruEngine.E621 -> E621Api(source, client, json)
        BooruEngine.GELBOORU_HTML -> GelbooruApi(source, client, json, htmlOnly = true)
        BooruEngine.SHIMMIE -> ShimmieApi(source, client, json)
        BooruEngine.HTML_GENERIC -> GenericHtmlApi(source, client, json)
    }
}

/**
 * Runs every call on the IO dispatcher. Callers are often view models on the main thread, and
 * reading a response body or parsing a page there is refused by Android (NetworkOnMainThread).
 */
private class OffMainThread(private val api: BooruApi) : BooruApi {
    override suspend fun posts(tags: String, page: Int, limit: Int) = withContext(Dispatchers.IO) { api.posts(tags, page, limit) }
    override suspend fun autocomplete(query: String) = withContext(Dispatchers.IO) { api.autocomplete(query) }
    override suspend fun resolve(post: Post) = withContext(Dispatchers.IO) { api.resolve(post) }
}

/** The tag being typed: the last word, without a leading exclusion or "or" marker. */
fun autocompleteToken(query: String): Pair<String, String> {
    val last = query.substringAfterLast(' ')
    val prefix = last.takeWhile { it == '-' || it == '~' }
    return prefix to last.drop(prefix.length)
}

internal val gelbooruDate: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE MMM dd HH:mm:ss Z yyyy", Locale.US)

internal fun parseGelbooruDate(value: String?): Long? = value?.let {
    runCatching { ZonedDateTime.parse(it, gelbooruDate).toInstant().toEpochMilli() }.getOrNull()
}
