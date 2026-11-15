package io.github.firebirks.booruvault.download

import io.github.firebirks.booruvault.booru.BooruEngine
import io.github.firebirks.booruvault.booru.BooruSource
import io.github.firebirks.booruvault.booru.Post
import io.github.firebirks.booruvault.booru.Rating
import io.github.firebirks.booruvault.settings.SettingsRepository
import io.github.firebirks.booruvault.vault.VaultManager
import io.github.firebirks.booruvault.vault.VaultStore
import io.github.firebirks.booruvault.vault.crypto.Kdf
import io.github.firebirks.booruvault.vault.crypto.SealedFormat
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
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * The previews of the downloads listed are kept, sealed, for as long as their rows are: the list
 * shown again after the images in memory were dropped must not ask the sites for them anew, the
 * download must not ask for the one its row already has, and a row taken off the list must leave
 * nothing behind.
 */
class DownloadPreviewTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val fastKdf get() = Kdf.newParams(memoryKiB = 1024, iterations = 1)
    private val source = BooruSource("safebooru-donmai", "Safebooru", BooruEngine.DANBOORU, "https://safebooru.donmai.us")
    private val password = "pw-123456"
    private lateinit var root: File
    private lateinit var vault: VaultManager
    private lateinit var store: VaultStore

    @After
    fun tearDown() {
        managerScope.cancel()
        scope.cancel()
    }

    /** Serves [PREVIEW] at `/p<id>.jpg` and [FILE] at anything else, unless the id is among those [refused]. */
    private class Site : AutoCloseable {
        private val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val previewRequests = AtomicInteger()
        val refused = AtomicBoolean(false)
        val port: Int get() = server.localPort

        init {
            thread(isDaemon = true) {
                while (!server.isClosed) {
                    val socket = runCatching { server.accept() }.getOrNull() ?: break
                    thread(isDaemon = true) {
                        runCatching {
                            socket.use {
                                val reader = it.getInputStream().bufferedReader()
                                val path = reader.readLine().split(' ')[1]
                                while (reader.readLine()?.isNotEmpty() == true) Unit
                                val preview = path.startsWith("/p")
                                if (preview) previewRequests.incrementAndGet()
                                val output = it.getOutputStream()
                                if (!preview && refused.get()) {
                                    output.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                                } else {
                                    val body = if (preview) PREVIEW else FILE
                                    output.write("HTTP/1.1 200 OK\r\nContent-Type: image/jpeg\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                                    output.write(body)
                                }
                                output.flush()
                            }
                        }
                    }
                }
            }
        }

        override fun close() = server.close()
    }

    private fun post(id: Long, port: Int) = Post(
        source.id, id, "http://127.0.0.1:$port/p$id.jpg", null, "http://127.0.0.1:$port/$id.jpg", 1, 1, "jpg",
        rating = Rating.GENERAL, postUrl = "https://safebooru.donmai.us/posts/$id",
    )

    private suspend fun manager(): DownloadManager {
        root = tmp.newFolder()
        vault = VaultManager(root, json)
        val settings = SettingsRepository(root, vault, json, scope)
        vault.create(password.toCharArray(), fastKdf)
        withTimeout(5000) { settings.ready.first { it } }
        store = VaultStore(root, vault, json, scope)
        return DownloadManager({}, { error("no API needed") }, OkHttpClient(), { false }, { _, _ -> false }, store, vault, settings, json, root, managerScope, timeoutRetryPauseMs = 0)
    }

    private suspend fun DownloadManager.settled(count: Int = 1) =
        withTimeout(30_000) { tasks.first { list -> list.size == count && list.none { it.active } } }

    private suspend fun until(what: () -> Boolean) = withTimeout(5000) { while (!what()) delay(20) }

    private fun kept(task: DownloadTask) = File(File(root, QueuePreviews.DIR), task.previewId)

    private suspend fun DownloadManager.preview(task: DownloadTask) = previews.bytes(task.previewId, task.post.previewUrl, task.source)

    @Test
    fun `a download done saves the preview once, for the item and for its row`() = runBlocking {
        Site().use { site ->
            val downloads = manager()
            downloads.enqueue(listOf(post(1, site.port)), source)
            val task = downloads.settled().single()
            assertEquals(DownloadStatus.DONE, task.status)
            val item = withTimeout(5000) { store.items.first { it.isNotEmpty() } }.single()
            assertArrayEquals(PREVIEW, store.openThumbnail(item.id).use { it.readAll() })
            // The row shown afterwards reads what was kept: sealed, and not asked for again.
            assertArrayEquals(PREVIEW, downloads.preview(task))
            assertEquals(1, site.previewRequests.get())
            assertTrue(kept(task).readBytes().copyOf(SealedFormat.MAGIC.size).contentEquals(SealedFormat.MAGIC))
        }
    }

    @Test
    fun `a download uses the preview its row was shown with`() = runBlocking {
        Site().use { site ->
            site.refused.set(true)
            val downloads = manager()
            downloads.enqueue(listOf(post(1, site.port)), source)
            val failed = downloads.settled().single()
            assertEquals(DownloadStatus.FAILED, failed.status)
            assertEquals(0, site.previewRequests.get())
            // The row is shown, twice: the site is asked the first time only.
            assertArrayEquals(PREVIEW, downloads.preview(failed))
            assertArrayEquals(PREVIEW, downloads.preview(failed))
            assertEquals(1, site.previewRequests.get())

            site.refused.set(false)
            downloads.retry(failed.key)
            assertEquals(DownloadStatus.DONE, withTimeout(30_000) { downloads.tasks.first { it.single().status == DownloadStatus.DONE } }.single().status)
            val item = withTimeout(5000) { store.items.first { it.isNotEmpty() } }.single()
            assertArrayEquals(PREVIEW, store.openThumbnail(item.id).use { it.readAll() })
            assertEquals(1, site.previewRequests.get())
        }
    }

    @Test
    fun `a row taken off the list takes its preview with it`() = runBlocking {
        Site().use { site ->
            val downloads = manager()
            downloads.enqueue(listOf(post(1, site.port), post(2, site.port)), source)
            val (first, second) = downloads.settled(2)
            assertTrue(kept(first).isFile && kept(second).isFile)

            downloads.remove(first.key)
            until { !kept(first).exists() }
            assertTrue(kept(second).isFile)

            downloads.clearFinished()
            until { !kept(second).exists() }
            assertTrue(downloads.tasks.value.isEmpty())
        }
    }

    @Test
    fun `a lock keeps the previews of the rows it saves and drops the others`() = runBlocking {
        Site().use { site ->
            val downloads = manager()
            downloads.enqueue(listOf(post(1, site.port)), source)
            val done = downloads.settled().single()
            site.refused.set(true)
            downloads.enqueue(listOf(post(2, site.port)), source)
            val failed = downloads.settled(2).single { it.post.id == 2L }
            assertEquals(DownloadStatus.FAILED, failed.status)
            downloads.preview(failed)
            assertEquals(2, site.previewRequests.get())

            vault.lock()
            // What is done is not saved, and its preview goes; what failed comes back with its own.
            until { !kept(done).exists() }
            assertTrue(kept(failed).isFile)
            // Left by a row that was listed when the process ended.
            val orphan = File(kept(failed).parentFile, UUID.randomUUID().toString()).apply { writeBytes(ByteArray(8)) }

            assertTrue(vault.unlock(password.toCharArray()))
            val restored = withTimeout(5000) { downloads.tasks.first { it.isNotEmpty() } }.single()
            assertEquals(failed.previewId, restored.previewId)
            until { !orphan.exists() }
            assertTrue(kept(restored).isFile)
            assertArrayEquals(PREVIEW, downloads.preview(restored))
            assertEquals(2, site.previewRequests.get())

            downloads.clearFailed()
            until { !kept(restored).exists() }
            assertFalse(orphan.exists())
        }
    }

    private companion object {
        val PREVIEW = ByteArray(9_000) { i -> (i * 7 + 3).toByte() }
        val FILE = ByteArray(40_000) { i -> (i * 31 + i / 251).toByte() }
    }
}
