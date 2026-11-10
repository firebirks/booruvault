package io.github.firebirks.booruvault.download

import io.github.firebirks.booruvault.booru.BooruApi
import io.github.firebirks.booruvault.booru.BooruSource
import io.github.firebirks.booruvault.booru.Post
import io.github.firebirks.booruvault.booru.UserAgents
import io.github.firebirks.booruvault.booru.bytesUpTo
import io.github.firebirks.booruvault.booru.extensionFromUrl
import io.github.firebirks.booruvault.booru.withResponse
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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
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
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
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

    /** Failed or cancelled: listed with what it was, to be retried, or removed with the failed. */
    val retriable: Boolean get() = status == DownloadStatus.FAILED || status == DownloadStatus.CANCELLED
    val progress: Float? get() = if (total > 0) (bytes.toFloat() / total).coerceIn(0f, 1f) else null
}

/**
 * A search whose results are being gathered page by page to be downloaded. A [problem] stopped
 * it, or the user did ([stopped]): it stays listed, with what it found, until retried or cleared.
 */
data class BatchJob(
    val key: Long,
    val source: BooruSource,
    val tags: String,
    val target: Int,
    val found: Int = 0,
    val nextPage: Int = 1,
    val problem: Problem? = null,
    val stopped: Boolean = false,
) {
    val active: Boolean get() = problem == null && !stopped

    /** Stopped by a problem or the user: listed with what it found, to be retried, or removed with the failed. */
    val retriable: Boolean get() = !active
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
    val stopped: Boolean = false,
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
    private val timeoutRetryPauseMs: Long = 3_000,
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

    /** What is saved: the work to do, and what failed or was cancelled, which can be retried; what is done is not. */
    private fun snapshot(): SavedQueue = SavedQueue(
        tasks = _tasks.value.filter { it.active || it.status == DownloadStatus.FAILED || it.status == DownloadStatus.CANCELLED }.map {
            SavedTask(it.post, it.source, if (it.active) DownloadStatus.QUEUED else it.status, it.problem)
        },
        batches = _batches.value.map { SavedBatch(it.source, it.tags, it.target, it.found, it.nextPage, it.problem, it.stopped) },
    )

    /**
     * Seals the queue as it stands, and returns it as sealed. A save made [onlyWhileLive] takes
     * its copy under the write lock and only if the queue is still the vault's: a lock that
     * came meanwhile has sealed the full queue and emptied it, and a copy taken after that
     * would seal the emptiness over it.
     */
    private fun persist(onlyWhileLive: Boolean = false): SavedQueue {
        var queue: SavedQueue? = null
        val recipient = vault.recipient
        if (recipient != null) runCatching { saved.write(recipient) { if (onlyWhileLive && !live) null else snapshot().also { queue = it } } }
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
        _paused.value = queue.tasks.count { it.status == DownloadStatus.QUEUED } + queue.batches.count { it.problem == null && !it.stopped }
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
        val stoppedTasks = queue.tasks.filter { it.status == DownloadStatus.FAILED || it.status == DownloadStatus.CANCELLED }
            .map { DownloadTask(keys.incrementAndGet(), it.post, it.source, it.status, problem = it.problem) }
        _tasks.update { it + stoppedTasks }
        queue.tasks.filter { it.status == DownloadStatus.QUEUED }.groupBy { it.source }.forEach { (source, list) ->
            enqueue(list.map { it.post }, source)
        }
        val (stopped, pending) = queue.batches.partition { it.problem != null || it.stopped }
        _batches.update { list ->
            list + stopped.map { BatchJob(keys.incrementAndGet(), it.source, it.tags, it.target, it.found, it.nextPage, it.problem, it.stopped) }
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
                    VaultState.Missing, VaultState.Damaged -> {
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
            combine(_tasks, _batches) { tasks, batches -> tasks.map { it.key to it.status } to batches.map { listOf(it.key, it.nextPage, it.problem, it.stopped) } }
                .distinctUntilChanged()
                .collect { if (live) saveRequests.trySend(Unit) }
        }
        scope.launch(Dispatchers.IO) {
            for (request in saveRequests) {
                if (live) persist(onlyWhileLive = true)
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
     * A page that fails stops the batch, which stays listed with the reason; so does the user,
     * and the batch stays listed as stopped, to be taken up again from the same page.
     */
    private fun collect(batch: BatchJob) {
        startService()
        var problem: Problem? = null
        val job = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            try {
                val api = api(batch.source)
                val limit = 100
                var page = batch.nextPage
                var found = batch.found
                var barren = 0
                // Uploads while it runs push newest-first pages along, so a page may repeat posts.
                val seen = HashSet<Long>()
                // Filters that let nothing through would otherwise have every page read.
                while (found < batch.target && barren < MAX_BARREN_PAGES) {
                    val posts = retryingTimeouts { api.posts(batch.tags, page, limit) }
                    if (posts.isEmpty()) break
                    val allowed = posts.filter { seen.add(it.id) && policy.allows(it) }.take(batch.target - found)
                    barren = if (allowed.isEmpty()) barren + 1 else 0
                    found += allowed.size
                    // A cancellation that came while the page was being read must not queue it.
                    ensureActive()
                    enqueue(allowed, batch.source)
                    page++
                    _batches.update { list -> list.map { if (it.key == batch.key) it.copy(found = found, nextPage = page) else it } }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Whatever was queued before the failure still downloads.
                problem = e.toProblem()
            }
        }
        // On completion rather than in a finally: a job cancelled before it was ever dispatched
        // runs no finally, and the batch would stay listed as under way with nothing under it.
        job.invokeOnCompletion { cause ->
            jobs.remove(batch.key, job)
            val stopped = problem
            _batches.update { list ->
                when {
                    stopped != null -> list.map { if (it.key == batch.key) it.copy(problem = stopped) else it }
                    cause != null -> list.map { if (it.key == batch.key) it.copy(stopped = true) else it }
                    else -> list.filterNot { it.key == batch.key }
                }
            }
        }
        jobs[batch.key] = job
        job.start()
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
            list.map { if (it.key == key && !it.active) it.copy(problem = null, stopped = false).also { again -> resumed = again } else it }
        }
        resumed?.let(::collect)
    }

    fun cancel(key: Long) {
        jobs[key]?.cancel()
        set(key) { if (it.active) it.copy(status = DownloadStatus.CANCELLED, problem = null) else it }
    }

    /** Stops everything under way; the entries stay listed, as cancelled or stopped, to be retried. */
    fun cancelAll() {
        _batches.value.forEach { cancelBatch(it.key) }
        _tasks.value.filter { it.active }.forEach { cancel(it.key) }
    }

    fun retry(key: Long) {
        val task = _tasks.value.firstOrNull { it.key == key } ?: return
        if (task.status != DownloadStatus.FAILED && task.status != DownloadStatus.CANCELLED) return
        // A job still listed is a retry already under way, whatever status an older one left.
        if (jobs[key]?.isActive == true) return
        if (!live) return
        set(key) { it.copy(status = DownloadStatus.QUEUED, bytes = 0, total = -1, problem = null) }
        launch(task)
        startService()
    }

    /** Queues again everything that failed or was cancelled. */
    fun retryFailed() {
        _tasks.value.filter { it.status == DownloadStatus.FAILED || it.status == DownloadStatus.CANCELLED }.forEach { retry(it.key) }
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

    /** Takes off the list what is done or skipped; what failed or was cancelled stays, to be retried. */
    fun clearFinished() {
        _tasks.update { list -> list.filter { it.active || it.retriable } }
        _batches.update { list -> list.filter { it.active || it.retriable } }
    }

    /** Takes off the list what failed or was cancelled, batches a problem or the user stopped included. */
    fun clearFailed() {
        _tasks.update { list -> list.filter { !it.retriable } }
        _batches.update { list -> list.filter { !it.retriable } }
    }

    /** Takes one download off the list, unless it is under way: done, skipped, failed or cancelled. */
    fun remove(key: Long) {
        _tasks.update { list -> list.filterNot { it.key == key && !it.active } }
    }

    /** Takes one collection off the list, unless it is under way: stopped by a problem or by the user. */
    fun removeBatch(key: Long) {
        _batches.update { list -> list.filterNot { it.key == key && !it.active } }
    }

    /**
     * Runs [block] again, after a pause, while it fails on a timeout, up to [TIMEOUT_RETRIES] more
     * times. Over Tor a timeout is most often a slow circuit, and the next try may take another.
     */
    private suspend fun <T> retryingTimeouts(block: suspend () -> T): T {
        var tries = 0
        while (true) {
            try {
                return block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (tries++ >= TIMEOUT_RETRIES || e.toProblem().code != Problem.Code.TIMEOUT) throw e
                delay(timeoutRetryPauseMs)
            }
        }
    }

    private fun set(key: Long, transform: (DownloadTask) -> DownloadTask) {
        _tasks.update { list -> list.map { if (it.key == key) transform(it) else it } }
    }

    private fun launch(task: DownloadTask) {
        // Listed before it starts, and taken off the list only if still the one listed: a retry
        // after a cancellation puts a newer job under the same key while the old one unwinds.
        // On completion rather than in a finally, so a job cancelled before it ran is taken off too.
        val job = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) { parallel.withPermit { run(task.key) } }
        job.invokeOnCompletion { jobs.remove(task.key, job) }
        jobs[task.key] = job
        job.start()
    }

    /**
     * [retries]: the tries a failure may still earn this file before it counts as failed.
     * [resume]: whether a transfer cut short may ask for the rest; false once the file's host
     * has answered such a request with something else.
     */
    private suspend fun run(key: Long, retries: Int = TIMEOUT_RETRIES, resume: Boolean = true) {
        val task = _tasks.value.firstOrNull { it.key == key } ?: return
        if (task.status != DownloadStatus.QUEUED) return
        var post = task.post
        if (store.containsPost(task.source.id, post.id) || post.md5?.let(store::containsMd5) == true) {
            set(key) { it.copy(status = DownloadStatus.SKIPPED, problem = Problem(Problem.Code.ALREADY_IN_VAULT)) }
            return
        }
        set(key) { it.copy(status = DownloadStatus.RUNNING) }
        val id = store.newId()
        var left = retries
        var resumable = resume
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
            // Tied to this job for the whole transfer: a cancellation closes the socket, so a read
            // on a server gone quiet does not sit out the timeout with one of the three places.
            val size = client.newCall(downloadRequest(url, task.source)).withResponse { response ->
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
                val validator = if (resumable) resumeValidator(response) else null
                store.writeMedia(id) { out ->
                    var done = 0L
                    var reported = 0L
                    val count: (Int) -> Unit = { n ->
                        done += n
                        if (done - reported >= 256 * 1024) {
                            reported = done
                            val bytes = done
                            set(key) { it.copy(bytes = bytes) }
                        }
                    }
                    var cut = receive(body.byteStream(), out, count)
                    // Cut short by the network, with part of the file already sealed: the rest is
                    // asked for and sealed after it, rather than the whole file once more.
                    var gained = done
                    while (cut != null) {
                        val failure: IOException = cut
                        currentCoroutineContext().ensureActive()
                        if (validator == null || done == 0L || failure.toProblem().code !in RESUMED) throw failure
                        // What was read is kept, so the tries start over; they run out only on
                        // attempts in a row that bring nothing.
                        if (gained > 0) left = TIMEOUT_RETRIES
                        if (left == 0) throw failure
                        left--
                        delay(timeoutRetryPauseMs)
                        val from = done
                        cut = try {
                            client.newCall(resumeRequest(url, task.source, from, validator)).withResponse { rest ->
                                if (rest.header("cf-mitigated").equals("challenge", ignoreCase = true)) {
                                    throw ProblemException(Problem.Code.CHALLENGE, url)
                                }
                                if (!resumesAt(rest, from, total)) {
                                    // The host does not hand out parts, or the file is no longer
                                    // the one begun: from its first byte, or not at all. Asking
                                    // was no try at the file, and does not count as one.
                                    resumable = false
                                    left++
                                    throw failure
                                }
                                receive(rest.body.byteStream(), out, count)
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: IOException) {
                            // Refused, or stopped by something a further try will not mend: that
                            // ends it. Anything else cut this request before it brought a byte.
                            if (!resumable || e.toProblem().code !in RESUMED) throw e
                            e
                        }
                        gained = done - from
                    }
                }
            }
            val hasThumb = post.previewUrl?.let { preview ->
                val fetched = try {
                    client.newCall(downloadRequest(preview, task.source)).withResponse { r ->
                        if (r.isSuccessful) r.body.bytesUpTo(MAX_THUMBNAIL)?.takeIf { it.isNotEmpty() } else null
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // A preview that cannot be had is no reason to drop the file; a cancellation is.
                    currentCoroutineContext().ensureActive()
                    null
                }
                fetched?.let { bytes ->
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
        } catch (e: Exception) {
            store.discard(id)
            if (e is CancellationException || !currentCoroutineContext().isActive) {
                // Cancelled: the call was closed under the transfer, which then failed as a read
                // would. That is no failure to report, and the job ends as a cancelled one does.
                // The status is written only by the job still listed for the key: an older one,
                // unwinding after a retry replaced it, must not mark the retry cancelled.
                if (jobs[key] === currentCoroutineContext().job) set(key) { it.copy(status = DownloadStatus.CANCELLED) }
                throw e as? CancellationException ?: CancellationException("download cancelled", e)
            }
            val met = e.toProblem()
            if (met.code == Problem.Code.TIMEOUT && left > 0) {
                // Over Tor most often a slow circuit: the next try may take another. The place
                // among the parallel transfers is kept through the pause.
                set(key) { it.copy(status = DownloadStatus.QUEUED, bytes = 0, total = -1) }
                delay(timeoutRetryPauseMs)
                return run(key, left - 1, resumable)
            }
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

        /**
         * Tries after the first that a timeout earns a file or a page, before it counts as
         * failed. A file taken up where it was cut gets them anew each time it brings something.
         */
        const val TIMEOUT_RETRIES = 2

        /** What cuts a transfer short without saying anything about the file: the rest is worth asking for. */
        val RESUMED = setOf(Problem.Code.TIMEOUT, Problem.Code.NETWORK)
    }

    /**
     * Copies what [input] gives into [out], telling [count] of each piece. Returns null at the end
     * of the input, or the failure that cut the reading short; a failure of [out] is thrown.
     */
    private suspend fun receive(input: InputStream, out: OutputStream, count: (Int) -> Unit): IOException? {
        val buffer = ByteArray(64 * 1024)
        try {
            input.use {
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = try {
                        it.read(buffer)
                    } catch (e: IOException) {
                        return e
                    }
                    if (n < 0) return null
                    out.write(buffer, 0, n)
                    count(n)
                }
            }
        } finally {
            buffer.fill(0)
        }
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

/**
 * What a later request can name to be given the rest of the file [response] began, and nothing
 * else: a strong ETag, or the date the file was last changed. Null when the rest cannot be asked
 * for: the host hands out no parts, names nothing to hold the file to, or sent it without a
 * length, as one unpacked on the way arrives, whose bytes are not the ones the host counts.
 */
internal fun resumeValidator(response: Response): String? {
    if (response.code != 200 || response.body.contentLength() <= 0) return null
    if (response.header("Accept-Ranges").equals("none", ignoreCase = true)) return null
    return response.header("ETag")?.takeIf { it.isNotBlank() && !it.startsWith("W/") }
        ?: response.header("Last-Modified")?.takeIf { it.isNotBlank() }
}

/** [downloadRequest] for the bytes from [from] on, of the file [validator] names and of no other. */
internal fun resumeRequest(url: String, source: BooruSource, from: Long, validator: String): Request =
    downloadRequest(url, source).newBuilder()
        .header("Range", "bytes=$from-")
        .header("If-Range", validator)
        .build()

/** Whether [response] is the part asked for: the bytes from [from] on, of a file [total] bytes long. */
internal fun resumesAt(response: Response, from: Long, total: Long): Boolean {
    if (response.code != 206) return false
    val range = CONTENT_RANGE.matchEntire(response.header("Content-Range")?.trim() ?: return false) ?: return false
    val (start, end, length) = range.destructured
    return start.toLongOrNull() == from && end.toLongOrNull() == total - 1 && (length == "*" || length.toLongOrNull() == total)
}

private val CONTENT_RANGE = Regex("""bytes\s+(\d+)-(\d+)/(\d+|\*)""", RegexOption.IGNORE_CASE)

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
