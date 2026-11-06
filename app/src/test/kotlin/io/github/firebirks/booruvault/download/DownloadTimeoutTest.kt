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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Over Tor a transfer that goes quiet is most often a slow circuit, and the next try takes
 * another: a download that times out is tried again by itself a couple of times before it is
 * reported as failed.
 */
class DownloadTimeoutTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val fastKdf get() = Kdf.newParams(memoryKiB = 1024, iterations = 1)
    private val source = BooruSource("safebooru-donmai", "Safebooru", BooruEngine.DANBOORU, "https://safebooru.donmai.us")

    @After
    fun tearDown() {
        managerScope.cancel()
        scope.cancel()
    }

    /** Goes quiet halfway through the first [stalls] transfers, then serves the whole file. */
    private class FlakyServer(private val stalls: Int) : AutoCloseable {
        private val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val connections = AtomicInteger()
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
                                while (reader.readLine()?.isNotEmpty() == true) Unit
                                val output = it.getOutputStream()
                                output.write("HTTP/1.1 200 OK\r\nContent-Type: image/jpeg\r\nContent-Length: $SIZE\r\nConnection: close\r\n\r\n".toByteArray())
                                if (n <= stalls) {
                                    output.write(ByteArray(SIZE / 2))
                                    output.flush()
                                    it.getInputStream().readBytes() // silent until the client hangs up
                                } else {
                                    output.write(ByteArray(SIZE) { i -> i.toByte() })
                                    output.flush()
                                }
                            }
                        }
                    }
                }
            }
        }

        override fun close() = server.close()

        companion object {
            const val SIZE = 100 * 1024
        }
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
        val store = VaultStore(root, vault, json, scope)
        val client = OkHttpClient.Builder().readTimeout(300, TimeUnit.MILLISECONDS).build()
        return DownloadManager({}, { error("no API needed") }, client, { false }, { _, _ -> false }, store, vault, settings, json, root, managerScope, timeoutRetryPauseMs = 0)
    }

    private suspend fun DownloadManager.settled() =
        withTimeout(20_000) { tasks.first { list -> list.isNotEmpty() && list.none { it.active } }.single() }

    @Test
    fun `a download that times out is tried again by itself`() = runBlocking {
        FlakyServer(stalls = 1).use { server ->
            val downloads = manager()
            downloads.enqueue(listOf(post(server.port)), source)
            val task = downloads.settled()
            assertEquals(DownloadStatus.DONE, task.status)
            assertEquals(FlakyServer.SIZE.toLong(), task.bytes)
            assertEquals(2, server.connections.get())
        }
    }

    @Test
    fun `a download that keeps timing out fails with a timeout after its tries`() = runBlocking {
        FlakyServer(stalls = Int.MAX_VALUE).use { server ->
            val downloads = manager()
            downloads.enqueue(listOf(post(server.port)), source)
            val task = downloads.settled()
            assertEquals(DownloadStatus.FAILED, task.status)
            assertEquals(Problem.Code.TIMEOUT, task.problem?.code)
            assertEquals(3, server.connections.get())
        }
    }
}
