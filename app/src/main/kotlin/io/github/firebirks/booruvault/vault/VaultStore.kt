package io.github.firebirks.booruvault.vault

import io.github.firebirks.booruvault.util.Problem
import io.github.firebirks.booruvault.util.ProblemException
import io.github.firebirks.booruvault.vault.crypto.Recipient
import io.github.firebirks.booruvault.vault.crypto.SealedFile
import io.github.firebirks.booruvault.vault.crypto.SealedOutputStream
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
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.UUID

class VaultMissingException : ProblemException(Problem.Code.NO_VAULT)
class VaultLockedException : ProblemException(Problem.Code.VAULT_LOCKED)

/**
 * The vault's contents. Every item is three sealed files named by a random id:
 * `media/<id>`, `thumbs/<id>` and `meta/<id>`. The meta files are the source of truth;
 * `index.bin` is a sealed cache of all of them so that unlocking does not decrypt thousands of
 * files, and is rebuilt from them whenever it is missing or unreadable.
 *
 * Writing needs only the public key, so downloads can add items while the vault is locked;
 * they appear the next time it is unlocked.
 */
class VaultStore(
    private val root: File,
    private val manager: VaultManager,
    private val json: Json,
    scope: CoroutineScope,
) {
    private val mediaDir = File(root, "media")
    private val thumbDir = File(root, "thumbs")
    private val metaDir = File(root, "meta")
    private val indexFile = File(root, "index.bin")
    private val listSerializer = ListSerializer(VaultItem.serializer())

    private val mutex = Mutex()
    private val reconcileRequests = Channel<Unit>(Channel.CONFLATED)

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
            // Leftovers of writes interrupted by a crash; new writes wait until this is done.
            try {
                mediaDir.listFiles { f -> f.name.endsWith(".part") }?.forEach { it.delete() }
                thumbDir.listFiles { f -> f.name.endsWith(".part") }?.forEach { it.delete() }
                metaDir.listFiles { f -> f.name.endsWith(".tmp") }?.forEach { it.delete() }
            } finally {
                cleaned.complete(Unit)
            }
            manager.state.collect { state ->
                mutex.withLock {
                    if (state is VaultState.Unlocked) {
                        _loading.value = true
                        try {
                            load(state.keys)
                        } finally {
                            _loading.value = false
                        }
                    } else {
                        loadedKeys = null
                        _items.value = emptyList()
                    }
                }
            }
        }
        scope.launch(Dispatchers.IO) {
            for (request in reconcileRequests) {
                mutex.withLock { loadedKeys?.let { reconcile(it) } }
                // New items tend to arrive in bursts; batch them into fewer index rewrites.
                delay(750)
            }
        }
    }

    fun newId(): String = UUID.randomUUID().toString()

    fun containsPost(sourceId: String, postId: Long): Boolean =
        _items.value.any { it.postId == postId && it.sourceId == sourceId }

    fun containsMd5(md5: String): Boolean = _items.value.any { it.md5.equals(md5, ignoreCase = true) }

    private fun recipient(): Recipient = manager.recipient ?: throw VaultMissingException()

    private fun requireKeys(): VaultKeys = manager.keys ?: throw VaultLockedException()

    /** Seals what [block] writes as the media of item [id]. Returns the plaintext size. */
    suspend fun writeMedia(id: String, block: suspend (OutputStream) -> Unit): Long = withContext(Dispatchers.IO) {
        cleaned.await()
        inFlight.incrementAndGet()
        try {
            val recipient = recipient()
            mediaDir.mkdirs()
            val part = File(mediaDir, "$id.part")
            try {
                val sealed = SealedOutputStream(part.outputStream().buffered(256 * 1024), recipient)
                sealed.use { block(it) }
                if (!part.renameTo(File(mediaDir, id))) throw IOException("cannot store media")
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
        cleaned.await()
        inFlight.incrementAndGet()
        try {
            thumbDir.mkdirs()
            val part = File(thumbDir, "$id.part")
            SealedOutputStream(part.outputStream().buffered(), recipient()).use { it.write(jpeg) }
            if (!part.renameTo(File(thumbDir, id))) throw IOException("cannot store thumbnail")
        } finally {
            inFlight.decrementAndGet()
        }
    }

    /** Makes an item whose media is already written visible. */
    suspend fun commit(item: VaultItem) = withContext(Dispatchers.IO) {
        cleaned.await()
        inFlight.incrementAndGet()
        try {
            metaDir.mkdirs()
            val tmp = File(metaDir, "${item.id}.tmp")
            val bytes = json.encodeToString(VaultItem.serializer(), item).toByteArray()
            try {
                SealedOutputStream(tmp.outputStream().buffered(), recipient()).use { it.write(bytes) }
            } finally {
                bytes.fill(0)
            }
            if (!tmp.renameTo(File(metaDir, item.id))) throw IOException("cannot store metadata")
        } finally {
            inFlight.decrementAndGet()
        }
        reconcileRequests.trySend(Unit)
    }

    /** Removes whatever was written for an item that will not be committed. */
    fun discard(id: String) {
        File(metaDir, id).delete()
        File(mediaDir, id).delete()
        File(mediaDir, "$id.part").delete()
        File(thumbDir, id).delete()
    }

    suspend fun delete(ids: Set<String>) = withContext(Dispatchers.IO) {
        mutex.withLock {
            // Meta first: an item without meta is gone even if the rest outlives a crash.
            ids.forEach { File(metaDir, it).delete() }
            ids.forEach { discard(it) }
            val remaining = _items.value.filterNot { it.id in ids }
            _items.value = remaining
            saveIndex(remaining)
        }
    }

    fun openMedia(id: String): SealedFile = SealedFile(File(mediaDir, id), requireKeys())

    fun openThumbnail(id: String): SealedFile = SealedFile(File(thumbDir, id), requireKeys())

    fun item(id: String): VaultItem? = _items.value.firstOrNull { it.id == id }

    private fun load(keys: VaultKeys) {
        val cached = try {
            if (indexFile.exists()) SealedFile(indexFile, keys).use { decode(it.readAll()) { text -> json.decodeFromString(listSerializer, text) } } else null
        } catch (e: Exception) {
            null
        }
        _items.value = cached.orEmpty()
        loadedKeys = keys
        // Without a usable index, every meta file counts as new and the index is rebuilt.
        reconcile(keys, forceSave = cached == null && indexFile.exists())
    }

    /** Brings the index in line with the meta files on disk. */
    private fun reconcile(keys: VaultKeys, forceSave: Boolean = false) {
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
        val merged = (kept + accepted).sortedByDescending { it.addedAt }
        _items.value = merged
        saveIndex(merged)
    }

    private fun saveIndex(items: List<VaultItem>) {
        val recipient = manager.recipient ?: return
        val tmp = File(root, "index.bin.tmp")
        val bytes = json.encodeToString(listSerializer, items).toByteArray()
        try {
            SealedOutputStream(tmp.outputStream().buffered(), recipient).use { it.write(bytes) }
        } finally {
            bytes.fill(0)
        }
        if (!tmp.renameTo(indexFile)) tmp.delete()
    }

    /** Decodes decrypted bytes, then overwrites them. */
    private inline fun <T> decode(bytes: ByteArray, parse: (String) -> T): T = try {
        parse(bytes.decodeToString())
    } finally {
        bytes.fill(0)
    }

    private fun VaultItem.postKey(): String? = if (sourceId != null && postId != null) "$sourceId/$postId" else null
}
