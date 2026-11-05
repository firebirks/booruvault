package io.github.firebirks.booruvault.download

import io.github.firebirks.booruvault.booru.BooruEngine
import io.github.firebirks.booruvault.booru.BooruSource
import io.github.firebirks.booruvault.booru.Post
import io.github.firebirks.booruvault.booru.Rating
import io.github.firebirks.booruvault.settings.SettingsRepository
import io.github.firebirks.booruvault.vault.VaultManager
import io.github.firebirks.booruvault.vault.VaultStore
import io.github.firebirks.booruvault.vault.crypto.Kdf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Cancelling a download must end it at once, even when the server has gone quiet: a read blocked
 * on an idle socket would otherwise keep its place among the three parallel transfers until the
 * read timeout, and then report a failure over the cancellation.
 */
class DownloadCancelTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val fastKdf get() = Kdf.newParams(memoryKiB = 1024, iterations = 1)
    private val source = BooruSource("safebooru-donmai", "Safebooru", BooruEngine.DANBOORU, "https://safebooru.donmai.us")

    /** Shorter than the test's own waits: a cancellation that only waited for it would show. */
    private val readTimeoutMs = 2000L

    @After
    fun tearDown() {
        managerScope.cancel()
        scope.cancel()
    }

    /** Answers every request with the headers and the start of a long body, then says nothing more. */
    private class StallingServer : AutoCloseable {
        private val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        private val open = CopyOnWriteArrayList<Socket>()
        val port: Int get() = server.localPort

        init {
            thread(isDaemon = true) {
                while (!server.isClosed) {
                    val socket = runCatching { server.accept() }.getOrNull() ?: break
                    open += socket
                    thread(isDaemon = true) {
                        runCatching {
                            val reader = socket.getInputStream().bufferedReader()
                            while (reader.readLine()?.isNotEmpty() == true) Unit
                            val output = socket.getOutputStream()
                            output.write("HTTP/1.1 200 OK\r\nContent-Type: image/jpeg\r\nContent-Length: 10000000\r\n\r\n".toByteArray())
                            output.write(ByteArray(300 * 1024))
                            output.flush()
                            // The socket stays open and silent from here on.
                        }
                    }
                }
            }
        }

        override fun close() {
            open.forEach { runCatching { it.close() } }
            server.close()
        }
    }

    private fun post(id: Long, port: Int) = Post(
        source.id, id, null, null, "http://127.0.0.1:$port/$id.jpg", 1, 1, "jpg",
        rating = Rating.GENERAL, postUrl = "https://safebooru.donmai.us/posts/$id",
    )

    private suspend fun manager(): DownloadManager {
        val root = tmp.newFolder()
        val vault = VaultManager(root, json)
        val settings = SettingsRepository(root, vault, json, scope)
        vault.create("pw-123456".toCharArray(), fastKdf)
        withTimeout(5000) { settings.ready.first { it } }
        val store = VaultStore(root, vault, json, scope)
        val client = OkHttpClient.Builder().readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS).build()
        return DownloadManager({}, { error("no API needed") }, client, { false }, { _, _ -> false }, store, vault, settings, json, root, managerScope)
    }

    private fun DownloadManager.status(id: Long) = tasks.value.single { it.post.id == id }.status

    @Test
    fun `cancelling a download the server went quiet on ends it at once and frees its place`() = runBlocking {
        StallingServer().use { server ->
            val downloads = manager()
            // Three transfers under way, each stalled after its first bytes, and a fourth waiting for a place.
            downloads.enqueue((1L..4L).map { post(it, server.port) }, source)
            withTimeout(10_000) { downloads.tasks.first { list -> list.count { it.bytes > 0 } == 3 } }
            assertEquals(DownloadStatus.QUEUED, downloads.status(4))

            val cancelled = downloads.tasks.value.first { it.bytes > 0 }
            downloads.cancel(cancelled.key)

            // The place is free well before the read timeout could have freed it.
            withTimeout(readTimeoutMs / 2) { downloads.tasks.first { list -> list.single { it.post.id == 4L }.status == DownloadStatus.RUNNING } }
            // Once the timeout has passed, the read that was cut short has not been taken for a failure,
            // while the transfers left stalled did meet the timeout: it was short enough to tell the two apart.
            delay(readTimeoutMs + 1500)
            assertEquals(DownloadStatus.CANCELLED, downloads.status(cancelled.post.id))
            val stalled = downloads.tasks.value.filter { it.post.id in 1L..3L && it.post.id != cancelled.post.id }
            assertTrue(stalled.all { it.status == DownloadStatus.FAILED })
        }
    }
}
