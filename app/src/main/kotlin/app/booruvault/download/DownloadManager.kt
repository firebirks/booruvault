package app.booruvault.download

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import app.booruvault.booru.BooruApiFactory
import app.booruvault.booru.BooruSource
import app.booruvault.booru.Post
import app.booruvault.booru.USER_AGENT
import app.booruvault.booru.await
import app.booruvault.booru.extensionFromUrl
import app.booruvault.settings.AppSettings
import app.booruvault.settings.SettingsRepository
import app.booruvault.settings.allows
import app.booruvault.util.Mime
import app.booruvault.util.Problem
import app.booruvault.util.ProblemException
import app.booruvault.util.toProblem
import app.booruvault.util.safeFileName
import app.booruvault.vault.SealedJson
import app.booruvault.vault.VaultItem
import app.booruvault.vault.VaultManager
import app.booruvault.vault.VaultMissingException
import app.booruvault.vault.VaultState
import app.booruvault.vault.VaultStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

@Serializable
enum class DownloadStatus { QUEUED, RUNNING, DONE, SKIPPED, FAILED, CANCELLED }

data class DownloadTask(
    val key: Long,
    val post: Post,
    val source: BooruSource,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val bytes: Long = 0,
    val total: Long = -1,
    val problem: Problem? = null,
) {
    val active: Boolean get() = status == DownloadStatus.QUEUED || status == DownloadStatus.RUNNING
    val progress: Float? get() = if (total > 0) (bytes.toFloat() / total).coerceIn(0f, 1f) else null
}

/** A search whose results are being gathered page by page to be downloaded. */
data class BatchJob(val key: Long, val source: BooruSource, val tags: String, val target: Int, val found: Int = 0, val nextPage: Int = 1)

/** What survives a lock or a restart: sealed in the vault as `downloads.bin`. */
@Serializable
private data class SavedQueue(val tasks: List<SavedTask> = emptyList(), val batches: List<SavedBatch> = emptyList())

@Serializable
private data class SavedTask(val post: Post, val source: BooruSource, val status: DownloadStatus, val problem: Problem? = null)

@Serializable
private data class SavedBatch(val source: BooruSource, val tags: String, val target: Int, val found: Int, val nextPage: Int)

/**
 * Downloads posts straight into the vault: the response body is sealed as it streams, so the
 * plaintext never touches storage.
 *
 * The queue itself names posts, tags and sites, so it lives in memory only while the vault is
 * unlocked. Locking seals it into the vault, stops every transfer and forgets it; the next unlock
 * picks it up again.
 */
