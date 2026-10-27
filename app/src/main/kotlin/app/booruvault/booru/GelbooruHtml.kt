package app.booruvault.booru

import app.booruvault.util.Problem
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * The web pages of Gelbooru 0.1 (booru.org) and 0.2 sites. A list page carries thumbnails whose
 * `title` holds the tags plus `score:` and `rating:`; the post page carries the file.
 */
object GelbooruHtml {
    class ListPage(val posts: List<Post>, val perPage: Int?, val looksLikeList: Boolean)

    fun parseList(html: String, pageUrl: String, sourceId: String): ListPage {
        val doc = Jsoup.parse(html, pageUrl)
        val posts = doc.select("span.thumb, article.thumbnail-preview, div.thumbnail-preview").mapNotNull { thumb(it, sourceId) }
        // The paginator links by offset; the smallest one is the page size.
        val perPage = doc.select("#paginator a[href], .pagination a[href]")
            .mapNotNull { Regex("[?&]pid=(\\d+)").find(it.attr("href"))?.groupValues?.get(1)?.toIntOrNull() }
            .filter { it > 0 }
            .minOrNull()
        val looksLikeList = posts.isNotEmpty() || doc.selectFirst("#paginator, .pagination") != null ||
            html.contains("Nobody here but us chickens", ignoreCase = true)
        return ListPage(posts, perPage, looksLikeList)
    }

    private fun thumb(el: Element, sourceId: String): Post? {
        val link = el.selectFirst("a[href*=s=view]") ?: el.selectFirst("a[id^=p]") ?: return null
        val id = Regex("[?&]id=(\\d+)").find(link.attr("href"))?.groupValues?.get(1)?.toLongOrNull()
            ?: link.id().removePrefix("p").toLongOrNull()
            ?: return null
        val img = link.selectFirst("img") ?: el.selectFirst("img")
        var rating = Rating.UNKNOWN
        var score: Int? = null
        val tags = ArrayList<Tag>()
        img?.attr("title").orEmpty().split(Regex("\\s+")).filter { it.isNotBlank() }.forEach { token ->
            when {
                token.startsWith("rating:", ignoreCase = true) -> rating = ratingOf(token.substringAfter(':'))
                token.startsWith("score:", ignoreCase = true) -> score = token.substringAfter(':').toIntOrNull()
                else -> tags += Tag(token)
            }
        }
        val names = tags.mapTo(HashSet()) { it.name }
        val video = img?.className().orEmpty().contains("webm", ignoreCase = true) ||
            ("video" in names && ("webm" in names || "mp4" in names))
        return Post(
            sourceId = sourceId,
            id = id,
            previewUrl = img?.let { it.absUrl("src").ifBlank { it.absUrl("data-src") } }?.ifBlank { null },
            sampleUrl = null,
            fileUrl = null,
            width = 0,
            height = 0,
            fileExt = if (video) "mp4" else "jpg",
            rating = rating,
            score = score,
            tags = tags,
            postUrl = link.absUrl("href").replace(Regex("&tags=[^&]*"), ""),
            needsResolve = true,
        )
    }

    fun parsePost(html: String, pageUrl: String, post: Post): Post {
        val doc = Jsoup.parse(html, pageUrl)
        val image = doc.selectFirst("img#image")?.absUrl("src")?.ifBlank { null }
        val video = doc.selectFirst("video source[src]")?.absUrl("src")?.ifBlank { null }
            ?: doc.selectFirst("video[src]")?.absUrl("src")?.ifBlank { null }
        val original = video
            ?: doc.select("a[href]").firstOrNull { it.ownText().trim().equals("Original image", ignoreCase = true) }?.absUrl("href")
            ?: doc.selectFirst("meta[property=og:image]")?.absUrl("content")?.ifBlank { null }
            ?: image
            ?: throw BooruFormatException(Problem.Code.NO_IMAGE_ON_PAGE)
        val text = doc.text()
        val size = Regex("Size:\\s*(\\d+)\\s*x\\s*(\\d+)", RegexOption.IGNORE_CASE).find(text)
        val rating = Regex("Rating:\\s*([A-Za-z]+)").find(text)?.groupValues?.get(1)?.let(::ratingOf)
        val score = Regex("Score:\\s*(-?\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull()
        return post.copy(
            fileUrl = original,
            sampleUrl = image?.takeIf { it != original && video == null },
            fileExt = original.extensionFromUrl() ?: post.fileExt,
            width = size?.groupValues?.get(1)?.toIntOrNull() ?: post.width,
            height = size?.groupValues?.get(2)?.toIntOrNull() ?: post.height,
            rating = rating?.takeIf { it != Rating.UNKNOWN } ?: post.rating,
            score = score ?: post.score,
            tags = categorisedTags(doc).ifEmpty { post.tags },
            needsResolve = false,
        )
    }

    /** The sidebar's `tag-type-*` entries; the tag is the `tags=` of its search link. */
    private fun categorisedTags(doc: Document): List<Tag> = doc.select("li[class*=tag-type-]").mapNotNull { li ->
        val type = Regex("tag-type-([a-z]+)").find(li.className())?.groupValues?.get(1)
        val link = li.select("a[href*=tags=]").lastOrNull() ?: return@mapNotNull null
        val name = Regex("[?&]tags=([^&]+)").find(link.attr("href"))?.groupValues?.get(1)
            ?.let { java.net.URLDecoder.decode(it, "UTF-8") }
            ?: link.text()
        Tag(
            name.trim().replace(' ', '_'),
            when (type) {
                "artist" -> TagCategory.ARTIST
                "copyright" -> TagCategory.COPYRIGHT
                "character" -> TagCategory.CHARACTER
                "metadata", "meta" -> TagCategory.META
                else -> TagCategory.GENERAL
            },
        )
    }.distinctBy { it.name }

    fun ratingOf(value: String): Rating = when (value.lowercase()) {
        "general", "safe", "s", "g" -> Rating.GENERAL
        "sensitive" -> Rating.SENSITIVE
        "questionable", "q" -> Rating.QUESTIONABLE
        "explicit", "e" -> Rating.EXPLICIT
        else -> Rating.UNKNOWN
    }
}
