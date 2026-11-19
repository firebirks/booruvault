package io.github.firebirks.booruvault.download

import io.github.firebirks.booruvault.booru.BooruEngine
import io.github.firebirks.booruvault.booru.BooruSource
import io.github.firebirks.booruvault.booru.Post
import io.github.firebirks.booruvault.booru.Rating
import io.github.firebirks.booruvault.settings.SettingsRepository
import io.github.firebirks.booruvault.util.Problem
import io.github.firebirks.booruvault.vault.VaultManager
import io.github.firebirks.booruvault.vault.VaultStore
import io.github.firebirks.booruvault.vault.crypto.Kdf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A transfer that ends without a word short of the length its host announced, as an HTTP/2 stream
 * may, is cut and not done: taken for the whole file, it would be saved cut short and, being in
 * the vault, never asked for again. It is taken up where it stopped when the host allows.
 */
class DownloadLengthTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val source = BooruSource("safebooru-donmai", "Safebooru", BooruEngine.DANBOORU, "https://safebooru.donmai.us")
    private val file = ByteArray(200_000) { i -> (i * 7 + i / 13).toByte() }
    private lateinit var store: VaultStore

    @After
    fun tearDown() {
        managerScope.cancel()
        scope.cancel()
    }

    /** Announces [length] and gives [bytes], then ends as if that were all. */
    private class ShortBody(private val bytes: ByteArray, private val length: Long) : ResponseBody() {
        override fun contentType(): MediaType = "image/jpeg".toMediaType()
        override fun contentLength(): Long = length
        override fun source(): BufferedSource = Buffer().write(bytes)
    }

    /** Serves [file], the first answer ending at [firstGives]; [validator] names it, if given. */
    private inner class Host(private val firstGives: Int, private val validator: String?) : Interceptor {
        val ranges = CopyOnWriteArrayList<String?>()

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val range = request.header("Range")
            ranges += range
            val builder = Response.Builder().request(request).protocol(Protocol.HTTP_2).message("")
            validator?.let { builder.header("ETag", it) }
            if (range == null) {
                return builder.code(200).header("Content-Length", "${file.size}")
                    .body(ShortBody(file.copyOf(firstGives), file.size.toLong())).build()
            }
            val from = range.removePrefix("bytes=").removeSuffix("-").toInt()
            val rest = file.copyOfRange(from, file.size)
            return builder.code(206).header("Content-Length", "${rest.size}").header("Content-Range", "bytes $from-${file.size - 1}/${file.size}")
                .body(ShortBody(rest, rest.size.toLong())).build()
        }
    }

    private suspend fun manager(host: Host): DownloadManager {
        val root = tmp.newFolder()
        val vault = VaultManager(root, json)
        val settings = SettingsRepository(root, vault, json, scope)
        vault.create("pw-123456".toCharArray(), Kdf.newParams(memoryKiB = 1024, iterations = 1))
        withTimeout(5000) { settings.ready.first { it } }
        store = VaultStore(root, vault, json, scope)
        val client = OkHttpClient.Builder().addInterceptor(host).build()
        return DownloadManager({}, { error("no API needed") }, client, { false }, { _, _ -> false }, store, vault, settings, json, root, managerScope, timeoutRetryPauseMs = 0)
    }

    private val post = Post(source.id, 1, null, null, "https://cdn.donmai.us/original/1.jpg", 1, 1, "jpg", rating = Rating.GENERAL, postUrl = "https://safebooru.donmai.us/posts/1")

    private suspend fun DownloadManager.settled() =
        withTimeout(30_000) { tasks.first { list -> list.isNotEmpty() && list.none { it.active } }.single() }

    @Test
    fun `an answer that ends short of its length is taken up where it stopped`() = runBlocking {
        val host = Host(firstGives = 70_000, validator = "\"v1\"")
        val downloads = manager(host)
        downloads.enqueue(listOf(post), source)

        val task = downloads.settled()
        assertEquals(DownloadStatus.DONE, task.status)
        assertEquals(listOf(null, "bytes=70000-"), host.ranges.toList())
        val item = withTimeout(5000) { store.items.first { it.isNotEmpty() } }.single()
        assertArrayEquals(file, store.openMedia(item.id).use { it.readAll() })
    }

    @Test
    fun `one its host cannot take up fails, and nothing is saved`() = runBlocking {
        val downloads = manager(Host(firstGives = 70_000, validator = null))
        downloads.enqueue(listOf(post), source)

        val task = downloads.settled()
        assertEquals(DownloadStatus.FAILED, task.status)
        assertEquals(Problem.Code.NETWORK, task.problem?.code)
        assertTrue(store.items.value.isEmpty())
    }

    @Test
    fun `only an end short of a length announced is a cut`() {
        assertNull(endedShort(10, 10))
        assertNull(endedShort(10, -1))
        assertTrue(endedShort(9, 10) is java.io.EOFException)
    }

    @Test
    fun `the queue comes back in the order it was queued, across sources`() {
        val runs = runsOf(listOf("a" to 1, "a" to 2, "b" to 3, "a" to 4, "a" to 5)) { it.first }
        assertEquals(listOf(listOf(1, 2), listOf(3), listOf(4, 5)), runs.map { run -> run.map { it.second } })
    }
}
