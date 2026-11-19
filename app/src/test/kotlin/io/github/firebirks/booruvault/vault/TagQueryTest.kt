package io.github.firebirks.booruvault.vault

import io.github.firebirks.booruvault.booru.BooruEngine
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
        // Initials as the app labels the ratings: s is sensitive, and safe, in full, is general.
        assertEquals(listOf("c"), ids("rating:s"))
        assertEquals(listOf("a", "b", "d", "e"), ids("rating:g"))
        assertEquals(listOf("a", "b", "d", "e"), ids("rating:safe"))
        assertEquals(listOf("a", "b", "c", "d", "e"), ids("rating:g,s"))
        assertEquals(emptyList<String>(), ids("rating:nonsense"))
        assertEquals(listOf("a", "b", "c", "e"), ids("-type:video"))
    }

    @Test
    fun everythingMatchesAnEmptyQuery() {
        assertEquals(items.map { it.id }, ids("  "))
    }

    @Test
    fun aSearchFromABoardDropsWhatTheVaultDoesNotKeep() {
        assertEquals("landscape", TagQuery.fromSearch("landscape order:score", BooruEngine.DANBOORU))
        assertEquals("cat -dog", TagQuery.fromSearch("cat -dog score:>100 -user:someone sort:score:desc limit:40", BooruEngine.GELBOORU))
        assertEquals("cat", TagQuery.fromSearch("cat source:*pixiv*", BooruEngine.DANBOORU))
        // Saved from the search, the collection holds the items the search found.
        assertEquals(listOf("a", "b"), ids(TagQuery.fromSearch("landscape order:rank id:>100", BooruEngine.DANBOORU)))
    }

    @Test
    fun aBoardsRatingsAreNamedInFullAsTheBoardMeansThem() {
        assertEquals("cat rating:sensitive", TagQuery.fromSearch("cat rating:s", BooruEngine.DANBOORU))
        assertEquals("cat rating:general", TagQuery.fromSearch("cat rating:s", BooruEngine.MOEBOORU))
        assertEquals("cat rating:general", TagQuery.fromSearch("cat rating:safe", BooruEngine.E621))
        assertEquals("-rating:questionable,explicit", TagQuery.fromSearch("-rating:q,e", BooruEngine.DANBOORU))
        assertEquals(listOf("c"), ids(TagQuery.fromSearch("rating:s", BooruEngine.DANBOORU)))
        assertEquals("cat", TagQuery.fromSearch("cat rating:nonsense", BooruEngine.DANBOORU))
    }

    @Test
    fun aBoardsFileTypesBecomeKinds() {
        assertEquals("type:video", TagQuery.fromSearch("filetype:mp4,webm", BooruEngine.DANBOORU))
        assertEquals("-type:gif", TagQuery.fromSearch("-type:gif", BooruEngine.E621))
        // Kinds the vault tells apart from each other cannot be held by one term: dropped.
        assertEquals("cat", TagQuery.fromSearch("cat filetype:png,gif", BooruEngine.DANBOORU))
        assertEquals(listOf("d"), ids(TagQuery.fromSearch("filetype:webm", BooruEngine.DANBOORU)))
    }

    @Test
    fun tagsWithAColonAndAlternativesAreKeptAsTheyAre() {
        assertEquals("re:zero ~cat ~dog :o", TagQuery.fromSearch("  re:zero  ~cat ~dog :o ", BooruEngine.DANBOORU))
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

    @Test
    fun aWordWithAColonIsWidenedLikeAnyOther() {
        val shows = listOf(
            item("rz", listOf("re:zero_kara_hajimeru_isekai_seikatsu", "emilia")),
            item("other", listOf("zero_two")),
        )
        fun exactIn(query: String) = shows.filter(TagQuery.matcher(query)).map { it.id }
        fun looseIn(query: String) = shows.filter(TagQuery.matcher(query, loose = true)).map { it.id }
        assertEquals(listOf("rz"), looseIn("re:zero"))
        assertEquals("*re:zero*", TagQuery.widened("re:zero"))
        assertEquals(looseIn("re:zero"), exactIn(TagQuery.widened("re:zero")))
        // The terms that narrow mean the same loose or exact, and are kept.
        assertEquals("type:video -Rating:e source:dan", TagQuery.widened("type:video -Rating:e source:dan"))
    }

    @Test
    fun aFilterSavedWithinACollectionHoldsWhatItShowedThere() {
        val within = "landscape -rain"
        val shown = items.filter(TagQuery.matcher(within)).filter(TagQuery.matcher("sky", loose = true)).map { it.id }
        val saved = TagQuery.savedFilter("sky", within)
        assertEquals("landscape -rain *sky*", saved)
        assertEquals(shown, exact(saved))
        // Filtering the same outside any collection gives the whole vault's.
        assertEquals("*sky*", TagQuery.savedFilter("sky", null))
        assertEquals(loose("sky"), exact(TagQuery.savedFilter("sky", null)))
    }

    @Test
    fun twoSetsOfAlternativesCannotBeKeptApartAndTheFilterIsSavedAlone() {
        assertEquals("~*cat* ~*dog*", TagQuery.savedFilter("~cat ~dog", "~landscape ~sky"))
        // One set alone is no trouble.
        assertEquals("~landscape ~sky *cat*", TagQuery.savedFilter("cat", "~landscape ~sky"))
        assertEquals("", TagQuery.savedFilter("  ", "landscape"))
    }
}

