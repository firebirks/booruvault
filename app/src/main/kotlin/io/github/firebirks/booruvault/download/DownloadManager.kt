package io.github.firebirks.booruvault.download

import io.github.firebirks.booruvault.booru.BooruApi
import io.github.firebirks.booruvault.booru.BooruSource
import io.github.firebirks.booruvault.booru.Post
import io.github.firebirks.booruvault.booru.UserAgents
import io.github.firebirks.booruvault.booru.extensionFromUrl
import io.github.firebirks.booruvault.booru.withResponse
import io.github.firebirks.booruvault.settings.AppSettings
import io.github.firebirks.booruvault.settings.SettingsRepository
import io.github.firebirks.booruvault.settings.allows
import io.github.firebirks.booruvault.util.Mime
import io.github.firebirks.booruvault.util.HasProblem
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.getAndUpdate
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
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
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
    /** Names the row's preview among the [QueuePreviews]; the row keeps it for as long as it is listed. */
    val previewId: String = UUID.randomUUID().toString(),
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
private data class SavedTask(
    val post: Post,
    val source: BooruSource,
    val status: DownloadStatus,
    val problem: Problem? = null,
    /** Null in a queue saved before previews were kept. */
    val previewId: String? = null,
)

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
 * plaintext never touches storage. Three run at a time, in the order they were queued; one cut
 * short asks its host for the rest and seals it after what it has, when the host can be held
 * to the same file.
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
    /** Waits for Tor's exit check to speak, for a download it held back: true if the download may go out now. */
    private val awaitTorVerdict: suspend () -> Boolean = { false },
) {
    private val saved = SealedJson(File(root, "downloads.bin"), SavedQueue.serializer(), json)

    /** The previews of the rows listed, kept sealed so the list need not ask the sites for them again. */
    val previews = QueuePreviews(root, vault, client)

    /** Whether the queue in memory is the vault's; false while locked or before it is read back. */
    @Volatile
    private var live = false

    /**
     * Held while [live] is looked at and work is added on the strength of it, and while a lock
     * empties the queue: a download queued on another thread between the two would otherwise land
     * in the emptied list and go out behind a locked vault the user did not allow it to run behind.
     */
    private val liveLock = Any()

    /**
     * What downloads need from the settings, copied while unlocked: once the vault locks the
     * settings themselves are gone, and downloads allowed to go on still need these.
     */
    @Volatile
    private var policy = AppSettings()

    /**
     * Whether [policy] is the vault's own settings rather than the defaults it is reset to. At
     * an unlock the queue comes back before the settings are read, and its downloads wait for
     * them: with the defaults they would be filtered, and fetched, by what the user did not choose.
     */
    private val policyReady = MutableStateFlow(false)

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
            SavedTask(it.post, it.source, if (it.active) DownloadStatus.QUEUED else it.status, it.problem, it.previewId)
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
        vault.sealing { recipient -> runCatching { saved.write(recipient) { if (onlyWhileLive && !live) null else snapshot().also { queue = it } } } }
        return queue ?: snapshot()
    }

    private fun suspendForLock() {
        synchronized(liveLock) {
            if (!live) return
            if (policy.downloadWhileLocked && hasWork()) {
                // Allowed to go on: the queue stays in memory until it drains, then it is dropped.
                runningLocked = true
                persist()
            } else {
                live = false
                val queue = persist()
                stopAll(queue)
                // Keep only the switch: everything else from the settings goes with the lock.
                policyReady.value = false
                policy = AppSettings(downloadWhileLocked = policy.downloadWhileLocked)
                _paused.value = queue.tasks.count { it.status == DownloadStatus.QUEUED } + queue.batches.count { it.problem == null && !it.stopped }
            }
        }
        // What was left to run may have finished while the lock was being decided, its last
        // word on draining lost before there was anything to drain: looked at again.
        if (runningLocked) drainedWhileLocked() else onLockedWorkDone()
    }

    /** The last download behind a locked vault finished: seal what is left and forget it. */
    private fun drainedWhileLocked() {
        synchronized(liveLock) {
            if (!runningLocked || hasWork() || vault.state.value is VaultState.Unlocked) return
            runningLocked = false
            live = false
            stopAll(persist())
            policyReady.value = false
            policy = AppSettings(downloadWhileLocked = policy.downloadWhileLocked)
            _paused.value = 0
        }
        onLockedWorkDone()
    }

    /** Forgets the queue. The previews go with it, but for those of the rows [kept] sealed for the next unlock. */
    private fun stopAll(kept: SavedQueue? = null) {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        checkedAt.clear()
        val keep = kept?.tasks?.mapNotNullTo(HashSet()) { it.previewId }.orEmpty()
        dropPreviews(_tasks.value.filter { it.previewId !in keep })
        _tasks.value = emptyList()
        _batches.value = emptyList()
    }

    private fun dropPreviews(gone: List<DownloadTask>) {
        if (gone.isEmpty()) return
        val ids = gone.map { it.previewId }
        scope.launch(Dispatchers.IO) { previews.delete(ids) }
    }

    /** Takes off the list the downloads [gone] is true of, and their previews off the storage. */
    private fun takeOff(gone: (DownloadTask) -> Boolean) {
        val before = _tasks.getAndUpdate { list -> list.filterNot(gone) }
        dropPreviews(before.filter(gone))
    }

    /** The id a saved row kept its preview under; a new one for a row saved without, or with one that names no file of ours. */
    private fun previewIdOf(saved: SavedTask): String = saved.previewId?.takeIf(VaultStore.ID::matches) ?: UUID.randomUUID().toString()

    private fun restore(state: VaultState.Unlocked) {
        synchronized(liveLock) {
            runningLocked = false
            // Still running from before the lock: the queue in memory is current.
            if (live) return
        }
        val queue = saved.read(state.keys) ?: SavedQueue()
        synchronized(liveLock) { live = true }
        _paused.value = 0
        val stoppedTasks = queue.tasks.filter { it.status == DownloadStatus.FAILED || it.status == DownloadStatus.CANCELLED }
            .map { DownloadTask(keys.incrementAndGet(), it.post, it.source, it.status, problem = it.problem, previewId = previewIdOf(it)) }
        _tasks.update { it + stoppedTasks }
        // In the order they were queued, one run of a source's rows at a time: grouped by source,
        // the rows of one queued after those of another would start before them.
        runsOf(queue.tasks.filter { it.status == DownloadStatus.QUEUED }) { it.source }.forEach { run ->
            enqueue(run.map { it.post }, run.first().source, run.associate { it.post.id to previewIdOf(it) })
        }
        val (stopped, pending) = queue.batches.partition { it.problem != null || it.stopped }
        _batches.update { list ->
            list + stopped.map { BatchJob(keys.incrementAndGet(), it.source, it.tags, it.target, it.found, it.nextPage, it.problem, it.stopped) }
        }
        pending.forEach { downloadSearch(it.source, it.tags, it.target, it.found, it.nextPage) }
        // Previews whose rows are gone: of downloads done, which are not saved, when the process
        // ended with them listed. The rows are read before the word that they are the vault's
        // still: a lock that came meanwhile has emptied them, and calls the sweep off.
        scope.launch(Dispatchers.IO) {
            previews.sweep { _tasks.value.mapTo(HashSet()) { it.previewId }.takeIf { live } }
        }
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

    /**
     * Where a download comes to wait for one of the places: one at a time, so they reach the
     * wait, which hands the places out first come first served, in the order they were queued.
     * Left to as many threads as there are downloads, they would race to it, and a post queued
     * later could start before one queued earlier.
     */
    private val turn = Dispatchers.IO.limitedParallelism(1)

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
                .collect { current ->
                    // A lock resets the settings to the defaults before it drops ready, and the
                    // two may be seen together for a moment: those defaults are no one's choice.
                    if (current != null && vault.state.value is VaultState.Unlocked) {
                        policy = current.copy(sources = emptyList(), selectedSourceId = null)
                        policyReady.value = true
                    }
                }
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
    fun enqueue(posts: List<Post>, source: BooruSource): Int = enqueue(posts, source, emptyMap())

    /** [previewIds]: post id → the id its row had before a lock, whose preview is still kept. */
    private fun enqueue(posts: List<Post>, source: BooruSource, previewIds: Map<Long, String>): Int {
        val added = ArrayList<DownloadTask>()
        synchronized(liveLock) {
            if (!live) return 0
            _tasks.update { current ->
                added.clear()
                val taken = current.filter { it.status != DownloadStatus.FAILED && it.status != DownloadStatus.CANCELLED }
                    .mapTo(HashSet()) { it.source.id to it.post.id }
                posts.filter { (source.id to it.id) !in taken }.distinctBy { it.id }.forEach {
                    val task = DownloadTask(keys.incrementAndGet(), it, source)
                    added += previewIds[it.id]?.let { id -> task.copy(previewId = id) } ?: task
                }
                current + added
            }
            added.forEach(::launch)
        }
        if (added.isNotEmpty()) startService()
        return added.size
    }

    /** Collects up to [max] results of a search, page by page, and queues them as they arrive. */
    fun downloadSearch(source: BooruSource, tags: String, max: Int, alreadyFound: Int = 0, startPage: Int = 1) {
        synchronized(liveLock) {
            if (!live) return
            val batch = BatchJob(keys.incrementAndGet(), source, tags, max, alreadyFound, startPage)
            _batches.update { it + batch }
            collect(batch)
        }
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
                policyReady.first { it }
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
        synchronized(liveLock) {
            if (!live) return
            var resumed: BatchJob? = null
            _batches.update { list ->
                resumed = null
                list.map { if (it.key == key && !it.active) it.copy(problem = null, stopped = false).also { again -> resumed = again } else it }
            }
            resumed?.let(::collect)
        }
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
        synchronized(liveLock) {
            if (!live) return
            set(key) { it.copy(status = DownloadStatus.QUEUED, bytes = 0, total = -1, problem = null) }
            launch(task)
        }
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
        takeOff { !it.active && !it.retriable }
        _batches.update { list -> list.filter { it.active || it.retriable } }
    }

    /** Takes off the list what failed or was cancelled, batches a problem or the user stopped included. */
    fun clearFailed() {
        takeOff { it.retriable }
        _batches.update { list -> list.filter { !it.retriable } }
    }

    /** Takes one download off the list, unless it is under way: done, skipped, failed or cancelled. */
    fun remove(key: Long) {
        takeOff { it.key == key && !it.active }
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
        val job = scope.launch(turn, start = CoroutineStart.LAZY) {
            val own = coroutineContext.job
            parallel.withPermit { withContext(Dispatchers.IO) { run(task.key, own) } }
        }
        job.invokeOnCompletion { jobs.remove(task.key, job) }
        jobs[task.key] = job
        job.start()
    }

    /**
     * [own]: the job this download was launched as, the one listed under its key.
     * [retries]: the tries a failure may still earn this file before it counts as failed.
     * [resume]: whether a transfer cut short may ask for the rest; false once the file's host
     * has answered such a request with something else.
     * [holds]: the times it may still wait for Tor's exit check before being held back by it
     * counts as failed.
     */
    private suspend fun run(key: Long, own: Job, retries: Int = TIMEOUT_RETRIES, resume: Boolean = true, holds: Int = TOR_HOLDS) {
        // At an unlock the queue comes back before the settings and the vault's list are read:
        // until then the filters would be the defaults, and nothing would be found saved already.
        policyReady.first { it }
        store.awaitLoaded()
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
                // Queued from a list page that told less: read in full, the post may say a rating
                // or tags the filters do not allow, and then it is not saved.
                if (!policy.allows(resolved)) {
                    set(key) { it.copy(status = DownloadStatus.SKIPPED, problem = Problem(Problem.Code.FILTERED)) }
                    return
                }
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
                    var cut = receive(body.byteStream(), out, count) ?: endedShort(done, total)
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
                                receive(rest.body.byteStream(), out, count) ?: endedShort(done, total)
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
            // The row's own preview, kept since the row was shown, or fetched now and kept for it.
            val preview = try {
                previews.bytes(task.previewId, post.previewUrl, task.source)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A preview that cannot be had is no reason to drop the file; a cancellation is.
                currentCoroutineContext().ensureActive()
                null
            }
            val hasThumb = preview?.let { bytes ->
                try {
                    store.writeThumbnail(id, bytes)
                } finally {
                    bytes.fill(0)
                }
                true
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
                if (jobs[key] === own) set(key) { it.copy(status = DownloadStatus.CANCELLED) }
                throw e as? CancellationException ?: CancellationException("download cancelled", e)
            }
            val met = e.toProblem()
            if (met.code == Problem.Code.TIMEOUT && left > 0) {
                // Over Tor most often a slow circuit: the next try may take another. The place
                // among the parallel transfers is kept through the pause.
                set(key) { it.copy(status = DownloadStatus.QUEUED, bytes = 0, total = -1) }
                delay(timeoutRetryPauseMs)
                return run(key, own, left - 1, resumable, holds)
            }
            if (met.code == Problem.Code.TOR_UNVERIFIED && holds > 0) {
                // Held back while Tor's exit is being checked, as right after Orbot comes back:
                // nothing went out, and it waits in the queue for the check rather than fail on
                // what a few seconds will mend. Being held was no try at the file.
                set(key) { it.copy(status = DownloadStatus.QUEUED, bytes = 0, total = -1) }
                if (awaitTorVerdict()) return run(key, own, left, resumable, holds - 1)
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
        /** Pages in a row that add nothing the filters allow, after which a batch stops looking. */
        const val MAX_BARREN_PAGES = 10

        /**
         * Tries after the first that a timeout earns a file or a page, before it counts as
         * failed. A file taken up where it was cut gets them anew each time it brings something.
         */
        const val TIMEOUT_RETRIES = 2

        /** Waits for Tor's exit check that one download gets, should the verdict be withdrawn again under it. */
        const val TOR_HOLDS = 3

        /** What cuts a transfer short without saying anything about the file: the rest is worth asking for. */
        val RESUMED = setOf(Problem.Code.TIMEOUT, Problem.Code.NETWORK)
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

/**
 * Copies what [input] gives into [out], telling [count] of each piece. Returns null at the end
 * of the input, or the failure that cut the reading short. A failure of [out] is thrown, as a
 * [StorageWriteException] unless it already says what it is: it is no cut that the rest of the
 * file asked of the host would mend, and [out] may already hold part of the piece it refused.
 */
internal suspend fun receive(input: InputStream, out: OutputStream, count: (Int) -> Unit): IOException? {
    val buffer = ByteArray(64 * 1024)
    try {
        return input.use {
            var cut: IOException? = null
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = try {
                    it.read(buffer)
                } catch (e: IOException) {
                    cut = e
                    break
                }
                if (n < 0) break
                try {
                    out.write(buffer, 0, n)
                } catch (e: IOException) {
                    throw if (e is HasProblem) e else StorageWriteException(e)
                }
                count(n)
            }
            cut
        }
    } finally {
        buffer.fill(0)
    }
}

/**
 * A transfer that ended without a word short of the length its host announced, as an HTTP/2
 * stream may: a cut like any other, to be taken up or reported. Taken for the whole file, it
 * would be saved cut short and, being in the vault, never asked for again.
 */
internal fun endedShort(done: Long, total: Long): IOException? =
    if (total in 0..<Long.MAX_VALUE && done < total) EOFException("the transfer ended at $done of $total bytes") else null

/** The phone's storage refused part of a file being written to the vault: full, or failing. */
class StorageWriteException(cause: IOException) : IOException(cause.message, cause), HasProblem {
    override val problem = Problem(Problem.Code.STORAGE_WRITE, cause.message ?: cause.javaClass.simpleName)
}

/** [items] cut into runs of one [key] each, in their order: a key met again starts a run anew. */
internal fun <T> runsOf(items: List<T>, key: (T) -> Any?): List<List<T>> {
    val runs = ArrayList<MutableList<T>>()
    for (item in items) {
        val last = runs.lastOrNull()
        if (last != null && key(last.first()) == key(item)) last += item else runs += mutableListOf(item)
    }
    return runs
}
