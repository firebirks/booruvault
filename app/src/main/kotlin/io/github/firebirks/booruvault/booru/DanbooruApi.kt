package io.github.firebirks.booruvault.booru

import io.github.firebirks.booruvault.util.Problem
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl
import okhttp3.OkHttpClient

class DanbooruApi(source: BooruSource, client: OkHttpClient, json: Json) : BaseBooruApi(source, client, json) {

    private fun HttpUrl.Builder.auth(): HttpUrl.Builder = apply {
        if (source.hasCredentials) {
            addQueryParameter("login", source.login.trim())
            addQueryParameter("api_key", source.apiKey.trim())
        }
    }

    override suspend fun posts(tags: String, page: Int, limit: Int): List<Post> {
        val url = base.newBuilder().addPathSegment("posts.json")
            .addQueryParameter("tags", tags.trim())
            .addQueryParameter("page", page.toString())
            .addQueryParameter("limit", limit.toString())
            .auth().build()
        val element = get(url)
        // Errors come back as {"success": false, "message": ...}.
        element.obj()?.let { o -> throw BooruException(Problem.Code.SITE_MESSAGE, o.str("message")) }
        return element.arr().orEmpty().mapNotNull { parse(it.obj() ?: return@mapNotNull null) }
    }

    private fun parse(o: JsonObject): Post? {
        val id = o.long("id") ?: return null
        var ext = o.str("file_ext")?.lowercase() ?: "jpg"
        var fileUrl = absolute(o.str("file_url"))
        val large = absolute(o.str("large_file_url"))
        // Ugoira originals are zip archives of frames; the sample is a playable webm.
        if (ext == "zip" && large != null && large.extensionFromUrl() in setOf("webm", "mp4")) {
            fileUrl = large
            ext = large.extensionFromUrl()!!
        }
        if (fileUrl == null && large == null) return null // restricted or banned post
        val variants = o["media_asset"].obj()?.get("variants").arr().orEmpty().mapNotNull { it.obj() }
        val preview = variants.firstOrNull { it.str("type") == "360x360" }?.str("url")
            ?: absolute(o.str("preview_file_url"))
        val tags = buildList {
            fun add(key: String, category: TagCategory) =
                o.str(key)?.split(' ')?.filter { it.isNotBlank() }?.forEach { add(Tag(it, category)) }
            add("tag_string_artist", TagCategory.ARTIST)
            add("tag_string_copyright", TagCategory.COPYRIGHT)
            add("tag_string_character", TagCategory.CHARACTER)
            add("tag_string_general", TagCategory.GENERAL)
            add("tag_string_meta", TagCategory.META)
        }.ifEmpty { o.str("tag_string").orEmpty().split(' ').filter { it.isNotBlank() }.map { Tag(it) } }
        return Post(
            sourceId = source.id,
            id = id,
            previewUrl = preview,
            sampleUrl = if (ext == "zip") null else large,
            fileUrl = fileUrl,
            width = o.int("image_width") ?: 0,
            height = o.int("image_height") ?: 0,
            fileExt = ext,
            fileSize = o.long("file_size"),
            md5 = o.str("md5"),
            rating = when (o.str("rating")) {
                "g" -> Rating.GENERAL
                "s" -> Rating.SENSITIVE
                "q" -> Rating.QUESTIONABLE
                "e" -> Rating.EXPLICIT
                else -> Rating.UNKNOWN
            },
            score = o.int("score"),
            tags = tags,
            source = o.str("source"),
            createdAt = parseIso(o.str("created_at")),
            postUrl = "$baseString/posts/$id",
        )
    }

    override suspend fun autocomplete(query: String): List<TagSuggestion> {
        val url = base.newBuilder().addPathSegment("tags.json")
            .addQueryParameter("search[name_matches]", "$query*")
            .addQueryParameter("search[order]", "count")
            .addQueryParameter("search[hide_empty]", "true")
            .addQueryParameter("limit", "12")
            .auth().build()
        return get(url).arr().orEmpty().mapNotNull { it.obj() }.mapNotNull { o ->
            TagSuggestion(o.str("name") ?: return@mapNotNull null, o.long("post_count"), danbooruCategory(o.int("category")))
        }
    }
}

internal fun danbooruCategory(code: Int?): TagCategory = when (code) {
    0 -> TagCategory.GENERAL
    1 -> TagCategory.ARTIST
    3 -> TagCategory.COPYRIGHT
    4 -> TagCategory.CHARACTER
    5 -> TagCategory.META
    else -> TagCategory.OTHER
}
