package io.github.firebirks.booruvault.booru

import io.github.firebirks.booruvault.util.Problem
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient

/**
 * Gelbooru 0.2 and its forks (safebooru.org, rule34.xxx, xbooru, tbib...), and with [htmlOnly]
 * Gelbooru 0.1 (booru.org) sites that have no API at all.
 *
 * gelbooru.com and rule34.xxx answer 401 without a user id and API key. When the API refuses or
 * is switched off, the site's own web pages are read instead; posts found that way get their
 * file URLs from their page when opened.
 */
class GelbooruApi(
    source: BooruSource,
    client: OkHttpClient,
    json: Json,
    private val htmlOnly: Boolean = false,
) : BaseBooruApi(source, client, json) {
    private val apiKey get() = "$baseString|${source.login}|${source.apiKey.hashCode()}"

    /** The web pages live on the main host, not on an `api.` one (rule34.xxx). */
    private val webBase by lazy { baseString.replace("://api.", "://").toHttpUrl() }

    private fun dapi(): HttpUrl.Builder = base.newBuilder().addPathSegment("index.php")
        .addQueryParameter("page", "dapi")
        .addQueryParameter("q", "index")
        .addQueryParameter("json", "1")
        .apply {
            if (source.hasCredentials) {
                addQueryParameter("user_id", source.login.trim())
                addQueryParameter("api_key", source.apiKey.trim())
            }
        }

    override suspend fun posts(tags: String, page: Int, limit: Int): List<Post> {
        if (htmlOnly || apiKey in apiRefused) return htmlPosts(tags, page)
        return try {
            apiPosts(tags, page, limit)
        } catch (e: BooruChallengeException) {
            throw e
        } catch (e: BooruException) {
            // A network failure would hit the web pages too; anything else may be the API alone.
            if (e.cause is java.io.IOException) throw e
            val fallback = try {
                htmlPosts(tags, page, requireList = true)
            } catch (challenge: BooruChallengeException) {
                throw challenge
            } catch (other: BooruException) {
                throw e
            }
            apiRefused += apiKey
            fallback
        }
    }

    private suspend fun apiPosts(tags: String, page: Int, limit: Int): List<Post> {
        val url = dapi().addQueryParameter("s", "post")
            .addQueryParameter("tags", tags.trim())
            .addQueryParameter("pid", (page - 1).toString())
            .addQueryParameter("limit", limit.toString())
            .build()
        val element = get(url) ?: return emptyList()
        // gelbooru.com wraps posts in {"@attributes": ..., "post": [...]}; the forks return a bare array.
        val list = element.arr() ?: element.obj()?.get("post").arr() ?: return emptyList()
        return list.mapNotNull { parse(it.obj() ?: return@mapNotNull null) }
    }

    private fun parse(o: JsonObject): Post? {
        val id = o.long("id") ?: return null
        val directory = o.str("directory")
        val image = o.str("image")
        val stem = image?.substringBeforeLast('.')
        val fileUrl = absolute(o.str("file_url"))
            ?: if (directory != null && image != null) "$baseString/images/$directory/$image" else null
        fileUrl ?: return null
        val hasSample = o.bool("sample") == true
        val sample = absolute(o.str("sample_url"))?.takeIf { it != fileUrl }
            ?: if (hasSample && directory != null && stem != null) "$baseString/samples/$directory/sample_$stem.jpg" else null
        val preview = absolute(o.str("preview_url"))
            ?: if (directory != null && stem != null) "$baseString/thumbnails/$directory/thumbnail_$stem.jpg" else null
        val webHost = baseString.replace("://api.", "://")
        return Post(
            sourceId = source.id,
            id = id,
            previewUrl = preview,
            sampleUrl = sample,
            fileUrl = fileUrl,
            width = o.int("width") ?: 0,
            height = o.int("height") ?: 0,
            fileExt = image?.substringAfterLast('.', "")?.lowercase()?.ifBlank { null } ?: fileUrl.extensionFromUrl() ?: "jpg",
            md5 = o.str("md5") ?: o.str("hash"),
            rating = when (o.str("rating")?.lowercase()) {
                "general", "safe", "s", "g" -> Rating.GENERAL
                "sensitive" -> Rating.SENSITIVE
                "questionable", "q" -> Rating.QUESTIONABLE
                "explicit", "e" -> Rating.EXPLICIT
                else -> Rating.UNKNOWN
            },
            score = o.int("score"),
            tags = o.str("tags").orEmpty().split(' ').filter { it.isNotBlank() }.map { Tag(it) },
            source = o.str("source"),
            createdAt = parseGelbooruDate(o.str("created_at")) ?: o.long("change")?.let { it * 1000 },
            postUrl = "$webHost/index.php?page=post&s=view&id=$id",
        )
    }

    private suspend fun htmlPosts(tags: String, page: Int, requireList: Boolean = false): List<Post> {
        val perPage = if (page == 1) null else pageSizes[webBase.host] ?: run {
            // The offset of page N needs the page size, which only a list page tells.
            htmlList(tags, 0)
            pageSizes[webBase.host]
        } ?: return emptyList()
        val list = htmlList(tags, (page - 1) * (perPage ?: 0))
        if (requireList && !list.looksLikeList) throw BooruFormatException(Problem.Code.UNRECOGNISED_PAGES)
        return list.posts
    }

    private suspend fun htmlList(tags: String, offset: Int): GelbooruHtml.ListPage {
        val url = webBase.newBuilder().addPathSegment("index.php")
            .addQueryParameter("page", "post")
            .addQueryParameter("s", "list")
            .addQueryParameter("tags", tags.trim().ifBlank { "all" })
            .addQueryParameter("pid", offset.toString())
            .build()
        val list = GelbooruHtml.parseList(getHtml(url), url.toString(), source.id)
        list.perPage?.let { pageSizes[webBase.host] = it }
        return list
    }

    override suspend fun resolve(post: Post): Post {
        if (!post.needsResolve) return post
        val url = post.postUrl.toHttpUrlOrNull() ?: throw BooruException(Problem.Code.BAD_URL, post.postUrl)
        return GelbooruHtml.parsePost(getHtml(url), post.postUrl, post)
    }

    override suspend fun autocomplete(query: String): List<TagSuggestion> {
        // Each fork exposes a different endpoint; try them from most to least informative.
        val attempts = listOf(
            base.newBuilder().addPathSegment("index.php")
                .addQueryParameter("page", "autocomplete2")
                .addQueryParameter("term", query)
                .addQueryParameter("type", "tag_query")
                .addQueryParameter("limit", "12").build(),
            base.newBuilder().addPathSegment("autocomplete.php").addQueryParameter("q", query).build(),
        )
        for (url in attempts) {
            val list = runCatching { get(url).arr() }.getOrNull() ?: continue
            val suggestions = list.mapNotNull { it.obj() }.mapNotNull { o ->
                val name = o.str("value") ?: return@mapNotNull null
                // autocomplete.php puts the count in the label: "name (123)".
                val count = o.long("post_count")
                    ?: o.str("label")?.substringAfterLast('(')?.substringBefore(')')?.toLongOrNull()
                TagSuggestion(name, count, gelbooruCategory(o.str("category")))
            }
            if (suggestions.isNotEmpty()) return suggestions
        }
        return emptyList()
    }

    private companion object {
        /** Sites whose API turned us away this session: go straight to their pages. */
        val apiRefused: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

        /** Posts per list page, learned from each site's paginator. */
        val pageSizes = java.util.concurrent.ConcurrentHashMap<String, Int>()
    }

    private fun gelbooruCategory(name: String?): TagCategory = when (name) {
        "artist" -> TagCategory.ARTIST
        "copyright" -> TagCategory.COPYRIGHT
        "character" -> TagCategory.CHARACTER
        "metadata", "meta" -> TagCategory.META
        else -> TagCategory.GENERAL
    }
}
