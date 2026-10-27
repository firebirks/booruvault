package app.booruvault.booru

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Page shapes copied from the live sites' markup, with neutral content. */
class HtmlParsingTest {
    private val json = Json { ignoreUnknownKeys = true }

    private val gelbooruList = """
        <div class="thumbnail-container">
        <article class="thumbnail-preview">
        <a id="p15165936" href="https://gelbooru.com/index.php?page=post&amp;s=view&amp;id=15165936&tags=landscape">
        <img src="https://img4.gelbooru.com/thumbnails/a7/87/thumbnail_a787.jpg" title=" cloud landscape mountain  score:3 rating:general" alt="x" class=""/>
        </a></article>
        <article class="thumbnail-preview">
        <a id="p15165712" href="https://gelbooru.com/index.php?page=post&amp;s=view&amp;id=15165712&tags=landscape">
        <img src="https://img4.gelbooru.com/thumbnails/bb/cc/thumbnail_bbcc.jpg" title=" sea video webm  score:0 rating:sensitive" class="webm"/>
        </a></article></div>
        <div id="paginator"> <b>1</b> <a href="?page=post&amp;s=list&amp;tags=landscape&amp;pid=42">2</a><a href="?page=post&amp;s=list&amp;tags=landscape&amp;pid=84">3</a></div>
    """

    private val gelbooruPost = """
        <html><head><meta property="og:image" content="https://img4.gelbooru.com//images/a7/87/a787.jpg" /></head><body>
        <ul id="tag-list">
        <li class="tag-type-artist"><span class="sm-hidden"><a href="index.php?page=wiki&amp;s=list&amp;search=painter">?</a> </span><a href="index.php?page=post&amp;s=list&amp;tags=some_painter">some painter</a> <span>12</span></li>
        <li class="tag-type-general"><a href="index.php?page=post&amp;s=list&amp;tags=cloud">cloud</a></li>
        </ul>
        <li>Size: 3696x2079</li><li>Rating: General</li><li>Score: <span id="psc1">5</span></li>
        <a href="https://img4.gelbooru.com/images/a7/87/a787.jpg" target="_blank" style="font-weight: bold;">Original image</a>
        <img width="850" height="478" id="image" src="https://img4.gelbooru.com//samples/a7/87/sample_a787.jpg">
        </body></html>
    """

    private val booruOrgList = """
        <div><span class="thumb"><a id="p23692" href="index.php?page=post&amp;s=view&amp;id=23692"><img src="https://thumbs.booru.org/site/thumbnails//24/thumbnail_39e5.png" alt="post" border="0" title=" bunny edit princess score:0 rating:Questionable"/></a></span>
        <span class="thumb"><a id="p23691" href="index.php?page=post&amp;s=view&amp;id=23691"><img src="https://thumbs.booru.org/site/thumbnails//24/thumbnail_aaaa.jpg" title=" tree score:2 rating:Safe"/></a></span></div>
        <div id="paginator"><b>1</b> <a href="?page=post&amp;s=list&amp;tags=all&amp;pid=20">2</a></div>
    """

    private val shimmieList = """
        <div class='shm-image-list '>
        <div class='shm-thumb thumb' data-mime='image/png' data-tags='blue_sky field' data-post-id='7492994'><a class='shm-thumb-link' href='/post/view/7492994'><img id='thumb_7492994' title='Blue_Sky Field 800x600 // 248KB // png 2026-10-27T10:15:10+00:00' src='https://t.example.net/b5/d1/b5d1' /></a><br /><a href='https://i.example.net/b5/d1/b5d1'>File Only</a></div>
        <div class='shm-thumb thumb' data-mime='video/mp4' data-tags='river' data-post-id='7492990'><a class='shm-thumb-link' href='/post/view/7492990'><img title='River 1280x720 // 2MB // mp4' src='/_thumbs/cc/thumb.jpg' /></a></div>
        </div><a href='/post/list/2' class='nextlink'>Next</a>
    """

    @Test
    fun gelbooruListAndPost() {
        val page = GelbooruHtml.parseList(gelbooruList, "https://gelbooru.com/index.php?page=post&s=list&tags=landscape", "g")
        assertEquals(42, page.perPage)
        assertEquals(2, page.posts.size)
        val first = page.posts[0]
        assertEquals(15165936L, first.id)
        assertTrue(first.needsResolve)
        assertNull(first.fileUrl)
        assertEquals(Rating.GENERAL, first.rating)
        assertEquals(3, first.score)
        assertEquals(listOf("cloud", "landscape", "mountain"), first.tagNames)
        assertEquals("https://gelbooru.com/index.php?page=post&s=view&id=15165936", first.postUrl)
        assertEquals(MediaKind.VIDEO, page.posts[1].kind)
        assertEquals(Rating.SENSITIVE, page.posts[1].rating)

        val resolved = GelbooruHtml.parsePost(gelbooruPost, first.postUrl, first)
        assertFalse(resolved.needsResolve)
        assertEquals("https://img4.gelbooru.com/images/a7/87/a787.jpg", resolved.fileUrl)
        assertEquals("https://img4.gelbooru.com//samples/a7/87/sample_a787.jpg", resolved.sampleUrl)
        assertEquals(3696, resolved.width)
        assertEquals(5, resolved.score)
        assertEquals(Tag("some_painter", TagCategory.ARTIST), resolved.tags.first())
    }

