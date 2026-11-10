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
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * A transfer cut short keeps what it has: the rest is asked of the host and sealed after it, so
 * a large file over a slow Tor circuit is not begun again at every stall. The file must come
 * out byte for byte the one the host serves, and a host that hands out no parts, or another
 * file, must be read from the first byte instead.
 */
class DownloadResumeTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val fastKdf get() = Kdf.newParams(memoryKiB = 1024, iterations = 1)
    private val source = BooruSource("safebooru-donmai", "Safebooru", BooruEngine.DANBOORU, "https://safebooru.donmai.us")
    private lateinit var store: VaultStore

    @After
    fun tearDown() {
        managerScope.cancel()
        scope.cancel()
    }

    private enum class Cut { STALL, CLOSE }

    /**
     * Serves [FILE], whole or from where a Range asks when [parts] is on. [sends] says how many
     * bytes the n-th connection gives of what it was asked before it is cut, the way [cut] says;
     * null gives it all.
     */
    private class PartServer(
        private val parts: Boolean = true,
        private val cut: Cut = Cut.STALL,
        private val validator: String? = "ETag: \"v1\"",
        private val sends: (connection: Int) -> Int?,
    ) : AutoCloseable {
        private val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val connections = AtomicInteger()

        /** The Range and If-Range of each request, in order; null for a request without. */
        val ranges = CopyOnWriteArrayList<Pair<String?, String?>>()
        val port: Int get() = server.localPort

        init {
            thread(isDaemon = true) {
                while (!server.isClosed) {
                    val socket = runCatching { server.accept() }.getOrNull() ?: break
                    val n = connections.incrementAndGet()
                    thread(isDaemon = true) {
                        runCatching {
                            socket.use {
                                val reader = it.getInputStream().bufferedReader()
                                val headers = HashMap<String, String>()
                                while (true) {
                                    val line = reader.readLine()?.takeIf { l -> l.isNotEmpty() } ?: break
                                    val colon = line.indexOf(':')
                                    if (colon > 0) headers[line.substring(0, colon).lowercase()] = line.substring(colon + 1).trim()
                                }
                                val range = headers["range"]
                                ranges += range to headers["if-range"]
                                val from = if (parts && range != null) range.removePrefix("bytes=").removeSuffix("-").toInt() else 0
                                val body = FILE.copyOfRange(from, FILE.size)
                                val output = it.getOutputStream()
                                val status = if (from > 0) "206 Partial Content" else "200 OK"
                                val head = StringBuilder("HTTP/1.1 $status\r\nContent-Type: image/jpeg\r\nContent-Length: ${body.size}\r\nConnection: close\r\n")
                                if (from > 0) head.append("Content-Range: bytes $from-${FILE.size - 1}/${FILE.size}\r\n")
                                validator?.let { v -> head.append(v).append("\r\n") }
                                output.write(head.append("\r\n").toString().toByteArray())
                                val given = sends(n)
                                if (given == null) {
                                    output.write(body)
                                    output.flush()
                                } else {
                                    output.write(body, 0, minOf(given, body.size))
                                    output.flush()
                                    if (cut == Cut.STALL) it.getInputStream().readBytes() // silent until the client hangs up
                                }
                            }
                        }
                    }
                }
            }
        }

        override fun close() = server.close()
    }

    private fun post(port: Int) = Post(
        source.id, 1, null, null, "http://127.0.0.1:$port/1.jpg", 1, 1, "jpg",
        rating = Rating.GENERAL, postUrl = "https://safebooru.donmai.us/posts/1",
    )

    private suspend fun manager(): DownloadManager {
        val root = tmp.newFolder()
        val vault = VaultManager(root, json)
        val settings = SettingsRepository(root, vault, json, scope)
        vault.create("pw-123456".toCharArray(), fastKdf)
        withTimeout(5000) { settings.ready.first { it } }
        store = VaultStore(root, vault, json, scope)
        val client = OkHttpClient.Builder().readTimeout(300, TimeUnit.MILLISECONDS).build()
        return DownloadManager({}, { error("no API needed") }, client, { false }, { _, _ -> false }, store, vault, settings, json, root, managerScope, timeoutRetryPauseMs = 0)
    }

    private suspend fun DownloadManager.settled() =
        withTimeout(30_000) { tasks.first { list -> list.isNotEmpty() && list.none { it.active } }.single() }

    /** What the vault holds of the one file downloaded, unsealed. */
    private suspend fun stored(): ByteArray {
        val item = withTimeout(5000) { store.items.first { it.isNotEmpty() } }.single()
        return store.openMedia(item.id).use { it.readAll() }
    }

    @Test
    fun `a transfer that goes quiet is taken up where it stopped`() = runBlocking {
        PartServer(sends = { n -> if (n == 1) 70_000 else null }).use { server ->
            val downloads = manager()
            downloads.enqueue(listOf(post(server.port)), source)
            val task = downloads.settled()
            assertEquals(DownloadStatus.DONE, task.status)
            assertEquals(FILE.size.toLong(), task.bytes)
            assertEquals(listOf(null to null, "bytes=70000-" to "\"v1\""), server.ranges.toList())
            assertArrayEquals(FILE, stored())
        }
    }

    @Test
    fun `a transfer the host hung up on is taken up where it stopped`() = runBlocking {
        PartServer(cut = Cut.CLOSE, sends = { n -> if (n == 1) 70_000 else null }).use { server ->
            val downloads = manager()
            downloads.enqueue(listOf(post(server.port)), source)
            assertEquals(DownloadStatus.DONE, downloads.settled().status)
            assertEquals(2, server.connections.get())
            assertArrayEquals(FILE, stored())
        }
    }

    @Test
    fun `the date a file was last changed names it when the host gives no strong tag`() = runBlocking {
        val date = "Last-Modified: Wed, 21 Oct 2026 07:28:00 GMT"
        PartServer(validator = "ETag: W/\"weak\"\r\n$date", sends = { n -> if (n == 1) 70_000 else null }).use { server ->
            val downloads = manager()
            downloads.enqueue(listOf(post(server.port)), source)
            assertEquals(DownloadStatus.DONE, downloads.settled().status)
            assertEquals("bytes=70000-" to "Wed, 21 Oct 2026 07:28:00 GMT", server.ranges[1])
            assertArrayEquals(FILE, stored())
        }
    }

    @Test
    fun `a transfer cut again and again goes on for as long as each try brings something`() = runBlocking {
        // More cuts than a file earns tries, each after a little more of it.
        PartServer(sends = { n -> if (n <= 6) 30_000 else null }).use { server ->
            val downloads = manager()
            downloads.enqueue(listOf(post(server.port)), source)
            assertEquals(DownloadStatus.DONE, downloads.settled().status)
            assertEquals(7, server.connections.get())
            assertEquals("bytes=180000-", server.ranges.last().first)
            assertArrayEquals(FILE, stored())
        }
    }

    @Test
    fun `a transfer that brings nothing more fails with a timeout after its tries`() = runBlocking {
        PartServer(sends = { n -> if (n == 1) 70_000 else 0 }).use { server ->
            val downloads = manager()
            downloads.enqueue(listOf(post(server.port)), source)
            val task = downloads.settled()
            assertEquals(DownloadStatus.FAILED, task.status)
            assertEquals(Problem.Code.TIMEOUT, task.problem?.code)
            // The first, and the two tries it earned: each asked for the same rest.
            assertEquals(3, server.connections.get())
            assertEquals(listOf("bytes=70000-", "bytes=70000-"), server.ranges.drop(1).map { it.first })
            assertTrue(store.items.value.isEmpty())
        }
    }

    @Test
    fun `a host that hands out no parts is read again from the first byte`() = runBlocking {
        PartServer(parts = false, sends = { n -> if (n == 1) 70_000 else null }).use { server ->
            val downloads = manager()
            downloads.enqueue(listOf(post(server.port)), source)
            val task = downloads.settled()
            assertEquals(DownloadStatus.DONE, task.status)
            // Cut, asked for the rest and given the whole, then read whole: the last asks for no part.
            assertEquals(listOf(null, "bytes=70000-", null), server.ranges.map { it.first })
            assertArrayEquals(FILE, stored())
        }
    }

    @Test
    fun `a host that hands out no parts still gets every try at the whole file`() = runBlocking {
        PartServer(parts = false, sends = { 70_000 }).use { server ->
            val downloads = manager()
            downloads.enqueue(listOf(post(server.port)), source)
            val task = downloads.settled()
            assertEquals(DownloadStatus.FAILED, task.status)
            assertEquals(Problem.Code.TIMEOUT, task.problem?.code)
            // Three tries at the whole file, as before, and one question about its rest, asked once.
            assertEquals(listOf(null, "bytes=70000-", null, null), server.ranges.map { it.first })
        }
    }

    @Test
    fun `a file without a name to hold it to is not taken up`() = runBlocking {
        PartServer(validator = null, cut = Cut.CLOSE, sends = { n -> if (n == 1) 70_000 else null }).use { server ->
            val downloads = manager()
            downloads.enqueue(listOf(post(server.port)), source)
            assertEquals(DownloadStatus.FAILED, downloads.settled().status)
            assertEquals(1, server.connections.get())
        }
    }

    private fun response(code: Int, vararg headers: Pair<String, String>, length: Int = 10): Response {
        val builder = Response.Builder().request(Request.Builder().url("http://127.0.0.1/1.jpg").build())
            .protocol(Protocol.HTTP_1_1).code(code).message("").body(ByteArray(length).toResponseBody())
        headers.forEach { (name, value) -> builder.header(name, value) }
        return builder.build()
    }

    @Test
    fun `only the very part asked for is taken`() {
        assertTrue(resumesAt(response(206, "Content-Range" to "bytes 100-999/1000"), 100, 1000))
        assertTrue(resumesAt(response(206, "Content-Range" to "bytes 100-999/*"), 100, 1000))
        // Another start, another length, a whole file, or no word on what it is.
        assertFalse(resumesAt(response(206, "Content-Range" to "bytes 0-999/1000"), 100, 1000))
        assertFalse(resumesAt(response(206, "Content-Range" to "bytes 100-1999/2000"), 100, 1000))
        assertFalse(resumesAt(response(206, "Content-Range" to "bytes 100-499/1000"), 100, 1000))
        assertFalse(resumesAt(response(200, "Content-Range" to "bytes 100-999/1000"), 100, 1000))
        assertFalse(resumesAt(response(206), 100, 1000))
    }

    @Test
    fun `a file is held to a strong tag, a date, or nothing`() {
        assertEquals("\"a\"", resumeValidator(response(200, "ETag" to "\"a\"", "Last-Modified" to "then")))
        assertEquals("then", resumeValidator(response(200, "ETag" to "W/\"a\"", "Last-Modified" to "then")))
        assertNull(resumeValidator(response(200, "ETag" to "W/\"a\"")))
        assertNull(resumeValidator(response(200)))
        assertNull(resumeValidator(response(200, "ETag" to "\"a\"", "Accept-Ranges" to "none")))
        // A part, or a file of no known length, is not a whole to take the rest of.
        assertNull(resumeValidator(response(206, "ETag" to "\"a\"")))
        assertNull(resumeValidator(response(200, "ETag" to "\"a\"", length = 0)))
    }

    private companion object {
        /** Several chunks of the sealed format, and no two stretches alike, so a part put in the wrong place shows. */
        val FILE = ByteArray(250_000) { i -> (i * 31 + i / 251).toByte() }
    }
}
