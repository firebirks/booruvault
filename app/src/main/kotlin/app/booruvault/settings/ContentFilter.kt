package app.booruvault.settings

import app.booruvault.booru.Post

/** Whether a post passes the rating filter and the blacklist. */
fun AppSettings.allows(post: Post): Boolean {
    if (post.rating !in allowedRatings) return false
    val rules = blacklistRules
    if (rules.isEmpty()) return true
    val tags = post.tags.mapTo(HashSet()) { it.name.lowercase() }
    tags += "rating:${post.rating.name.lowercase()}"
    return rules.none { rule -> tags.containsAll(rule) }
}
