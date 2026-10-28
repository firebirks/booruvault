package io.github.firebirks.booruvault.booru

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * e621 / e926. Their API rejects generic user agents, so it names the app, and the account when
 * the request is signed with it; an anonymous session is not tied to a name.
 */
class E621Api(source: BooruSource, client: OkHttpClient, json: Json) : BaseBooruApi(source, client, json) {

    override fun request(url: HttpUrl): Request = Request.Builder().url(url)
        .header("User-Agent", "BooruVault/0.1 (by ${if (source.hasCredentials) source.login.trim() else "anonymous"} on e621)")
        .apply { if (source.hasCredentials) header("Authorization", Credentials.basic(source.login.trim(), source.apiKey.trim(), Charsets.UTF_8)) }
        .build()

    override suspend fun posts(tags: String, page: Int, limit: Int): List<Post> {
        val url = base.newBuilder().addPathSegment("posts.json")
            .addQueryParameter("tags", tags.trim())
            .addQueryParameter("page", page.toString())
            .addQueryParameter("limit", limit.toString())
            .build()
        val root = get(url).obj() ?: return emptyList()
        return root["posts"].arr().orEmpty().mapNotNull { parse(it.obj() ?: return@mapNotNull null) }
    }

    private fun parse(o: JsonObject): Post? {
        val id = o.long("id") ?: return null
        val file = o["file"].obj() ?: return null
        // Without an account e621 withholds the URL of some posts.
        val fileUrl = file.str("url") ?: return null
        val sample = o["sample"].obj()?.takeIf { it.bool("has") == true }?.str("url")
        val tagGroups = o["tags"].obj()
        val tags = buildList {
            fun add(key: String, category: TagCategory) =
                tagGroups?.get(key).arr()?.forEach { el -> (el as? kotlinx.serialization.json.JsonPrimitive)?.content?.let { add(Tag(it, category)) } }
            add("artist", TagCategory.ARTIST)
            add("copyright", TagCategory.COPYRIGHT)
            add("character", TagCategory.CHARACTER)
            add("species", TagCategory.SPECIES)
            add("general", TagCategory.GENERAL)
            add("lore", TagCategory.OTHER)
            add("meta", TagCategory.META)
        }
        return Post(
            sourceId = source.id,
            id = id,
            previewUrl = o["preview"].obj()?.str("url"),
            sampleUrl = sample?.takeIf { it != fileUrl },
            fileUrl = fileUrl,
            width = file.int("width") ?: 0,
            height = file.int("height") ?: 0,
            fileExt = file.str("ext")?.lowercase() ?: fileUrl.extensionFromUrl() ?: "jpg",
            fileSize = file.long("size"),
            md5 = file.str("md5"),
            rating = when (o.str("rating")) {
                "s" -> Rating.GENERAL
                "q" -> Rating.QUESTIONABLE
                "e" -> Rating.EXPLICIT
                else -> Rating.UNKNOWN
            },
            score = o["score"].obj()?.int("total"),
            tags = tags,
            source = o["sources"].arr()?.firstOrNull()?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content },
            createdAt = parseIso(o.str("created_at")),
            postUrl = "$baseString/posts/$id",
        )
    }

    override suspend fun autocomplete(query: String): List<TagSuggestion> {
        val url = base.newBuilder().addPathSegments("tags/autocomplete.json")
            .addQueryParameter("search[name_matches]", query)
            .addQueryParameter("expiry", "7")
            .build()
        return get(url).arr().orEmpty().mapNotNull { it.obj() }.mapNotNull { o ->
            TagSuggestion(
                o.str("name") ?: return@mapNotNull null,
                o.long("post_count"),
                when (o.int("category")) {
                    0 -> TagCategory.GENERAL
                    1 -> TagCategory.ARTIST
                    3 -> TagCategory.COPYRIGHT
                    4 -> TagCategory.CHARACTER
                    5 -> TagCategory.SPECIES
                    7 -> TagCategory.META
                    else -> TagCategory.OTHER
                },
            )
        }
    }
}
