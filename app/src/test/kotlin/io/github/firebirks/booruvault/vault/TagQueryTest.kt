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

class LooseTagQueryTest {
    private fun item(id: String, tags: List<String>, name: String = id, source: String? = "Danbooru") =
        VaultItem(id = id, addedAt = 0, fileName = name, mimeType = "image/jpeg", size = 1, tags = tags, sourceName = source)

    private val items = listOf(
        item("a", listOf("landscape", "sky")),
        item("b", listOf("cat"), name = "holiday_land.jpg"),
        item("c", listOf("dog"), source = "Lands End"),
        item("d", listOf("cat_ears")),
    )

    private fun loose(query: String) = items.filter(TagQuery.matcher(query, loose = true)).map { it.id }
    private fun exact(query: String) = items.filter(TagQuery.matcher(query)).map { it.id }

    @Test
    fun aPlainWordIsFoundInsideTagsFileNamesAndSources() {
        assertEquals(listOf("a", "b", "c"), loose("land"))
        assertEquals(listOf("b", "d"), loose("cat"))
        assertEquals(listOf("b"), loose("cat -ears"))
    }

    @Test
    fun narrowingTermsAndWildcardsMeanTheSameLooseOrExact() {
        assertEquals(exact("type:image"), loose("type:image"))
        assertEquals(exact("cat*"), loose("cat*"))
        assertEquals(listOf("b", "d"), loose("~cat ~nothing"))
    }

    @Test
    fun aWidenedFilterIsAnExactQueryForWhatItShowed() {
        assertEquals("*land* -*rain* ~*cat* type:video cat*", TagQuery.widened("land  -rain ~cat type:video cat*"))
        assertEquals(loose("land"), exact(TagQuery.widened("land")))
        assertEquals(loose("cat -ears"), exact(TagQuery.widened("cat -ears")))
        assertEquals("", TagQuery.widened("  "))
    }

    @Test
    fun aWildcardLooksIntoFileNamesAndSourcesAsAPlainWordDoesLoosely() {
        assertEquals(listOf("b"), exact("*holiday*"))
        assertEquals(listOf("c"), exact("lands*end"))
        assertEquals(listOf("d"), exact("-*land*"))
    }
}
