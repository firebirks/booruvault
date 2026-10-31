package io.github.firebirks.booruvault.download

import io.github.firebirks.booruvault.booru.BooruApi
import io.github.firebirks.booruvault.booru.BooruSource
import io.github.firebirks.booruvault.booru.Post
import io.github.firebirks.booruvault.booru.UserAgents
import io.github.firebirks.booruvault.booru.await
import io.github.firebirks.booruvault.booru.bytesUpTo
import io.github.firebirks.booruvault.booru.extensionFromUrl
import io.github.firebirks.booruvault.settings.AppSettings
import io.github.firebirks.booruvault.settings.SettingsRepository
import io.github.firebirks.booruvault.settings.allows
import io.github.firebirks.booruvault.util.Mime
import io.github.firebirks.booruvault.util.Problem
import io.github.firebirks.booruvault.util.ProblemException
import io.github.firebirks.booruvault.util.toProblem
import io.github.firebirks.booruvault.util.safeFileName
import io.github.firebirks.booruvault.vault.MediaTooLargeException
import io.github.firebirks.booruvault.vault.SealedJson
import io.github.firebirks.booruvault.vault.VaultItem
import io.github.firebirks.booruvault.vault.VaultManager
import io.github.firebirks.booruvault.vault.VaultMissingException
import io.github.firebirks.booruvault.vault.VaultState
import io.github.firebirks.booruvault.vault.VaultStore
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
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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

/**
 * A search whose results are being gathered page by page to be downloaded. A [problem] stopped it:
 * it stays listed, with what it found, until retried or cleared.
 */
data class BatchJob(
    val key: Long,
    val source: BooruSource,
    val tags: String,
    val target: Int,
    val found: Int = 0,
    val nextPage: Int = 1,
    val problem: Problem? = null,
) {
    val active: Boolean get() = problem == null
}

/** What survives a lock or a restart: sealed in the vault as `downloads.bin`. */
@Serializable
private data class SavedQueue(val tasks: List<SavedTask> = emptyList(), val batches: List<SavedBatch> = emptyList())

@Serializable
private data class SavedTask(val post: Post, val source: BooruSource, val status: DownloadStatus, val problem: Problem? = null)

@Serializable
private data class SavedBatch(
    val source: BooruSource,
    val tags: String,
    val target: Int,
    val found: Int,
    val nextPage: Int,
    val problem: Problem? = null,
)

/**
 * Downloads posts straight into the vault: the response body is sealed as it streams, so the
 * plaintext never touches storage.
 *
 * The queue itself names posts, tags and sites, so it lives in memory only while the vault is
 * unlocked. Locking seals it into the vault, stops every transfer and forgets it; the next unlock
 * picks it up again.
 */
