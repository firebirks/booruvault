package io.github.firebirks.booruvault.vault

import io.github.firebirks.booruvault.util.Problem
import io.github.firebirks.booruvault.util.ProblemException
import io.github.firebirks.booruvault.util.moveOver
import io.github.firebirks.booruvault.vault.crypto.Recipient
import io.github.firebirks.booruvault.vault.crypto.SealedFile
import io.github.firebirks.booruvault.vault.crypto.SealedFormat
import io.github.firebirks.booruvault.vault.crypto.SealedOutputStream
import io.github.firebirks.booruvault.vault.crypto.SyncedFileOutputStream
import io.github.firebirks.booruvault.vault.crypto.VaultKeys
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.UUID

class VaultMissingException : ProblemException(Problem.Code.NO_VAULT)
class VaultLockedException : ProblemException(Problem.Code.VAULT_LOCKED)

/** A download or import longer than [VaultStore.MAX_MEDIA_BYTES]: stopped where it stood. */
class MediaTooLargeException : ProblemException(Problem.Code.TOO_LARGE)

/**
 * The vault's contents. Every item is three sealed files named by a random id:
 * `media/<id>`, `thumbs/<id>` and `meta/<id>`. The meta files are the source of truth;
 * `index.bin` is a sealed cache of all of them so that unlocking does not decrypt thousands of
 * files, and is rebuilt from them whenever it is missing or unreadable.
 *
 * The index is the whole list in one file, megabytes of it in a large vault, so it is not
 * written for every change: the list in memory is brought up to date at once, and the index
 * once changes have stopped coming for [indexQuietMs], or [indexMaxWaitMs] after the first of a
 * run that does not stop. Nothing is lost by an index that lags, at a lock or at the end of the
 * process: the next unlock reads the meta files it does not list and drops the entries whose
 * files are gone.
 *
 * Writing needs only the public key, so downloads can add items while the vault is locked;
 * they appear the next time it is unlocked.
 */