    @Test
    fun booruOrgListAndPost() {
        val page = GelbooruHtml.parseList(booruOrgList, "https://site.booru.org/index.php?page=post&s=list&tags=all", "b")
        assertEquals(20, page.perPage)
        assertEquals(Rating.QUESTIONABLE, page.posts[0].rating)
        assertEquals(Rating.GENERAL, page.posts[1].rating)
        assertEquals("https://site.booru.org/index.php?page=post&s=view&id=23692", page.posts[0].postUrl)
        val post = """<img alt="img" src="https://img.booru.org/site//images/24/39e5.png" id="image"/> Size: 677x480 Rating: Questionable"""
        val resolved = GelbooruHtml.parsePost(post, page.posts[0].postUrl, page.posts[0])
        assertEquals("https://img.booru.org/site//images/24/39e5.png", resolved.fileUrl)
        assertNull(resolved.sampleUrl)
        assertEquals("png", resolved.fileExt)
        // Without a categorised tag list, the list's tags stay.
        assertEquals(page.posts[0].tags, resolved.tags)
    }

    @Test
    fun gelbooruVideoPost() {
        val post = Post("g", 1, null, null, null, 0, 0, "jpg", postUrl = "https://gelbooru.com/index.php?page=post&s=view&id=1", needsResolve = true)
        val html = """<video id="gelcomVideoPlayer"><source src="https://video-cdn.gelbooru.com/images/aa/bb/aabb.mp4" type="video/mp4"></video>
            <meta property="og:image" content="https://img4.gelbooru.com/thumbnails/aa/bb/thumbnail_aabb.jpg" />"""
        val resolved = GelbooruHtml.parsePost(html, post.postUrl, post)
        assertEquals("https://video-cdn.gelbooru.com/images/aa/bb/aabb.mp4", resolved.fileUrl)
        assertEquals(MediaKind.VIDEO, resolved.kind)
    }

    @Test
    fun shimmieListAndPost() {
        val posts = ShimmieHtml.parseList(shimmieList, "https://shimmie.example.net/post/list/1", "s")
        assertEquals(2, posts.size)
        val direct = posts[0]
        assertEquals("https://i.example.net/b5/d1/b5d1", direct.fileUrl)
        assertFalse(direct.needsResolve)
        assertEquals("png", direct.fileExt)
        assertEquals(800, direct.width)
        assertEquals(listOf("blue_sky", "field"), direct.tagNames)
        assertEquals(Rating.UNKNOWN, direct.rating)

        val video = posts[1]
        assertTrue(video.needsResolve)
        assertEquals("https://shimmie.example.net/_thumbs/cc/thumb.jpg", video.previewUrl)
        assertEquals("https://shimmie.example.net/post/view/7492990", video.postUrl)
        val page = """<video id='main_image' data-width='1280' data-height='720' data-mime='video/mp4'><source src='/_images/cc/7492990.mp4' type='video/mp4'></video>"""
        val resolved = ShimmieHtml.parsePost(page, video.postUrl, video)
        assertEquals("https://shimmie.example.net/_images/cc/7492990.mp4", resolved.fileUrl)
        assertEquals(MediaKind.VIDEO, resolved.kind)
        assertEquals(1280, resolved.width)
    }

    @Test
    fun genericPageExtraction() {
        val html = """
            <html><head><meta property="og:image" content="/cover.jpg"></head><body>
            <img src="/logo.png" width="32" height="32">
            <a href="/full/one.jpg"><img src="/thumbs/one.jpg"></a>
            <img src="/inline.jpg" srcset="/inline-480.jpg 480w, /inline-1600.jpg 1600w">
            <img data-src="/lazy.webp" src="data:image/gif;base64,R0lGOD">
            <img src="/vector.svg">
            <video poster="/poster.jpg"><source src="/clip.mp4"></video>
            <a href="/full/one.jpg">duplicate link</a>
            </body></html>
        """
        val posts = GenericHtml.extract(html, "https://example.org/gallery/page.html", "w")
        val files = posts.map { it.fileUrl }
        assertEquals(
            listOf(
                "https://example.org/full/one.jpg",
                "https://example.org/clip.mp4",
                "https://example.org/inline-1600.jpg",
                "https://example.org/lazy.webp",
                "https://example.org/cover.jpg",
            ),
            files,
        )
        assertEquals("https://example.org/thumbs/one.jpg", posts[0].previewUrl)
        assertEquals(MediaKind.VIDEO, posts[1].kind)
        assertEquals("https://example.org/poster.jpg", posts[1].previewUrl)
        assertEquals(GenericHtml.stableId("https://example.org/full/one.jpg"), posts[0].id)
        assertTrue(posts.all { it.id > 0 && !it.needsResolve })
    }

    @Test
    fun gelbooruFallsBackToWebPagesWhenTheApiRefuses() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val url = chain.request().url.toString()
            val (code, body) = when {
                "page=dapi" in url -> 200 to "\"Missing authentication. Go to api.rule34.xxx for more information\""
                "s=list" in url -> 200 to gelbooruList
                "s=view" in url -> 200 to gelbooruPost
                else -> 404 to ""
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("x")
                .body(body.toResponseBody("text/html".toMediaType())).build()
        }.build()
        val api = BooruApiFactory.create(BooruSource("fb", "FB", BooruEngine.GELBOORU, "https://api.fallback.test"), client, json)
        val posts = api.posts("landscape", 1, 40)
        assertEquals(2, posts.size)
        assertTrue(posts.all { it.needsResolve })
        val resolved = api.resolve(posts[0])
        assertEquals("https://img4.gelbooru.com/images/a7/87/a787.jpg", resolved.fileUrl)
        // Page 2 uses the page size the paginator gave.
        assertEquals(2, api.posts("landscape", 2, 40).size)
    }
}
