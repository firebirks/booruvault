package io.github.firebirks.booruvault.booru

import io.github.firebirks.booruvault.util.Problem
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl
import okhttp3.OkHttpClient

/**
 * yande.re, konachan and other Moebooru sites. Credentials are the user name and the password
 * hash the site expects, which [passwordHash] computes; the password itself is never kept.
 */
class MoebooruApi(source: BooruSource, client: OkHttpClient, json: Json) : BaseBooruApi(source, client, json) {

    companion object {
        /** Moebooru's `password_hash`: SHA-1 of the password inside the salt every Moebooru site uses. */
        fun passwordHash(password: String): String =
            java.security.MessageDigest.getInstance("SHA-1").digest("choujin-steiner--$password--".toByteArray())
                .joinToString("") { "%02x".format(it) }
    }

    private fun HttpUrl.Builder.auth(): HttpUrl.Builder = apply {
        if (source.hasCredentials) {
            addQueryParameter("login", source.login.trim())
            addQueryParameter("password_hash", source.apiKey.trim())
        }
    }

    override suspend fun posts(tags: String, page: Int, limit: Int): List<Post> {
        val url = base.newBuilder().addPathSegment("post.json")
            .addQueryParameter("tags", tags.trim())
            .addQueryParameter("page", page.toString())
            .addQueryParameter("limit", limit.toString())
            .auth().build()
        val element = get(url)
        element.obj()?.let { o -> throw BooruException(Problem.Code.SITE_MESSAGE, o.str("reason") ?: o.str("message")) }
        return element.arr().orEmpty().mapNotNull { parse(it.obj() ?: return@mapNotNull null) }
    }

    private fun parse(o: JsonObject): Post? {
        val id = o.long("id") ?: return null
        val fileUrl = absolute(o.str("file_url"))
        val sample = absolute(o.str("sample_url"))
        if (fileUrl == null && sample == null) return null
        val ext = o.str("file_ext")?.lowercase() ?: fileUrl?.extensionFromUrl() ?: "jpg"
        return Post(
            sourceId = source.id,
            id = id,
            previewUrl = absolute(o.str("preview_url")),
            sampleUrl = sample?.takeIf { it != fileUrl } ?: absolute(o.str("jpeg_url"))?.takeIf { it != fileUrl },
            fileUrl = fileUrl,
            width = o.int("width") ?: 0,
            height = o.int("height") ?: 0,
            fileExt = ext,
            fileSize = o.long("file_size"),
            md5 = o.str("md5"),
            rating = when (o.str("rating")) {
                "s" -> Rating.GENERAL
                "q" -> Rating.QUESTIONABLE
                "e" -> Rating.EXPLICIT
                else -> Rating.UNKNOWN
            },
            score = o.int("score"),
            tags = o.str("tags").orEmpty().split(' ').filter { it.isNotBlank() }.map { Tag(it) },
            source = o.str("source"),
            createdAt = o.long("created_at")?.let { it * 1000 },
            postUrl = "$baseString/post/show/$id",
        )
    }

    override suspend fun autocomplete(query: String): List<TagSuggestion> {
        val url = base.newBuilder().addPathSegment("tag.json")
            .addQueryParameter("name", "$query*")
            .addQueryParameter("order", "count")
            .addQueryParameter("limit", "12")
            .build()
        return get(url).arr().orEmpty().mapNotNull { it.obj() }.mapNotNull { o ->
            TagSuggestion(o.str("name") ?: return@mapNotNull null, o.long("count"), moebooruCategory(o.int("type")))
        }
    }
}

/** Moebooru's tag types: Danbooru's up to character, then circle (5) and faults (6). */
internal fun moebooruCategory(code: Int?): TagCategory = when (code) {
    0 -> TagCategory.GENERAL
    1 -> TagCategory.ARTIST
    3 -> TagCategory.COPYRIGHT
    4 -> TagCategory.CHARACTER
    else -> TagCategory.OTHER
}
