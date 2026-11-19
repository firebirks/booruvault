package io.github.firebirks.booruvault.settings

import io.github.firebirks.booruvault.booru.Post
import io.github.firebirks.booruvault.booru.Rating
import io.github.firebirks.booruvault.booru.Tag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Each line of the blacklist is a rule written as a search, in the language of the vault's
 * collections: a post that matches a whole rule is hidden. Rules from before, plain tags and
 * `rating:` in full, keep hiding what they hid.
 */
class BlacklistTest {
    private val everyRating = Rating.entries.toSet()

    private fun post(id: Long, vararg tags: String, rating: Rating = Rating.GENERAL, ext: String = "jpg") =
        Post("s", id, null, null, null, 1, 1, ext, rating = rating, tags = tags.map { Tag(it) }, postUrl = "https://example.org/$id")

    private val posts = listOf(
        post(1, "landscape", "sky"),
        post(2, "cat", "solo", rating = Rating.EXPLICIT),
        post(3, "cat", "dog", rating = Rating.EXPLICIT),
        post(4, "breast_focus", "solo", rating = Rating.QUESTIONABLE),
        post(5, "Gore", "blood", rating = Rating.SENSITIVE),
        post(6, "dance", ext = "webm"),
    )

    /** The ids of the posts [blacklist] hides, with every rating allowed. */
    private fun hidden(blacklist: String): List<Long> {
        val settings = AppSettings(allowedRatings = everyRating, blacklist = blacklist)
        return posts.filterNot { settings.allows(it) }.map { it.id }
    }

    @Test
    fun `plain tags and ratings in full hide what they always did`() {
        assertEquals(listOf(5L), hidden("gore"))
        assertEquals(listOf(2L, 3L), hidden("rating:explicit cat"))
        assertEquals(emptyList<Long>(), hidden("rating:explicit landscape"))
    }

    @Test
    fun `a rating is named by its initial, and several by commas`() {
        assertEquals(listOf(2L, 3L), hidden("rating:e"))
        assertEquals(listOf(5L), hidden("rating:s"))
        assertEquals(listOf(2L, 3L, 4L), hidden("rating:q,e"))
        assertEquals(listOf(1L, 6L), hidden("rating:g"))
    }

    @Test
    fun `a tag the rule excludes keeps the post`() {
        assertEquals(listOf(3L), hidden("cat -solo"))
        assertEquals(listOf(4L), hidden("solo -rating:e"))
    }

    @Test
    fun `alternatives hide a post that has any of them`() {
        assertEquals(listOf(3L, 5L), hidden("~dog ~blood"))
        assertEquals(listOf(2L, 3L), hidden("rating:e ~cat ~gore"))
    }

    @Test
    fun `a wildcard stands for any letters of a whole tag`() {
        assertEquals(listOf(4L), hidden("*_focus"))
        assertEquals(listOf(1L), hidden("land*"))
        assertEquals(emptyList<Long>(), hidden("land"))
    }

    @Test
    fun `the kind of file can be named`() {
        assertEquals(listOf(6L), hidden("type:video"))
    }

    @Test
    fun `every line is a rule of its own, and blank ones are nothing`() {
        assertEquals(listOf(2L, 3L, 5L), hidden("\n  rating:e  \n\ngore\n"))
        assertEquals(emptyList<Long>(), hidden("\n \n"))
    }

    @Test
    fun `the ratings the filter leaves out stay out whatever the blacklist says`() {
        val settings = AppSettings(allowedRatings = setOf(Rating.GENERAL), blacklist = "-cat")
        assertFalse(settings.allows(posts[1]))
        assertTrue(settings.blacklistRules == listOf("-cat"))
    }
}
