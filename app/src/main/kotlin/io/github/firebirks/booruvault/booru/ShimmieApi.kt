package io.github.firebirks.booruvault.booru

import io.github.firebirks.booruvault.util.Problem
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/** Shimmie2 sites (rule34.paheal and many small boorus), read through their web pages. */
class ShimmieApi(source: BooruSource, client: OkHttpClient, json: Json) : BaseBooruApi(source, client, json) {

    override suspend fun posts(tags: String, page: Int, limit: Int): List<Post> {
        val builder = base.newBuilder().addPathSegments("post/list")
        val search = tags.trim()
        if (search.isNotEmpty()) builder.addPathSegment(search)
        val url = builder.addPathSegment(page.toString()).build()
        val html = try {
            getHtml(url)
        } catch (e: BooruHttpException) {
            // Past the last page Shimmie answers 404 with "No posts matched".
            if (e.code == 404) return emptyList() else throw e
        }
        return ShimmieHtml.parseList(html, url.toString(), source.id)
    }

    override suspend fun resolve(post: Post): Post {
        if (!post.needsResolve) return post
        val url = post.postUrl.toHttpUrlOrNull() ?: throw BooruException(Problem.Code.BAD_URL, post.postUrl)
        return ShimmieHtml.parsePost(getHtml(url), post.postUrl, post)
    }

    /** `{"tag": count}` on most versions, `{"tag": {"count": n}}` on newer ones. */
    override suspend fun autocomplete(query: String): List<TagSuggestion> {
        val url = base.newBuilder().addPathSegments("api/internal/autocomplete").addQueryParameter("s", query).build()
        val root = runCatching { get(url) }.getOrNull() as? JsonObject ?: return emptyList()
        return root.entries.map { (name, value) ->
            val count = (value as? JsonPrimitive)?.longOrNull ?: (value as? JsonObject)?.long("count")
            TagSuggestion(name, count, TagCategory.GENERAL)
        }.sortedByDescending { it.count ?: 0 }.take(12)
    }
}

object ShimmieHtml {
    fun parseList(html: String, pageUrl: String, sourceId: String): List<Post> {
        val doc = Jsoup.parse(html, pageUrl)
        return doc.select("div.shm-thumb[data-post-id], a.shm-thumb[data-post-id]").mapNotNull { thumb(it, sourceId) }
    }

    private fun thumb(el: Element, sourceId: String): Post? {
        val id = el.attr("data-post-id").toLongOrNull() ?: return null
        val link = if (el.tagName() == "a") el else el.selectFirst("a.shm-thumb-link") ?: el.selectFirst("a[href*=/post/view/]") ?: return null
        val img = el.selectFirst("img")
        val title = img?.attr("title").orEmpty()
        val dims = Regex("(\\d+)x(\\d+)").find(title)
        val ext = Mimes.extension(el.attr("data-mime"))
            ?: Regex("//\\s*([a-z0-9]{2,5})\\b").findAll(title).lastOrNull()?.groupValues?.get(1)
            ?: "jpg"
        // Some sites put the file itself next to the thumbnail ("File Only" / "Image Only").
        val direct = el.select("a[href]").firstOrNull {
            val text = it.ownText().trim()
            text.equals("File Only", ignoreCase = true) || text.equals("Image Only", ignoreCase = true)
        }?.absUrl("href")?.ifBlank { null }
        return Post(
            sourceId = sourceId,
            id = id,
            previewUrl = img?.absUrl("src")?.ifBlank { null },
            sampleUrl = null,
            fileUrl = direct,
            width = dims?.groupValues?.get(1)?.toIntOrNull() ?: 0,
            height = dims?.groupValues?.get(2)?.toIntOrNull() ?: 0,
            fileExt = ext,
            rating = el.attr("data-rating").takeIf { it.isNotBlank() }?.let(GelbooruHtml::ratingOf) ?: Rating.UNKNOWN,
            tags = el.attr("data-tags").split(' ').filter { it.isNotBlank() }.map { Tag(it) },
            postUrl = link.absUrl("href"),
            needsResolve = direct == null,
        )
    }

    fun parsePost(html: String, pageUrl: String, post: Post): Post {
        val doc = Jsoup.parse(html, pageUrl)
        val main = doc.selectFirst("#main_image")
        val file = when {
            main == null -> null
            main.tagName() == "video" -> main.selectFirst("source[src]")?.absUrl("src") ?: main.absUrl("src")
            else -> main.absUrl("src")
        }?.ifBlank { null }
            ?: doc.selectFirst("meta[property=og:image]")?.absUrl("content")?.ifBlank { null }
            ?: throw BooruFormatException(Problem.Code.NO_IMAGE_ON_PAGE)
        return post.copy(
            fileUrl = file,
            fileExt = Mimes.extension(main?.attr("data-mime").orEmpty()) ?: file.extensionFromUrl() ?: post.fileExt,
            width = main?.attr("data-width")?.toIntOrNull() ?: post.width,
            height = main?.attr("data-height")?.toIntOrNull() ?: post.height,
            needsResolve = false,
        )
    }
}

internal object Mimes {
    fun extension(mime: String): String? = when (mime.substringBefore(';').trim().lowercase()) {
        "image/jpeg" -> "jpg"
        "image/png" -> "png"
        "image/gif" -> "gif"
        "image/webp" -> "webp"
        "image/avif" -> "avif"
        "video/mp4" -> "mp4"
        "video/webm" -> "webm"
        "video/quicktime" -> "mov"
        else -> null
    }
}
