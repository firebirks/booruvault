package io.github.firebirks.booruvault.booru

import io.github.firebirks.booruvault.util.HasProblem
import io.github.firebirks.booruvault.util.Problem
import kotlinx.serialization.Serializable

@Serializable
enum class Rating {
    GENERAL,
    SENSITIVE,
    QUESTIONABLE,
    EXPLICIT,

    /** Sites without ratings (Shimmie2, plain web pages); filtered on their own switch. */
    UNKNOWN,
}

@Serializable
enum class TagCategory { GENERAL, ARTIST, COPYRIGHT, CHARACTER, SPECIES, META, OTHER }

@Serializable
data class Tag(val name: String, val category: TagCategory = TagCategory.GENERAL)

enum class MediaKind { IMAGE, ANIMATED, VIDEO }

@Serializable
data class Post(
    val sourceId: String,
    val id: Long,
    val previewUrl: String?,
    val sampleUrl: String?,
    val fileUrl: String?,
    val width: Int,
    val height: Int,
    val fileExt: String,
    val fileSize: Long? = null,
    val md5: String? = null,
    val rating: Rating = Rating.UNKNOWN,
    val score: Int? = null,
    val tags: List<Tag> = emptyList(),
    val source: String? = null,
    val createdAt: Long? = null,
    val postUrl: String,
    /**
     * Came from an HTML list that shows only thumbnails: the file URLs, and often the details, are
     * on the post's own page and are fetched by [BooruApi.resolve] when the post is opened.
     */
    val needsResolve: Boolean = false,
) {
    val kind: MediaKind
        get() = when (fileExt) {
            "mp4", "webm", "m4v", "mov" -> MediaKind.VIDEO
            "gif" -> MediaKind.ANIMATED
            else -> MediaKind.IMAGE
        }

    val tagNames: List<String> get() = tags.map { it.name }

    /** Largest version, falling back to the sample when the original is withheld. */
    val bestUrl: String? get() = fileUrl ?: sampleUrl

    fun viewUrl(original: Boolean): String? =
        if (original || kind == MediaKind.VIDEO) fileUrl ?: sampleUrl else sampleUrl ?: fileUrl

    val aspectRatio: Float
        get() = if (width > 0 && height > 0) (width.toFloat() / height).coerceIn(0.4f, 2.5f) else 1f
}

@Serializable
enum class BooruEngine(val html: Boolean = false) {
    DANBOORU,
    MOEBOORU,
    GELBOORU,
    E621,

    /** Gelbooru 0.1 (booru.org) and 0.2 sites read through their web pages. */
    GELBOORU_HTML(html = true),
    SHIMMIE(html = true),

    /** Any page: its images and videos. The address may hold {tags} and {page}. */
    HTML_GENERIC(html = true),
}

/** A configured site. [login] is the user name, or the numeric user id on Gelbooru. */
@Serializable
data class BooruSource(
    val id: String,
    val name: String,
    val engine: BooruEngine,
    val baseUrl: String,
    val login: String = "",
    val apiKey: String = "",
) {
    val hasCredentials: Boolean get() = login.isNotBlank() && apiKey.isNotBlank()
}

data class TagSuggestion(val name: String, val count: Long?, val category: TagCategory)

open class BooruException(final override val problem: Problem, cause: Throwable? = null) :
    Exception(problem.detail ?: problem.code.name, cause), HasProblem {
    constructor(code: Problem.Code, detail: String? = null, cause: Throwable? = null) : this(Problem(code, detail), cause)
}

/** The site refused or failed the request with an HTTP status. */
class BooruHttpException(val code: Int, problem: Problem) : BooruException(problem)

/** The site answered, but not in the shape the engine expects (e.g. HTML where JSON was due). */
class BooruFormatException(code: Problem.Code, cause: Throwable? = null) : BooruException(Problem(code), cause)

/** The site answered with an anti-bot page (Cloudflare) that only a person in a browser can pass. */
class BooruChallengeException(val url: String) : BooruException(Problem(Problem.Code.CHALLENGE))