class DownloadManager(
    private val context: Context,
    private val client: OkHttpClient,
    private val json: Json,
    private val store: VaultStore,
    private val vault: VaultManager,
    private val settings: SettingsRepository,
    root: File,
    private val scope: CoroutineScope,
) {
    private val saved = SealedJson(File(root, "downloads.bin"), SavedQueue.serializer(), json)

    /** Whether the queue in memory is the vault's; false while locked or before it is read back. */
    @Volatile
    private var live = false

    /**
     * What downloads need from the settings, copied while unlocked: once the vault locks the
     * settings themselves are gone, and downloads allowed to go on still need these.
     */
    @Volatile
    private var policy = AppSettings()

    /** True while downloads keep going behind a locked vault, as the user allowed. */
    @Volatile
    private var runningLocked = false

    /** Whether locking now would leave downloads running, which then keep their data in memory. */
    val workingWhileLocked: Boolean
        get() = policy.downloadWhileLocked && hasWork()

    /** Called when downloads that ran behind a locked vault are over and their data is dropped. */
    var onLockedWorkDone: () -> Unit = {}

    private fun hasWork(): Boolean = _tasks.value.any { it.active } || _batches.value.isNotEmpty()
    private val saveRequests = Channel<Unit>(Channel.CONFLATED)

    private val _paused = MutableStateFlow(0)

    /** Downloads waiting in the vault for the next unlock. */
    val paused: StateFlow<Int> = _paused.asStateFlow()

    private fun snapshot(): SavedQueue = SavedQueue(
        tasks = _tasks.value.filter { it.active || it.status == DownloadStatus.FAILED }.map {
            SavedTask(it.post, it.source, if (it.active) DownloadStatus.QUEUED else it.status, it.problem)
        },
        batches = _batches.value.map { SavedBatch(it.source, it.tags, it.target, it.found, it.nextPage) },
    )

    private fun persist(queue: SavedQueue = snapshot()) {
        val recipient = vault.recipient ?: return
        runCatching { saved.write(recipient, queue) }
    }

    private fun suspendForLock() {
        if (!live) return
        if (policy.downloadWhileLocked && hasWork()) {
            // Allowed to go on: the queue stays in memory until it drains, then it is dropped.
            runningLocked = true
            persist()
            return
        }
        live = false
        val queue = snapshot()
        persist(queue)
        stopAll()
        // Keep only the switch: everything else from the settings goes with the lock.
        policy = AppSettings(downloadWhileLocked = policy.downloadWhileLocked)
        _paused.value = queue.tasks.count { it.status == DownloadStatus.QUEUED } + queue.batches.size
        onLockedWorkDone()
    }

    /** The last download behind a locked vault finished: seal what is left and forget it. */
    private fun drainedWhileLocked() {
        if (!runningLocked || hasWork() || vault.state.value is VaultState.Unlocked) return
        runningLocked = false
        live = false
        persist()
        stopAll()
        policy = AppSettings(downloadWhileLocked = policy.downloadWhileLocked)
        _paused.value = 0
        onLockedWorkDone()
    }

    private fun stopAll() {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        _tasks.value = emptyList()
        _batches.value = emptyList()
    }

    private fun restore(state: VaultState.Unlocked) {
        runningLocked = false
        // Still running from before the lock: the queue in memory is current.
        if (live) return
        val queue = saved.read(state.keys) ?: SavedQueue()
        live = true
        _paused.value = 0
        val failed = queue.tasks.filter { it.status == DownloadStatus.FAILED }
            .map { DownloadTask(keys.incrementAndGet(), it.post, it.source, DownloadStatus.FAILED, problem = it.problem) }
        _tasks.update { it + failed }
        queue.tasks.filter { it.status == DownloadStatus.QUEUED }.groupBy { it.source }.forEach { (source, list) ->
            enqueue(list.map { it.post }, source)
        }
        queue.batches.forEach { downloadSearch(it.source, it.tags, it.target, it.found, it.nextPage) }
    }
    private val _tasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    val tasks: StateFlow<List<DownloadTask>> = _tasks.asStateFlow()

    private val _batches = MutableStateFlow<List<BatchJob>>(emptyList())
    val batches: StateFlow<List<BatchJob>> = _batches.asStateFlow()

    private val jobs = ConcurrentHashMap<Long, Job>()
    private val keys = AtomicLong()
    private val parallel = Semaphore(3)

    init {
        scope.launch {
            vault.state.collect { state ->
                when (state) {
                    is VaultState.Unlocked -> restore(state)
                    VaultState.Locked -> suspendForLock()
                    VaultState.Missing -> {
                        live = false
                        stopAll()
                        _paused.value = 0
                    }
                }
            }
        }
        scope.launch {
            combine(settings.ready, settings.settings) { ready, current -> if (ready) current else null }
                .collect { current -> if (current != null) policy = current.copy(sources = emptyList(), selectedSourceId = null) }
        }
        scope.launch {
            combine(_tasks, _batches) { tasks, batches -> tasks.any { it.active } || batches.isNotEmpty() }
                .distinctUntilChanged()
                .collect { working -> if (!working) drainedWhileLocked() }
        }
        scope.launch(Dispatchers.IO) {
            // Only status changes matter for what is saved, not byte counts.
            combine(_tasks, _batches) { tasks, batches -> tasks.map { it.key to it.status } to batches.map { it.key to it.nextPage } }
                .distinctUntilChanged()
                .collect { if (live) saveRequests.trySend(Unit) }
        }
        scope.launch(Dispatchers.IO) {
            for (request in saveRequests) {
                if (live) persist()
                delay(1000)
            }
        }
    }


    /** Queues posts not already queued or downloaded. Returns how many were added. */
    fun enqueue(posts: List<Post>, source: BooruSource): Int {
        if (!live) return 0
        val added = ArrayList<DownloadTask>()
        _tasks.update { current ->
            added.clear()
            val taken = current.filter { it.status != DownloadStatus.FAILED && it.status != DownloadStatus.CANCELLED }
                .mapTo(HashSet()) { it.source.id to it.post.id }
            posts.filter { (source.id to it.id) !in taken }.distinctBy { it.id }.forEach {
                added += DownloadTask(keys.incrementAndGet(), it, source)
            }
            current + added
        }
        added.forEach(::launch)
        if (added.isNotEmpty()) startService()
        return added.size
    }

    /** Collects up to [max] results of a search, page by page, and queues them as they arrive. */
    fun downloadSearch(source: BooruSource, tags: String, max: Int, alreadyFound: Int = 0, startPage: Int = 1) {
        if (!live) return
        val batch = BatchJob(keys.incrementAndGet(), source, tags, max, alreadyFound, startPage)
        _batches.update { it + batch }
        startService()
        jobs[batch.key] = scope.launch(Dispatchers.IO) {
            try {
                val api = BooruApiFactory.create(source, client, json)
                val limit = 100
                var page = startPage
                var found = alreadyFound
                while (found < max) {
                    val posts = api.posts(tags, page, limit)
                    if (posts.isEmpty()) break
                    val allowed = posts.filter { policy.allows(it) }.take(max - found)
                    found += allowed.size
                    enqueue(allowed, source)
                    page++
                    _batches.update { list -> list.map { if (it.key == batch.key) it.copy(found = found, nextPage = page) else it } }
                    if (posts.size < limit) break
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Whatever was queued before the failure still downloads.
            } finally {
                jobs.remove(batch.key)
                _batches.update { list -> list.filterNot { it.key == batch.key } }
            }
        }
    }

    fun cancelBatch(key: Long) {
        jobs[key]?.cancel()
    }

    fun cancel(key: Long) {
        jobs[key]?.cancel()
        set(key) { if (it.active) it.copy(status = DownloadStatus.CANCELLED, problem = null) else it }
    }

    fun cancelAll() {
        _batches.value.forEach { cancelBatch(it.key) }
        _tasks.value.filter { it.active }.forEach { cancel(it.key) }
    }

    fun retry(key: Long) {
        val task = _tasks.value.firstOrNull { it.key == key } ?: return
        if (task.status != DownloadStatus.FAILED && task.status != DownloadStatus.CANCELLED) return
        if (!live) return
        set(key) { it.copy(status = DownloadStatus.QUEUED, bytes = 0, total = -1, problem = null) }
        launch(task)
        startService()
    }

    fun retryFailed() {
        _tasks.value.filter { it.status == DownloadStatus.FAILED }.forEach { retry(it.key) }
    }

    fun clearFinished() {
        _tasks.update { list -> list.filter { it.active } }
    }

    private fun set(key: Long, transform: (DownloadTask) -> DownloadTask) {
        _tasks.update { list -> list.map { if (it.key == key) transform(it) else it } }
    }

    private fun launch(task: DownloadTask) {
        jobs[task.key] = scope.launch(Dispatchers.IO) {
            try {
                parallel.withPermit { run(task.key) }
            } finally {
                jobs.remove(task.key)
            }
        }
    }

    private suspend fun run(key: Long) {
        val task = _tasks.value.firstOrNull { it.key == key } ?: return
        if (task.status != DownloadStatus.QUEUED) return
        var post = task.post
        if (store.containsPost(task.source.id, post.id) || post.md5?.let(store::containsMd5) == true) {
            set(key) { it.copy(status = DownloadStatus.SKIPPED, problem = Problem(Problem.Code.ALREADY_IN_VAULT)) }
            return
        }
        set(key) { it.copy(status = DownloadStatus.RUNNING) }
        val id = store.newId()
        try {
            if (post.needsResolve) {
                post = BooruApiFactory.create(task.source, client, json).resolve(post)
                val resolved = post
                set(key) { it.copy(post = resolved) }
            }
            val original = policy.downloadOriginal
            val url = (if (original) post.bestUrl else post.viewUrl(false)) ?: throw ProblemException(Problem.Code.NO_FILE)
            val urlExt = url.extensionFromUrl()?.takeIf { Mime.fromExtension(it) != "application/octet-stream" }
            var contentType: String? = null
            val size = client.newCall(request(url, task.source)).await().use { response ->
                if (response.header("cf-mitigated").equals("challenge", ignoreCase = true)) {
                    throw ProblemException(Problem.Code.CHALLENGE)
                }
                if (!response.isSuccessful) throw ProblemException(Problem.Code.HTTP_STATUS, response.code.toString())
                val body = response.body
                contentType = body.contentType()?.let { "${it.type}/${it.subtype}" }
                val total = body.contentLength()
                set(key) { it.copy(total = total) }
                store.writeMedia(id) { out ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        var done = 0L
                        var reported = 0L
                        try {
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val n = input.read(buffer)
                                if (n < 0) break
                                out.write(buffer, 0, n)
                                done += n
                                if (done - reported >= 256 * 1024) {
                                    reported = done
                                    set(key) { it.copy(bytes = done) }
                                }
                            }
                        } finally {
                            buffer.fill(0)
                        }
                    }
                }
            }
            val hasThumb = post.previewUrl?.let { preview ->
                runCatching {
                    client.newCall(request(preview, task.source)).await().use { r ->
                        if (r.isSuccessful) r.body.bytes().takeIf { it.size in 1..(4 shl 20) } else null
                    }
                }.getOrNull()?.let { bytes ->
                    try {
                        store.writeThumbnail(id, bytes)
                    } finally {
                        bytes.fill(0)
                    }
                    true
                }
            } ?: false
            // A URL without an extension (common on plain web pages) is named by what the server sent.
            val served = contentType?.takeIf { it.startsWith("image/") || it.startsWith("video/") }
            val ext = urlExt ?: served?.let(Mime::extensionFor) ?: post.fileExt
            val mime = (if (urlExt == null) served else null)
                ?: Mime.fromExtension(ext).takeIf { it != "application/octet-stream" }
                ?: served
                ?: "application/octet-stream"
            store.commit(
                VaultItem(
                    id = id,
                    addedAt = System.currentTimeMillis(),
                    fileName = safeFileName("${task.source.name}_${post.id}.$ext"),
                    mimeType = mime,
                    size = size,
                    width = post.width,
                    height = post.height,
                    hasThumbnail = hasThumb,
                    sourceId = task.source.id,
                    sourceName = task.source.name,
                    postId = post.id,
                    postUrl = post.postUrl,
                    originalUrl = url,
                    md5 = post.md5,
                    rating = post.rating,
                    tags = post.tagNames,
                ),
            )
            set(key) { it.copy(status = DownloadStatus.DONE, bytes = size, total = size) }
        } catch (e: CancellationException) {
            store.discard(id)
            set(key) { it.copy(status = DownloadStatus.CANCELLED) }
            throw e
        } catch (e: Exception) {
            store.discard(id)
            val problem = e.toProblem()
            set(key) { it.copy(status = DownloadStatus.FAILED, problem = problem) }
        }
    }

    private fun request(url: String, source: BooruSource): Request = Request.Builder().url(url)
        .header("User-Agent", USER_AGENT)
        // Some image hosts refuse hotlinks without a referer from their own site.
        .header("Referer", source.baseUrl.trimEnd('/') + "/")
        .build()

    private fun startService() {
        runCatching { ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java)) }
    }
}
