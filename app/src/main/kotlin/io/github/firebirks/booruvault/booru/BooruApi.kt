package io.github.firebirks.booruvault.booru

import io.github.firebirks.booruvault.util.Problem
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

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

    protected open fun request(url: HttpUrl): Request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()

    protected suspend fun get(url: HttpUrl) = client.getJson(request(url), json)

    protected suspend fun getHtml(url: HttpUrl): String = client.getText(request(url), json)

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
    fun create(source: BooruSource, client: OkHttpClient, json: Json): BooruApi = when (source.engine) {
        BooruEngine.DANBOORU -> DanbooruApi(source, client, json)
        BooruEngine.MOEBOORU -> MoebooruApi(source, client, json)
        BooruEngine.GELBOORU -> GelbooruApi(source, client, json)
        BooruEngine.E621 -> E621Api(source, client, json)
        BooruEngine.GELBOORU_HTML -> GelbooruApi(source, client, json, htmlOnly = true)
        BooruEngine.SHIMMIE -> ShimmieApi(source, client, json)
        BooruEngine.HTML_GENERIC -> GenericHtmlApi(source, client, json)
    }
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
