package io.github.firebirks.booruvault.download

import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.key.Keyer
import coil3.request.Options
import io.github.firebirks.booruvault.booru.BooruSource
import io.github.firebirks.booruvault.booru.bytesUpTo
import io.github.firebirks.booruvault.booru.withResponse
import io.github.firebirks.booruvault.util.moveOver
import io.github.firebirks.booruvault.vault.VaultLockedException
import io.github.firebirks.booruvault.vault.VaultManager
import io.github.firebirks.booruvault.vault.VaultStore
import io.github.firebirks.booruvault.vault.crypto.SealedFile
import io.github.firebirks.booruvault.vault.crypto.FileLocks
import io.github.firebirks.booruvault.vault.crypto.SealedFormat
import io.github.firebirks.booruvault.vault.crypto.SealedOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okio.Buffer
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * The previews of the downloads listed, kept so that the list does not ask the sites for them
 * again each time it is shown: the images in memory go with a lock, and with the app left in the
 * background. Each is sealed to the vault's key as `previews/<id>`, under the id its row carries,
 * the first time it is wanted: by the row when it is shown, or by the download when it is done,
 * which saves the same bytes as the item's thumbnail. So a preview is asked of its site once.
 *
 * They are a cache: one that will not open is deleted and fetched again, none is in a backup,
 * and each goes with its row.
 */
class QueuePreviews(root: File, private val vault: VaultManager, private val client: OkHttpClient) {
    private val dir = File(root, DIR)

    /** One fetch at a time for each preview: the row and the download may want it at once. */
    private val fetching = ConcurrentHashMap<String, Mutex>()

    /** Whether what is kept can be read; a locked vault can only add to it. */
    val unlocked: Boolean get() = vault.keys != null

    /**
     * The preview kept under [id], or the one at [url], which is then kept. Null when there is
     * neither, or the site has none to give; a request that fails throws. Behind a locked vault
     * what is kept cannot be read, and the site is asked.
     */
    suspend fun bytes(id: String, url: String?, source: BooruSource): ByteArray? = withContext(Dispatchers.IO) {
        require(VaultStore.ID.matches(id)) { "not a preview id" }
        fetching.computeIfAbsent(id) { Mutex() }.withLock {
            read(id) ?: url?.let { fetch(it, source) }?.also { store(id, it) }
        }
    }

    private fun read(id: String): ByteArray? {
        val keys = vault.keys ?: return null
        val file = File(dir, id)
        if (!file.isFile) return null
        return try {
            SealedFile(file, keys).use { it.readAll() }
        } catch (e: Exception) {
            // Cut short as it was written, or sealed to a key since retired: fetched anew.
            file.delete()
            null
        }
    }

    private suspend fun fetch(url: String, source: BooruSource): ByteArray? =
        client.newCall(downloadRequest(url, source)).withResponse { response ->
            if (response.isSuccessful) response.body.bytesUpTo(MAX_BYTES)?.takeIf { it.isNotEmpty() } else null
        }

    /** A preview that cannot be kept is no failure: it is asked for again the next time. */
    private fun store(id: String, bytes: ByteArray) {
        vault.sealing { recipient ->
            val part = File(dir, "$id.part")
            try {
                dir.mkdirs()
                SealedOutputStream(FileOutputStream(part).buffered(), recipient, quantum = SealedFormat.SMALL_QUANTUM).use { it.write(bytes) }
                if (!part.moveOver(File(dir, id))) part.delete()
            } catch (e: IOException) {
                part.delete()
            }
        }
    }

    /** Removes the previews of rows taken off the list. */
    fun delete(ids: Collection<String>) {
        ids.forEach { id ->
            if (!VaultStore.ID.matches(id)) return@forEach
            FileLocks.delete(File(dir, id))
            File(dir, "$id.part").delete()
            fetching.remove(id)
        }
    }

    /**
     * Removes every preview whose row is not among those [listed] names, as a row dropped when
     * the process ended leaves behind. The folder is read first and the list after: a preview
     * written meanwhile is not seen, and its row is. Null from [listed] calls it off.
     */
    fun sweep(listed: () -> Set<String>?) {
        val names = dir.list() ?: return
        val keep = listed() ?: return
        names.filter { it.substringBefore('.') !in keep }.forEach { FileLocks.delete(File(dir, it)) }
        fetching.keys.retainAll(keep)
    }

    companion object {
        /** The folder, in the vault's own: the re-sealing after a password change takes it in. */
        const val DIR = "previews"

        /** A preview is a small JPEG; anything larger is not worth keeping as one. */
        private const val MAX_BYTES = 4L shl 20
    }
}

/** Coil model for the preview of a download listed: the one kept under [id], or the one at [url]. */
data class QueuePreview(val id: String, val url: String?, val source: BooruSource)

class QueuePreviewFetcher(
    private val data: QueuePreview,
    private val options: Options,
    private val previews: QueuePreviews,
) : Fetcher {
    override suspend fun fetch(): FetchResult {
        // The list is not shown behind a lock, and nothing is asked of a site on its behalf then.
        if (!previews.unlocked) throw VaultLockedException()
        val bytes = previews.bytes(data.id, data.url, data.source) ?: throw IOException("no preview")
        val buffer = Buffer()
        try {
            buffer.write(bytes)
        } finally {
            bytes.fill(0)
        }
        return SourceFetchResult(source = ImageSource(buffer, options.fileSystem), mimeType = null, dataSource = DataSource.DISK)
    }

    class Factory(private val previews: QueuePreviews) : Fetcher.Factory<QueuePreview> {
        override fun create(data: QueuePreview, options: Options, imageLoader: ImageLoader): Fetcher =
            QueuePreviewFetcher(data, options, previews)
    }
}

/** The address is part of the key: a post resolved from its page may come by its preview only then. */
class QueuePreviewKeyer : Keyer<QueuePreview> {
    override fun key(data: QueuePreview, options: Options): String = "preview:${data.id}:${data.url}"
}
