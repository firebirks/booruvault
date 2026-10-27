package app.booruvault.booru

import app.booruvault.util.Problem
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URLEncoder

/**
 * Any web page: the images and videos on it. The address may contain `{tags}` (the search,
 * URL-encoded) and `{page}` (from 1); without `{page}` there is a single page.
 */
class GenericHtmlApi(source: BooruSource, client: OkHttpClient, json: Json) : BaseBooruApi(source, client, json) {

    override suspend fun posts(tags: String, page: Int, limit: Int): List<Post> {
        val template = source.baseUrl.trim()
        if (page > 1 && "{page}" !in template) return emptyList()
        val address = template
            .replace("{tags}", URLEncoder.encode(tags.trim(), "UTF-8"))
            .replace("{page}", page.toString())
        val url = address.toHttpUrlOrNull() ?: throw BooruException(Problem.Code.BAD_URL, address)
        return GenericHtml.extract(getHtml(url), url.toString(), source.id)
    }

    override suspend fun autocomplete(query: String): List<TagSuggestion> = emptyList()
}

object GenericHtml {
    private val media = setOf("jpg", "jpeg", "png", "gif", "webp", "avif", "bmp", "mp4", "webm", "mov", "m4v")
    private const val MIN_SIZE = 100

    fun isMedia(url: String): Boolean = url.extensionFromUrl() in media

    /**
     * In order: links to media files (with the image inside them as preview), videos, then images
     * on their own. Icons and other small images are skipped where the page states a size.
     */
    fun extract(html: String, pageUrl: String, sourceId: String): List<Post> {
        val doc = Jsoup.parse(html, pageUrl)
        val found = LinkedHashMap<String, Post>()

        fun add(file: String, preview: String?, width: Int = 0, height: Int = 0) {
            if (file.isBlank() || file.startsWith("data:") || file.extensionFromUrl() == "svg" || file in found) return
            found[file] = Post(
                sourceId = sourceId,
                id = stableId(file),
                previewUrl = preview ?: file,
                sampleUrl = null,
                fileUrl = file,
                width = width,
                height = height,
                fileExt = file.extensionFromUrl()?.takeIf { it in media } ?: "jpg",
                rating = Rating.UNKNOWN,
                source = pageUrl,
                postUrl = pageUrl,
            )
        }

        doc.select("a[href]").forEach { a ->
            val href = a.absUrl("href")
            if (isMedia(href)) add(href, a.selectFirst("img")?.let(::bestSource))
        }
        doc.select("video").forEach { video ->
            val src = video.absUrl("src").ifBlank { video.selectFirst("source[src]")?.absUrl("src").orEmpty() }
            add(src, video.absUrl("poster").ifBlank { null })
        }
        doc.select("img").forEach { img ->
            if (img.parents().any { it.tagName() == "a" && isMedia(it.absUrl("href")) }) return@forEach
            if (tiny(img)) return@forEach
            val src = bestSource(img) ?: return@forEach
            add(src, src, img.attr("width").toIntOrNull() ?: 0, img.attr("height").toIntOrNull() ?: 0)
        }
        doc.selectFirst("meta[property=og:image]")?.absUrl("content")?.let { add(it, it) }
        return found.values.toList()
    }

    private fun tiny(img: Element): Boolean {
        val w = img.attr("width").toIntOrNull()
        val h = img.attr("height").toIntOrNull()
        return (w != null && w < MIN_SIZE) || (h != null && h < MIN_SIZE)
    }

    /** The largest `srcset` candidate, then the usual lazy-loading attributes, then `src`. */
    private fun bestSource(img: Element): String? {
        val base = img.baseUri().toHttpUrlOrNull()
        val srcset = (img.attr("srcset").ifBlank { img.attr("data-srcset") }).split(',').mapNotNull { part ->
            val pieces = part.trim().split(Regex("\\s+"))
            val url = pieces.firstOrNull()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val weight = pieces.getOrNull(1)?.dropLast(1)?.toDoubleOrNull() ?: 1.0
            (base?.resolve(url)?.toString() ?: url) to weight
        }.maxByOrNull { it.second }?.first
        return srcset
            ?: listOf("data-src", "data-original", "data-lazy-src", "src").map { img.absUrl(it) }.firstOrNull { it.isNotBlank() && !it.startsWith("data:") }
    }

    /** A positive id that stays the same for the same file across visits. */
    fun stableId(url: String): Long {
        var hash = -0x340d631b7bdddcdbL // FNV-1a 64-bit offset basis
        for (byte in url.toByteArray()) {
            hash = hash xor (byte.toLong() and 0xff)
            hash *= 0x100000001b3L
        }
        return hash and Long.MAX_VALUE
    }
}
