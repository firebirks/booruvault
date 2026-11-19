package io.github.firebirks.booruvault.vault

import io.github.firebirks.booruvault.booru.MediaKind
import io.github.firebirks.booruvault.booru.Post
import io.github.firebirks.booruvault.booru.Rating
import kotlinx.serialization.Serializable

/** A bookmarked set of tags: the vault shows, as a collection, every item that matches it. */
@Serializable
data class TagCollection(val id: String, val name: String, val query: String, val createdAt: Long = System.currentTimeMillis())

/**
 * What a query is matched against: an item in the vault, or a post read from a site. A post has
 * no file name, and its source is named only where the caller knows it.
 */
class QuerySubject(
    val tags: List<String>,
    val rating: Rating,
    val kind: MediaKind,
    val fileName: String? = null,
    val sourceName: String? = null,
)

fun VaultItem.asSubject() = QuerySubject(
    tags,
    rating,
    when {
        isVideo -> MediaKind.VIDEO
        isAnimated -> MediaKind.ANIMATED
        else -> MediaKind.IMAGE
    },
    fileName,
    sourceName,
)

fun Post.asSubject(sourceName: String? = null) = QuerySubject(tagNames, rating, kind, sourceName = sourceName)

/**
 * Booru-style queries, over vault items and over posts. Terms are space-separated and all must
 * hold: `tag` (exact), `-term` excludes, `~tag` terms are alternatives of which at least one must
 * hold, and `type:video|gif|image`, `source:name` and `rating:` narrow. A rating is named by its
 * initial, as the app labels them (g, s, q, e, u: general, sensitive, questionable, explicit,
 * unrated), or in full, and several are given with commas: `rating:q,e`; "safe" is general, as
 * most boards call it. A term with `*` as a wildcard matches a whole tag, the file name or the
 * source.
 *
 * The vault's filter box speaks the same language, [loose]ly: there a plain word is found inside
 * any tag, the file name or the source, so "land" finds "landscape" as one types. A filter
 * saved as a collection is [widened] first, so the collection holds what the filter showed. Each
 * line of the blacklist is a query too, and hides the posts it matches: see [rule].
 */
object TagQuery {
    fun matcher(query: String, loose: Boolean = false): (VaultItem) -> Boolean {
        val match = rule(query, loose)
        return { item -> match(item.asSubject()) }
    }

    /** [query] as a test of a post or an item; a query with no terms holds for everything. */
    fun rule(query: String, loose: Boolean = false): (QuerySubject) -> Boolean {
        val terms = query.trim().split(Regex("\\s+")).filter { it.isNotBlank() }.map { it.lowercase() }
        val required = ArrayList<(QuerySubject) -> Boolean>()
        val alternatives = ArrayList<(QuerySubject) -> Boolean>()
        for (term in terms) {
            when {
                term.startsWith('-') && term.length > 1 -> {
                    val inner = term(term.drop(1), loose)
                    required += { !inner(it) }
                }
                term.startsWith('~') && term.length > 1 -> alternatives += term(term.drop(1), loose)
                else -> required += term(term, loose)
            }
        }
        return { subject -> required.all { it(subject) } && (alternatives.isEmpty() || alternatives.any { it(subject) }) }
    }

    /**
     * The exact query that shows what the [loose] one does: each plain word becomes `*word*`.
     * Terms that already narrow, or carry a wildcard, are left as they are.
     */
    fun widened(query: String): String = query.trim().split(Regex("\\s+")).filter { it.isNotBlank() }.joinToString(" ") { term ->
        val prefix = term.takeWhile { it == '-' || it == '~' }.take(1)
        val word = term.drop(prefix.length)
        if (word.isEmpty() || ':' in word || '*' in word) term else "$prefix*$word*"
    }

    private fun term(term: String, loose: Boolean): (QuerySubject) -> Boolean = when {
        term.startsWith("type:") -> when (term.removePrefix("type:")) {
            "video" -> { subject -> subject.kind == MediaKind.VIDEO }
            "gif" -> { subject -> subject.kind == MediaKind.ANIMATED }
            "image" -> { subject -> subject.kind == MediaKind.IMAGE }
            else -> { _ -> false }
        }
        term.startsWith("source:") -> {
            val name = term.removePrefix("source:")
            ({ subject -> subject.sourceName?.lowercase()?.contains(name) == true })
        }
        term.startsWith("rating:") -> {
            val ratings = ratingsOf(term.removePrefix("rating:"))
            ({ subject -> subject.rating in ratings })
        }
        '*' in term -> {
            val pattern = Regex(term.split('*').joinToString(".*") { Regex.escape(it) })
            ({ subject ->
                subject.tags.any { pattern.matches(it.lowercase()) } ||
                    subject.fileName?.lowercase()?.let(pattern::matches) == true ||
                    subject.sourceName?.lowercase()?.let(pattern::matches) == true
            })
        }
        loose -> { subject ->
            subject.tags.any { it.contains(term, ignoreCase = true) } ||
                subject.fileName?.contains(term, ignoreCase = true) == true ||
                subject.sourceName?.contains(term, ignoreCase = true) == true
        }
        else -> { subject -> subject.tags.any { it.equals(term, ignoreCase = true) } }
    }

    /** The ratings [value] names, comma-separated; one it does not know names none. */
    internal fun ratingsOf(value: String): Set<Rating> = value.split(',').mapNotNullTo(HashSet()) { ratingOf(it.trim()) }

    private fun ratingOf(value: String): Rating? = when (value) {
        "g", "general", "safe" -> Rating.GENERAL
        "s", "sensitive" -> Rating.SENSITIVE
        "q", "questionable" -> Rating.QUESTIONABLE
        "e", "explicit" -> Rating.EXPLICIT
        "u", "unknown", "unrated" -> Rating.UNKNOWN
        else -> null
    }
}
