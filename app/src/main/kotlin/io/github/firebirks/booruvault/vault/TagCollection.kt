package io.github.firebirks.booruvault.vault

import io.github.firebirks.booruvault.booru.Rating
import kotlinx.serialization.Serializable

/** A bookmarked set of tags: the vault shows, as a collection, every item that matches it. */
@Serializable
data class TagCollection(val id: String, val name: String, val query: String, val createdAt: Long = System.currentTimeMillis())

/**
 * Booru-style queries over vault items. Terms are space-separated and all must hold:
 * `tag` (exact, `*` as wildcard), `-term` excludes, `~tag` terms are alternatives of which at
 * least one must hold, and `type:video|gif|image`, `source:name`, `rating:<rating>` narrow.
 */
object TagQuery {
    fun matcher(query: String): (VaultItem) -> Boolean {
        val terms = query.trim().split(Regex("\\s+")).filter { it.isNotBlank() }.map { it.lowercase() }
        val required = ArrayList<(VaultItem) -> Boolean>()
        val alternatives = ArrayList<(VaultItem) -> Boolean>()
        for (term in terms) {
            when {
                term.startsWith('-') && term.length > 1 -> {
                    val inner = term(term.drop(1))
                    required += { !inner(it) }
                }
                term.startsWith('~') && term.length > 1 -> alternatives += term(term.drop(1))
                else -> required += term(term)
            }
        }
        return { item -> required.all { it(item) } && (alternatives.isEmpty() || alternatives.any { it(item) }) }
    }

    private fun term(term: String): (VaultItem) -> Boolean = when {
        term.startsWith("type:") -> when (term.removePrefix("type:")) {
            "video" -> { item -> item.isVideo }
            "gif" -> { item -> item.isAnimated }
            "image" -> { item -> !item.isVideo && !item.isAnimated }
            else -> { _ -> false }
        }
        term.startsWith("source:") -> {
            val name = term.removePrefix("source:")
            ({ item -> item.sourceName?.lowercase()?.contains(name) == true })
        }
        term.startsWith("rating:") -> {
            val rating = ratingOf(term.removePrefix("rating:"))
            ({ item -> item.rating == rating })
        }
        '*' in term -> {
            val pattern = Regex(term.split('*').joinToString(".*") { Regex.escape(it) })
            ({ item -> item.tags.any { pattern.matches(it.lowercase()) } })
        }
        else -> { item -> item.tags.any { it.equals(term, ignoreCase = true) } }
    }

    private fun ratingOf(value: String): Rating? = when (value) {
        "g", "general", "s", "safe" -> Rating.GENERAL
        "sensitive" -> Rating.SENSITIVE
        "q", "questionable" -> Rating.QUESTIONABLE
        "e", "explicit" -> Rating.EXPLICIT
        "unknown", "unrated" -> Rating.UNKNOWN
        else -> null
    }
}