class DownloadManager(
    /** Starts [DownloadService], which keeps the process alive while there is work. */
    private val wakeService: () -> Unit,
    private val api: (BooruSource) -> BooruApi,
    private val client: OkHttpClient,
    private val torEnabled: () -> Boolean,
    /** Whether a host's site refused, since a time, a clearance that had worked: see [downloadProblem]. */
    private val passExpiredSince: (host: String, since: Long) -> Boolean,
    private val store: VaultStore,
    private val vault: VaultManager,
    private val settings: SettingsRepository,
    json: Json,
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

    private fun hasWork(): Boolean = _tasks.value.any { it.active } || _batches.value.any { it.active }
    private val saveRequests = Channel<Unit>(Channel.CONFLATED)

    private val _paused = MutableStateFlow(0)

    /** Downloads waiting in the vault for the next unlock. */
    val paused: StateFlow<Int> = _paused.asStateFlow()

    private fun snapshot(): SavedQueue = SavedQueue(
        tasks = _tasks.value.filter { it.active || it.status == DownloadStatus.FAILED }.map {
            SavedTask(it.post, it.source, if (it.active) DownloadStatus.QUEUED else it.status, it.problem)
        },
        batches = _batches.value.map { SavedBatch(it.source, it.tags, it.target, it.found, it.nextPage, it.problem) },
    )

    /** Seals the queue as it stands, and returns it as sealed. */
    private fun persist(): SavedQueue {
        var queue: SavedQueue? = null
        val recipient = vault.recipient
        if (recipient != null) runCatching { saved.write(recipient) { snapshot().also { queue = it } } }
        return queue ?: snapshot()
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
        val queue = persist()
        stopAll()
        // Keep only the switch: everything else from the settings goes with the lock.
        policy = AppSettings(downloadWhileLocked = policy.downloadWhileLocked)
        _paused.value = queue.tasks.count { it.status == DownloadStatus.QUEUED } + queue.batches.count { it.problem == null }
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
        checkedAt.clear()
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
        val (stopped, pending) = queue.batches.partition { it.problem != null }
        _batches.update { list ->
            list + stopped.map { BatchJob(keys.incrementAndGet(), it.source, it.tags, it.target, it.found, it.nextPage, it.problem) }
        }
        pending.forEach { downloadSearch(it.source, it.tags, it.target, it.found, it.nextPage) }
    }
    private val _tasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    val tasks: StateFlow<List<DownloadTask>> = _tasks.asStateFlow()

    private val _batches = MutableStateFlow<List<BatchJob>>(emptyList())
    val batches: StateFlow<List<BatchJob>> = _batches.asStateFlow()

    private val jobs = ConcurrentHashMap<Long, Job>()

    /** Source id → when the user last came back from the built-in browser after one of its checks. */
    private val checkedAt = ConcurrentHashMap<String, Long>()
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
            combine(_tasks, _batches) { tasks, batches -> tasks.any { it.active } || batches.any { it.active } }
                .distinctUntilChanged()
                .collect { working -> if (!working) drainedWhileLocked() }
        }
        scope.launch(Dispatchers.IO) {
            // Only status changes matter for what is saved, not byte counts.
            combine(_tasks, _batches) { tasks, batches -> tasks.map { it.key to it.status } to batches.map { Triple(it.key, it.nextPage, it.problem) } }
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
        collect(batch)
    }

    /**
     * Reads the search's pages from [BatchJob.nextPage] on. Danbooru drops the posts it does not
     * show after paging, so a page shorter than asked for is not the last: only an empty one is.
     * A page that fails stops the batch, which stays listed with the reason.
     */
    private fun collect(batch: BatchJob) {
        startService()
        jobs[batch.key] = scope.launch(Dispatchers.IO) {
            var problem: Problem? = null
            try {
                val api = api(batch.source)
                val limit = 100
                var page = batch.nextPage
                var found = batch.found
                var barren = 0
                // Filters that let nothing through would otherwise have every page read.
                while (found < batch.target && barren < MAX_BARREN_PAGES) {
                    val posts = api.posts(batch.tags, page, limit)
                    if (posts.isEmpty()) break
                    val allowed = posts.filter { policy.allows(it) }.take(batch.target - found)
                    barren = if (allowed.isEmpty()) barren + 1 else 0
                    found += allowed.size
                    enqueue(allowed, batch.source)
                    page++
                    _batches.update { list -> list.map { if (it.key == batch.key) it.copy(found = found, nextPage = page) else it } }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Whatever was queued before the failure still downloads.
                problem = e.toProblem()
            } finally {
                jobs.remove(batch.key)
                val stopped = problem
                _batches.update { list ->
                    if (stopped == null) list.filterNot { it.key == batch.key } else list.map { if (it.key == batch.key) it.copy(problem = stopped) else it }
                }
            }
        }
    }

    fun cancelBatch(key: Long) {
        jobs[key]?.cancel()
    }

    /** Takes up a stopped batch again from the page it could not read. */
    fun retryBatch(key: Long) {
        if (!live) return
        var resumed: BatchJob? = null
        _batches.update { list ->
            resumed = null
            list.map { if (it.key == key && !it.active) it.copy(problem = null).also { again -> resumed = again } else it }
        }
        resumed?.let(::collect)
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

    /**
     * Queues again the downloads of [sourceId] an anti-bot check stopped, once the user is back
     * from the built-in browser. For a while after, any of the source's downloads that meets the
     * check again, however it was retried, says that another round in the browser will not help.
     */
    fun retryAfterCheck(sourceId: String) {
        checkedAt[sourceId] = System.currentTimeMillis()
        challengedKeys(_tasks.value, sourceId).forEach(::retry)
    }

    /** A check passed along the other route says nothing about this one: it may be offered again. */
    fun routeChanged() = checkedAt.clear()

    fun clearFinished() {
        _tasks.update { list -> list.filter { it.active } }
        _batches.update { list -> list.filter { it.active } }
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
                post = api(task.source).resolve(post)
                val resolved = post
                set(key) { it.copy(post = resolved) }
            }
            val original = policy.downloadOriginal
            val url = (if (original) post.bestUrl else post.viewUrl(false)) ?: throw ProblemException(Problem.Code.NO_FILE)
            val urlExt = url.extensionFromUrl()?.takeIf { Mime.fromExtension(it) != "application/octet-stream" }
            var contentType: String? = null
            val size = client.newCall(downloadRequest(url, task.source)).await().use { response ->
                // The file host may guard the file itself rather than the site: the check is passed at this address.
                if (response.header("cf-mitigated").equals("challenge", ignoreCase = true)) {
                    throw ProblemException(Problem.Code.CHALLENGE, url)
                }
                if (!response.isSuccessful) throw ProblemException(Problem.Code.HTTP_STATUS, response.code.toString())
                val body = response.body
                contentType = body.contentType()?.let { "${it.type}/${it.subtype}" }
                val total = body.contentLength()
                if (total > VaultStore.MAX_MEDIA_BYTES) throw MediaTooLargeException()
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
                    client.newCall(downloadRequest(preview, task.source)).await().use { r ->
                        if (r.isSuccessful) r.body.bytesUpTo(MAX_THUMBNAIL)?.takeIf { it.isNotEmpty() } else null
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
            val met = e.toProblem()
            val checked = checkedAt[task.source.id]
            // A file host's check names the file; one met on the site's own pages names nothing.
            val guarded = (met.detail ?: task.source.baseUrl).toHttpUrlOrNull()?.host
            val expired = checked != null && guarded != null && passExpiredSince(guarded, checked)
            val problem = downloadProblem(met, checked, expired, System.currentTimeMillis(), torEnabled())
            set(key) { it.copy(status = DownloadStatus.FAILED, problem = problem) }
        }
    }

    private companion object {
        /** A preview is a small JPEG; anything larger is not worth keeping as one. */
        const val MAX_THUMBNAIL = 4L shl 20

        /** Pages in a row that add nothing the filters allow, after which a batch stops looking. */
        const val MAX_BARREN_PAGES = 10
    }

    private fun startService() {
        runCatching(wakeService)
    }
}

/**
 * A file or its preview, asked for as the grid asks for images: the client adds the Referer, the
 * file host's own origin, which is what hosts that refuse hotlinks want.
 */
internal fun downloadRequest(url: String, source: BooruSource): Request = Request.Builder().url(url)
    .header("User-Agent", UserAgents.forSource(source))
    .build()

/** The downloads of [sourceId] an anti-bot check stopped. */
internal fun challengedKeys(tasks: List<DownloadTask>, sourceId: String): List<Long> = tasks
    .filter { it.status == DownloadStatus.FAILED && it.source.id == sourceId && it.problem?.code == Problem.Code.CHALLENGE }
    .map { it.key }

/**
 * What a failed download reports. A check met again within [CHECK_HOLDS_MS] of the user passing
 * the source's check in the browser ([checkedAt]) is not offered once more, whether the download
 * was queued again on the way back or retried by hand: the site does not take the pass from the
 * app, and another round in the browser would end the same way. With Tor on, the likely reason is
 * that the browser and the app reach the site from different exits. Unless the pass worked and then
 * stopped ([passExpired]): it ran out or the address changed, and a new one will work again.
 */
internal fun downloadProblem(problem: Problem, checkedAt: Long?, passExpired: Boolean, now: Long, tor: Boolean): Problem = when {
    problem.code != Problem.Code.CHALLENGE || checkedAt == null || now - checkedAt > CHECK_HOLDS_MS -> problem
    passExpired -> problem
    tor -> Problem(Problem.Code.CHALLENGE_PASS_REFUSED_TOR)
    else -> Problem(Problem.Code.CHALLENGE_PASS_REFUSED)
}

/** How long a check passed in the browser counts: Cloudflare's own clearance lasts about as long by default. */
internal const val CHECK_HOLDS_MS = 30 * 60 * 1000L
