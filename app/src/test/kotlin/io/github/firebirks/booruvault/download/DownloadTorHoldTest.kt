package io.github.firebirks.booruvault.download

import io.github.firebirks.booruvault.booru.BooruEngine
import io.github.firebirks.booruvault.booru.BooruSource
import io.github.firebirks.booruvault.booru.Post
import io.github.firebirks.booruvault.booru.Rating
import io.github.firebirks.booruvault.net.TorUnverifiedException
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * A download asked for while Tor's exit is being checked, as right after Orbot comes back, is
 * held back by the gate: it waits for the check and goes out once it passes, instead of failing
 * on what a few seconds mend.
 */
class DownloadTorHoldTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val fastKdf get() = Kdf.newParams(memoryKiB = 1024, iterations = 1)
    private val source = BooruSource("safebooru-donmai", "Safebooru", BooruEngine.DANBOORU, "https://safebooru.donmai.us")

    /** Whether the exit counts as verified: the gate below refuses every request until it does. */
    private val verified = AtomicBoolean(false)
    private val waits = AtomicInteger()

    @After
    fun tearDown() {
        managerScope.cancel()
        scope.cancel()
    }

    /** Serves one small file to whoever asks. */
    private class FileServer : AutoCloseable {
        private val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val connections = AtomicInteger()
        val port: Int get() = server.localPort

        init {
            thread(isDaemon = true) {
                while (!server.isClosed) {
                    val socket = runCatching { server.accept() }.getOrNull() ?: break
                    connections.incrementAndGet()
                    thread(isDaemon = true) {
                        runCatching {
                            socket.use {
                                val reader = it.getInputStream().bufferedReader()
                                while (reader.readLine()?.isNotEmpty() == true) Unit
                                val output = it.getOutputStream()
                                output.write("HTTP/1.1 200 OK\r\nContent-Type: image/jpeg\r\nContent-Length: $SIZE\r\nConnection: close\r\n\r\n".toByteArray())
                                output.write(ByteArray(SIZE) { i -> i.toByte() })
                                output.flush()
                            }
                        }
                    }
                }
            }
        }

        override fun close() = server.close()

        companion object {
            const val SIZE = 10 * 1024
        }
    }

    private fun post(port: Int) = Post(
        source.id, 1, null, null, "http://127.0.0.1:$port/1.jpg", 1, 1, "jpg",
        rating = Rating.GENERAL, postUrl = "https://safebooru.donmai.us/posts/1",
    )

    private suspend fun manager(awaitTorVerdict: suspend () -> Boolean): DownloadManager {
        val root = tmp.newFolder()
        val vault = VaultManager(root, json)
        val settings = SettingsRepository(root, vault, json, scope)
        vault.create("pw-123456".toCharArray(), fastKdf)
        withTimeout(5000) { settings.ready.first { it } }
        val store = VaultStore(root, vault, json, scope)
        val client = OkHttpClient.Builder()
            .addInterceptor { chain -> if (verified.get()) chain.proceed(chain.request()) else throw TorUnverifiedException() }
            .build()
        return DownloadManager(
            {}, { error("no API needed") }, client, { true }, { _, _ -> false }, store, vault, settings, json, root, managerScope,
            timeoutRetryPauseMs = 0,
            awaitTorVerdict = {
                waits.incrementAndGet()
                awaitTorVerdict()
            },
        )
    }

    private suspend fun DownloadManager.settled() =
        withTimeout(20_000) { tasks.first { list -> list.isNotEmpty() && list.none { it.active } }.single() }

    @Test
    fun `a download held back by the exit check goes out once the check passes`() = runBlocking {
        FileServer().use { server ->
            val downloads = manager {
                verified.set(true)
                true
            }
            downloads.enqueue(listOf(post(server.port)), source)
            val task = downloads.settled()
            assertEquals(DownloadStatus.DONE, task.status)
            assertEquals(FileServer.SIZE.toLong(), task.bytes)
            assertEquals(1, waits.get())
            assertEquals(1, server.connections.get())
        }
    }

    @Test
    fun `a download held back with no check to wait for fails as unverified`() = runBlocking {
        FileServer().use { server ->
            val downloads = manager { false }
            downloads.enqueue(listOf(post(server.port)), source)
            val task = downloads.settled()
            assertEquals(DownloadStatus.FAILED, task.status)
            assertEquals(Problem.Code.TOR_UNVERIFIED, task.problem?.code)
            assertEquals(1, waits.get())
            assertEquals(0, server.connections.get())
        }
    }

    @Test
    fun `a verdict withdrawn again each time is not waited for without end`() = runBlocking {
        FileServer().use { server ->
            // Says the check passed while the gate goes on refusing.
            val downloads = manager { true }
            downloads.enqueue(listOf(post(server.port)), source)
            val task = downloads.settled()
            assertEquals(DownloadStatus.FAILED, task.status)
            assertEquals(Problem.Code.TOR_UNVERIFIED, task.problem?.code)
            assertEquals(3, waits.get())
            assertEquals(0, server.connections.get())
        }
    }
}
