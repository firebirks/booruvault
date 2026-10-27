package io.github.firebirks.booruvault.vault

import io.github.firebirks.booruvault.booru.Rating
import org.junit.Assert.assertEquals
import org.junit.Test

class TagQueryTest {
    private fun item(id: String, tags: List<String>, mime: String = "image/jpeg", rating: Rating = Rating.GENERAL, source: String? = "Danbooru") =
        VaultItem(id = id, addedAt = 0, fileName = id, mimeType = mime, size = 1, tags = tags, rating = rating, sourceName = source)

    private val items = listOf(
        item("a", listOf("landscape", "mountain", "sky")),
        item("b", listOf("landscape", "rain")),
        item("c", listOf("cat", "catgirl"), rating = Rating.SENSITIVE),
        item("d", listOf("catgirl"), mime = "video/mp4", source = "e621"),
        item("e", listOf("dog"), mime = "image/gif"),
    )

    private fun ids(query: String) = items.filter(TagQuery.matcher(query)).map { it.id }

    @Test
    fun tagsAreExactNotSubstrings() {
        assertEquals(listOf("c"), ids("cat"))
        assertEquals(listOf("c", "d"), ids("catgirl"))
    }

    @Test
    fun exclusionAlternativesAndWildcards() {
        assertEquals(listOf("a"), ids("landscape -rain"))
        assertEquals(listOf("c", "e"), ids("~cat ~dog"))
        assertEquals(listOf("c", "d"), ids("cat*"))
        assertEquals(listOf("a", "b"), ids("land*cape"))
    }

    @Test
    fun metaTerms() {
        assertEquals(listOf("d"), ids("type:video"))
        assertEquals(listOf("e"), ids("type:gif"))
        assertEquals(listOf("d"), ids("source:e621"))
        assertEquals(listOf("c"), ids("rating:sensitive"))
        assertEquals(listOf("a", "b", "c", "e"), ids("-type:video"))
    }

    @Test
    fun everythingMatchesAnEmptyQuery() {
        assertEquals(items.map { it.id }, ids("  "))
    }
}
