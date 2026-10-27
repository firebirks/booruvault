package io.github.firebirks.booruvault.booru

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Response shapes recorded from the live sites, trimmed to the fields that matter. */
class ParsingTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val requests = mutableListOf<Request>()

    private fun client(body: String, code: Int = 200) = OkHttpClient.Builder().addInterceptor { chain ->
        requests += chain.request()
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("x")
            .body(body.toResponseBody("application/json".toMediaType())).build()
    }.build()

    private fun api(engine: BooruEngine, base: String, body: String, code: Int = 200, login: String = "", key: String = "") =
        BooruApiFactory.create(BooruSource("s", "S", engine, base, login, key), client(body, code), json)

    @Test
    fun danbooru() = runBlocking {
        val body = """[{"id":12457294,"created_at":"2026-10-27T00:53:06.695-04:00","score":2,"source":"https://x.com/a",
            "md5":"a78785815d64fb878cd7d19dd184c88b","rating":"g","image_width":3696,"image_height":2079,
            "tag_string":"cloud landscape original the_raspy_cupcake","file_ext":"jpg","file_size":1269828,
            "tag_string_general":"cloud landscape","tag_string_character":"","tag_string_copyright":"original",
            "tag_string_artist":"the_raspy_cupcake","tag_string_meta":"",
            "media_asset":{"variants":[{"type":"180x180","url":"https://cdn.donmai.us/180x180/a.jpg"},{"type":"360x360","url":"https://cdn.donmai.us/360x360/a.jpg"}]},
            "file_url":"https://cdn.donmai.us/original/a.jpg","large_file_url":"https://cdn.donmai.us/sample/sample-a.jpg",
            "preview_file_url":"https://cdn.donmai.us/180x180/a.jpg"},
            {"id":2,"rating":"e","file_ext":"zip","image_width":10,"image_height":10,"tag_string":"ugoira",
            "file_url":"https://cdn.donmai.us/original/b.zip","large_file_url":"https://cdn.donmai.us/sample/b.webm"},
            {"id":3,"rating":"s","tag_string":"banned"}]"""
        val posts = api(BooruEngine.DANBOORU, "https://safebooru.donmai.us/", body, login = "me", key = "k").posts("landscape", 2, 40)
        assertEquals(2, posts.size)
        val p = posts[0]
        assertEquals(12457294L, p.id)
        assertEquals("https://cdn.donmai.us/360x360/a.jpg", p.previewUrl)
        assertEquals("https://cdn.donmai.us/original/a.jpg", p.fileUrl)
        assertEquals(Rating.GENERAL, p.rating)
        assertEquals(TagCategory.ARTIST, p.tags.first().category)
        assertEquals("https://safebooru.donmai.us/posts/12457294", p.postUrl)
        assertTrue(p.createdAt!! > 0)
        // Ugoira falls back to its webm sample.
        assertEquals("webm", posts[1].fileExt)
        assertEquals(MediaKind.VIDEO, posts[1].kind)
        val url = requests.single().url
        assertEquals("landscape", url.queryParameter("tags"))
        assertEquals("2", url.queryParameter("page"))
        // Credentials ride in a header, never in the URL.
        assertNull(url.queryParameter("login"))
        assertNull(url.queryParameter("api_key"))
        assertEquals(okhttp3.Credentials.basic("me", "k"), requests.single().header("Authorization"))
    }

    @Test
    fun danbooruErrorMessageIsShown() = runBlocking {
        val body = """{"success":false,"error":"PostQuery::TagLimitError","message":"You cannot search for more than 2 tags at a time."}"""
        try {
            api(BooruEngine.DANBOORU, "https://danbooru.donmai.us", body, code = 422).posts("a b c", 1, 10)
            fail()
        } catch (e: BooruException) {
            assertTrue(e.message!!.contains("2 tags"))
        }
    }

    @Test
    fun moebooru() = runBlocking {
        val body = """[{"id":1271155,"tags":"landscape umbrella","created_at":1792759887,"source":"https://va.com",
            "score":20,"md5":"ffba","file_size":626450,"file_ext":"webp","file_url":"https://files.yande.re/image/ffba/a.webp",
            "preview_url":"https://assets.yande.re/data/preview/ff/ba/ffba.jpg","sample_url":"https://files.yande.re/sample/ffba/s.jpg",
            "jpeg_url":"https://files.yande.re/jpeg/ffba/j.jpg","rating":"s","width":1920,"height":1080}]"""
        val p = api(BooruEngine.MOEBOORU, "https://yande.re", body).posts("", 1, 1).single()
        assertEquals("webp", p.fileExt)
        assertEquals(Rating.GENERAL, p.rating)
        assertEquals(1792759887000L, p.createdAt)
        assertEquals(listOf("landscape", "umbrella"), p.tagNames)
        assertEquals("https://yande.re/post/show/1271155", p.postUrl)
    }

    @Test
    fun gelbooruForkBareArray() = runBlocking {
        val body = """[{"preview_url":"https:\/\/safebooru.org\/thumbnails\/3926\/thumbnail_0137.jpg","sample_url":"https:\/\/safebooru.org\/samples\/3926\/sample_0137.jpg",
            "file_url":"https:\/\/safebooru.org\/images\/3926\/0137.jpg","directory":3926,"hash":"a787","width":3696,"height":2079,
            "id":7267945,"image":"0137.jpg","change":1793077214,"rating":"general","sample":true,"score":null,"tags":"clouds landscape"}]"""
        val p = api(BooruEngine.GELBOORU, "https://safebooru.org", body).posts("landscape", 3, 1).single()
        assertEquals("jpg", p.fileExt)
        assertEquals("a787", p.md5)
        assertNull(p.score)
        assertEquals(Rating.GENERAL, p.rating)
        assertEquals("https://safebooru.org/index.php?page=post&s=view&id=7267945", p.postUrl)
        assertEquals("2", requests.single().url.queryParameter("pid"))
    }

    @Test
    fun gelbooruWrappedAndBuiltUrls() = runBlocking {
        val body = """{"@attributes":{"limit":1,"offset":0,"count":1},"post":[{"id":5,"directory":"ab/cd","image":"x.png",
            "sample":1,"sample_url":"","rating":"explicit","width":1,"height":1,"tags":"a",
            "created_at":"Sun Oct 26 12:00:00 -0500 2025"}]}"""
        val p = api(BooruEngine.GELBOORU, "https://gelbooru.com", body).posts("", 1, 1).single()
        assertEquals("https://gelbooru.com/images/ab/cd/x.png", p.fileUrl)
        assertEquals("https://gelbooru.com/samples/ab/cd/sample_x.jpg", p.sampleUrl)
        assertEquals("https://gelbooru.com/thumbnails/ab/cd/thumbnail_x.jpg", p.previewUrl)
        assertEquals(Rating.EXPLICIT, p.rating)
        assertTrue(p.createdAt!! > 0)
    }

    @Test
    fun gelbooruEmptyBodyAndAuthError() = runBlocking {
        assertTrue(api(BooruEngine.GELBOORU, "https://safebooru.org", "").posts("zzz", 1, 1).isEmpty())
        try {
            api(BooruEngine.GELBOORU, "https://api.rule34.xxx", "\"Missing authentication. Go to api.rule34.xxx for more information\"").posts("", 1, 1)
            fail()
        } catch (e: BooruException) {
            assertTrue(e.message!!.startsWith("Missing authentication"))
        }
    }

    @Test
    fun gelbooruAutocompleteLabels() = runBlocking {
        val body = """[{"label":"landscape (9888)","value":"landscape"},{"label":"landscapes (1)","value":"landscapes"}]"""
        val list = api(BooruEngine.GELBOORU, "https://safebooru.org", body).autocomplete("landsc")
        assertEquals("landscape", list[0].name)
        assertEquals(9888L, list[0].count)
    }

    @Test
    fun e621() = runBlocking {
        val body = """{"posts":[{"id":6811651,"created_at":"2026-10-27T04:53:49.102-04:00",
            "file":{"width":3300,"height":2965,"ext":"png","size":5863113,"md5":"512f","url":"https://static1.e621.net/data/51/2f/512f.png"},
            "preview":{"width":285,"height":256,"url":"https://static1.e621.net/data/preview/51/2f/512f.jpg"},
            "sample":{"has":true,"width":946,"height":850,"url":"https://static1.e621.net/data/sample/51/2f/512f.jpg"},
            "score":{"up":1,"down":0,"total":1},"tags":{"general":["anthro"],"species":["mammal"],"artist":["someone"]},
            "rating":"s","sources":[]},
            {"id":7,"file":{"url":null,"ext":"jpg"},"rating":"e","tags":{}}]}"""
        val posts = api(BooruEngine.E621, "https://e621.net", body, login = "me", key = "k").posts("", 1, 2)
        assertEquals(1, posts.size)
        val p = posts.single()
        assertEquals("png", p.fileExt)
        assertEquals(1, p.score)
        assertEquals(TagCategory.ARTIST, p.tags.first().category)
        assertTrue(p.tags.any { it.category == TagCategory.SPECIES })
        val request = requests.single()
        assertTrue(request.header("User-Agent")!!.contains("by me on e621"))
        assertTrue(request.header("Authorization")!!.startsWith("Basic "))
    }

    @Test
    fun cloudflareChallengeIsRecognised() = runBlocking {
        try {
            api(BooruEngine.E621, "https://e926.net", "<!DOCTYPE html><html><head><title>Just a moment...</title>", code = 403).posts("", 1, 1)
            fail()
        } catch (e: BooruChallengeException) {
            assertTrue(e.url.startsWith("https://e926.net/posts.json"))
        }
        val flagged = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(403).message("x")
                .header("cf-mitigated", "challenge")
                .body("<html></html>".toResponseBody("text/html".toMediaType())).build()
        }.build()
        try {
            BooruApiFactory.create(BooruSource("s", "S", BooruEngine.DANBOORU, "https://danbooru.donmai.us"), flagged, json).posts("", 1, 1)
            fail()
        } catch (e: BooruChallengeException) {
            // expected
        }
    }

    @Test
    fun plainForbiddenIsNotAChallenge() = runBlocking {
        try {
            api(BooruEngine.GELBOORU, "https://gelbooru.com", "", code = 401).posts("", 1, 1)
            fail()
        } catch (e: BooruChallengeException) {
            fail("401 without a challenge page is a credentials problem")
        } catch (e: BooruException) {
            assertEquals(io.github.firebirks.booruvault.util.Problem.Code.ACCESS_DENIED, e.problem.code)
        }
    }

    @Test
    fun autocompleteToken() {
        assertEquals("-" to "lands", autocompleteToken("sky -lands"))
        assertEquals("" to "abc", autocompleteToken("abc"))
        assertEquals("~" to "x", autocompleteToken("a b ~x"))
    }

    @Test
    fun moebooruPasswordHashMatchesTheSite() {
        // SHA-1("choujin-steiner--password--"), the hash every Moebooru site expects as password_hash.
        assertEquals("b3c976b76ba4505518baa3dd35fd5d335069963c", MoebooruApi.passwordHash("password"))
    }
}