@OptIn(ExperimentalSerializationApi::class)
class VaultStore(
    private val root: File,
    private val manager: VaultManager,
    private val json: Json,
    scope: CoroutineScope,
    private val indexQuietMs: Long = 30_000,
    private val indexMaxWaitMs: Long = 120_000,
) {
    private val mediaDir = File(root, "media")
    private val thumbDir = File(root, "thumbs")
    private val metaDir = File(root, "meta")
    private val indexFile = File(root, "index.bin")
    private val listSerializer = ListSerializer(VaultItem.serializer())

    private val mutex = Mutex()
    private val reconcileRequests = Channel<Unit>(Channel.CONFLATED)
    private val indexRequests = Channel<Unit>(Channel.CONFLATED)

    /** True while the list in memory holds what the index on storage does not. Guarded by [mutex]. */
    private var indexStale = false

    /** How many times the index has been written; tests count on it. */
    @Volatile
    internal var indexWrites = 0
        private set

    /** Completed once leftovers of interrupted writes are gone; writes wait for it. */
    private val cleaned = CompletableDeferred<Unit>()
    private var loadedKeys: VaultKeys? = null

    private val _items = MutableStateFlow<List<VaultItem>>(emptyList())

    /** Newest first. Empty while locked. */
    val items: StateFlow<List<VaultItem>> = _items.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val inFlight = java.util.concurrent.atomic.AtomicInteger()

    /** Writes begun and not yet renamed into place; each seals to the recipient of its start. */
    val writesInFlight: Int get() = inFlight.get()

    init {
        scope.launch(Dispatchers.IO) {
            // Leftovers of writes interrupted by a crash, the re-sealer's copies included; new
            // writes wait until this is done.
            try {
                mediaDir.listFiles { f -> f.name.endsWith(".part") }?.forEach { it.delete() }
                thumbDir.listFiles { f -> f.name.endsWith(".part") }?.forEach { it.delete() }
                metaDir.listFiles { f -> f.name.endsWith(".tmp") }?.forEach { it.delete() }
                listOf(root, mediaDir, thumbDir, metaDir).forEach { dir -> dir.listFiles { f -> f.name.endsWith(".rekey") }?.forEach { it.delete() } }
            } finally {
                cleaned.complete(Unit)
            }
            manager.state.collect { state ->
                mutex.withLock {
                    if (state is VaultState.Unlocked) {
                        // A password change opens the vault anew over a list that may be ahead
                        // of the index: written first, it is not read back one file at a time.
                        flushIndex()
                        _loading.value = true
                        try {
                            load(state.keys)
                        } finally {
                            _loading.value = false
                        }
                    } else {
                        // Nothing is written on the way out: a lock forgets at once, and a
                        // write still waiting must not take the emptied list for the vault's.
                        indexStale = false
                        loadedKeys = null
                        _items.value = emptyList()
                    }
                }
            }
        }
        scope.launch(Dispatchers.IO) {
            for (request in reconcileRequests) {
                mutex.withLock { loadedKeys?.let { reconcile(it) } }
                // New items tend to arrive in bursts; batch them into fewer passes.
                delay(750)
            }
        }
        scope.launch(Dispatchers.IO) {
            for (request in indexRequests) {
                // Until changes stop coming for a while, though not past the longest wait.
                val deadline = System.nanoTime() + indexMaxWaitMs * 1_000_000
                while (true) {
                    val left = (deadline - System.nanoTime()) / 1_000_000
                    if (left <= 0) break
                    withTimeoutOrNull(minOf(indexQuietMs, left)) { indexRequests.receive() } ?: break
                }
                mutex.withLock { flushIndex() }
            }
        }
    }

    fun newId(): String = UUID.randomUUID().toString()

    /**
     * An id names a file in the vault's folders; only the UUIDs the app makes are accepted, so a
     * forged meta file, sealed to the public key that sits in clear in the header, cannot name
     * a path outside them.
     */
    private fun checkId(id: String): String {
        if (!ID.matches(id)) throw IllegalArgumentException("not a vault id")
        return id
    }

    fun containsPost(sourceId: String, postId: Long): Boolean =
        _items.value.any { it.postId == postId && it.sourceId == sourceId }

    fun containsMd5(md5: String): Boolean = _items.value.any { it.md5.equals(md5, ignoreCase = true) }

    private fun recipient(): Recipient = manager.recipient ?: throw VaultMissingException()

    private fun requireKeys(): VaultKeys = manager.keys ?: throw VaultLockedException()

    /** Seals what [block] writes as the media of item [id]. Returns the plaintext size. */
    suspend fun writeMedia(id: String, block: suspend (OutputStream) -> Unit): Long = withContext(Dispatchers.IO) {
        checkId(id)
        cleaned.await()
        inFlight.incrementAndGet()
        try {
            val recipient = recipient()
            mediaDir.mkdirs()
            val part = File(mediaDir, "$id.part")
            try {
                // Synced before the rename, as every write that becomes a file of the vault is: a
                // power cut right after must not leave an item that is there in name only.
                val sealed = SealedOutputStream(SyncedFileOutputStream(part).buffered(256 * 1024), recipient)
                sealed.use { block(CappedOutputStream(it, MAX_MEDIA_BYTES)) }
                if (!part.moveOver(File(mediaDir, id))) throw IOException("cannot store media")
                sealed.bytesWritten
            } catch (e: Throwable) {
                part.delete()
                throw e
            }
        } finally {
            inFlight.decrementAndGet()
        }
    }

    suspend fun writeThumbnail(id: String, jpeg: ByteArray) = withContext(Dispatchers.IO) {
        checkId(id)
        cleaned.await()
        inFlight.incrementAndGet()
        try {
            thumbDir.mkdirs()
            val part = File(thumbDir, "$id.part")
            SealedOutputStream(SyncedFileOutputStream(part).buffered(), recipient(), quantum = SealedFormat.SMALL_QUANTUM).use { it.write(jpeg) }
            if (!part.moveOver(File(thumbDir, id))) throw IOException("cannot store thumbnail")
        } finally {
            inFlight.decrementAndGet()
        }
    }

    /** Makes an item whose media is already written visible. */
    suspend fun commit(item: VaultItem) = withContext(Dispatchers.IO) {
        checkId(item.id)
        cleaned.await()
        inFlight.incrementAndGet()
        try {
            metaDir.mkdirs()
            val tmp = File(metaDir, "${item.id}.tmp")
            val bytes = json.encodeToString(VaultItem.serializer(), item).toByteArray()
            try {
                SealedOutputStream(SyncedFileOutputStream(tmp).buffered(), recipient(), quantum = SealedFormat.SMALL_QUANTUM).use { it.write(bytes) }
            } finally {
                bytes.fill(0)
            }
            if (!tmp.moveOver(File(metaDir, item.id))) throw IOException("cannot store metadata")
        } finally {
            inFlight.decrementAndGet()
        }
        reconcileRequests.trySend(Unit)
    }

    /** Removes whatever was written for an item that will not be committed. */
    fun discard(id: String) {
        checkId(id)
        File(metaDir, id).delete()
        File(mediaDir, id).delete()
        File(mediaDir, "$id.part").delete()
        File(thumbDir, id).delete()
    }

    suspend fun delete(ids: Set<String>) = withContext(Dispatchers.IO) {
        mutex.withLock {
            ids.forEach(::checkId)
            // Meta first: an item without meta is gone even if the rest outlives a crash.
            ids.forEach { File(metaDir, it).delete() }
            ids.forEach { discard(it) }
            _items.value = _items.value.filterNot { it.id in ids }
            indexChanged()
        }
    }

    fun openMedia(id: String): SealedFile = SealedFile(File(mediaDir, checkId(id)), requireKeys())

    fun openThumbnail(id: String): SealedFile = SealedFile(File(thumbDir, checkId(id)), requireKeys())

    fun item(id: String): VaultItem? = _items.value.firstOrNull { it.id == id }

    private fun load(keys: VaultKeys) {
        // Decoded as it is decrypted, chunk by chunk: the whole index is never in memory as
        // text, which in a large vault would be tens of megabytes that cannot be overwritten.
        val cached = try {
            if (indexFile.exists()) SealedFile(indexFile, keys).use { json.decodeFromStream(listSerializer, it.inputStream()) } else null
        } catch (e: Exception) {
            null
        }
        _items.value = cached.orEmpty()
        loadedKeys = keys
        // Without a usable index, every meta file counts as new and the index is rebuilt. What
        // an unlock finds is written at once: it is one write, and the next unlock is spared it.
        reconcile(keys, forceSave = cached == null && indexFile.exists(), atOnce = true)
    }

    /** Brings the list in line with the meta files on disk; the index follows, [atOnce] or when changes settle. */
    private fun reconcile(keys: VaultKeys, forceSave: Boolean = false, atOnce: Boolean = false) {
        val onDisk = metaDir.list()?.filterNot { it.endsWith(".tmp") }?.toSet().orEmpty()
        val current = _items.value
        val kept = current.filter { it.id in onDisk }
        val known = kept.mapTo(HashSet()) { it.id }
        val accepted = ArrayList<VaultItem>()
        val seenPosts = kept.mapNotNullTo(HashSet()) { it.postKey() }
        val seenMd5 = kept.mapNotNullTo(HashSet()) { it.md5?.lowercase() }
        for (id in onDisk - known) {
            val item = try {
                SealedFile(File(metaDir, id), keys).use { sealed ->
                    decode(sealed.readAll()) { text -> json.decodeFromString(VaultItem.serializer(), text) }
                }
            } catch (e: Exception) {
                continue
            }
            // The item must be the file it sits in: a meta file naming another id is not trusted.
            if (item.id != id || !ID.matches(id)) continue
            val postKey = item.postKey()
            val md5 = item.md5?.lowercase()
            if ((postKey != null && postKey in seenPosts) || (md5 != null && md5 in seenMd5)) {
                // Downloaded twice while the vault was locked; keep the first copy.
                discard(item.id)
                continue
            }
            postKey?.let(seenPosts::add)
            md5?.let(seenMd5::add)
            accepted += item
        }
        if (!forceSave && accepted.isEmpty() && kept.size == current.size) return
        _items.value = (kept + accepted).sortedByDescending { it.addedAt }
        indexChanged()
        if (atOnce) flushIndex()
    }

    /** The list in memory has moved on from the index: it is written once changes settle. */
    private fun indexChanged() {
        indexStale = true
        indexRequests.trySend(Unit)
    }

    /** Writes the index if the list has moved on from it. One that cannot be written stays owed. */
    private fun flushIndex() {
        if (indexStale && saveIndex(_items.value)) indexStale = false
    }

    /**
     * Seals [items] as the index, encoded straight into the sealed stream. False when it could
     * not be written, on a full disk for one: the index is a cache, and one left as it was
     * costs the next unlock some reading, where a failure thrown from here would cost the app.
     */
    private fun saveIndex(items: List<VaultItem>): Boolean {
        val recipient = manager.recipient ?: return false
        val tmp = File(root, "index.bin.tmp")
        return try {
            SealedOutputStream(SyncedFileOutputStream(tmp).buffered(), recipient, quantum = SealedFormat.SMALL_QUANTUM).use {
                json.encodeToStream(listSerializer, items, it)
            }
            val moved = tmp.moveOver(indexFile)
            if (moved) indexWrites++ else tmp.delete()
            moved
        } catch (e: IOException) {
            tmp.delete()
            false
        }
    }

    /** Decodes decrypted bytes, then overwrites them. */
    private inline fun <T> decode(bytes: ByteArray, parse: (String) -> T): T = try {
        parse(bytes.decodeToString())
    } finally {
        bytes.fill(0)
    }

    private fun VaultItem.postKey(): String? = if (sourceId != null && postId != null) "$sourceId/$postId" else null

    companion object {
        /** The most one media file may hold; a download or import past it is dropped. */
        const val MAX_MEDIA_BYTES = 2L shl 30

        internal val ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    }
}

/** Passes bytes through up to [max] in all, then refuses the rest. */
private class CappedOutputStream(private val out: OutputStream, private val max: Long) : OutputStream() {
    private var written = 0L

    override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

    override fun write(b: ByteArray, off: Int, len: Int) {
        written += len
        if (written > max) throw MediaTooLargeException()
        out.write(b, off, len)
    }

    override fun flush() = out.flush()

    override fun close() = out.close()
}
