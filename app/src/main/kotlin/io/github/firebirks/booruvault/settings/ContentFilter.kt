package io.github.firebirks.booruvault.settings

import io.github.firebirks.booruvault.booru.Post
import io.github.firebirks.booruvault.vault.QuerySubject
import io.github.firebirks.booruvault.vault.TagQuery
import io.github.firebirks.booruvault.vault.asSubject

/** Whether a post passes the rating filter and the blacklist. */
fun AppSettings.allows(post: Post): Boolean {
    if (post.rating !in allowedRatings) return false
    val rules = Blacklist.of(blacklist)
    if (rules.isEmpty()) return true
    val subject = post.asSubject()
    return rules.none { it(subject) }
}

/**
 * The blacklist's rules, one a line, each a query in the language of the vault's collections
 * (see [TagQuery]): a post that matches a whole rule is hidden. Read once for the text it was
 * given, since every post shown, and every one downloaded, is held against all of them.
 */
private object Blacklist {
    @Volatile
    private var last: Pair<String, List<(QuerySubject) -> Boolean>>? = null

    fun of(text: String): List<(QuerySubject) -> Boolean> {
        last?.let { (source, rules) -> if (source == text) return rules }
        val rules = blacklistLines(text).map { TagQuery.rule(it) }
        last = text to rules
        return rules
    }
}

/** The blacklist's rules as written: its lines that say something. */
fun blacklistLines(text: String): List<String> = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
