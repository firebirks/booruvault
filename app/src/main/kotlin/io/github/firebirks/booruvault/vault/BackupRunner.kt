package io.github.firebirks.booruvault.vault

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import io.github.firebirks.booruvault.util.HasProblem
import io.github.firebirks.booruvault.util.Problem
import io.github.firebirks.booruvault.util.ProblemException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException

/** A backup being made or brought back, or how the last one ended. */
sealed interface BackupState {
    val restoring: Boolean

    data object Idle : BackupState {
        override val restoring: Boolean get() = false
    }

    /** [done] of [total] bytes; a [total] of zero is one not known yet. */
    data class Running(override val restoring: Boolean, val done: Long = 0, val total: Long = 0) : BackupState
    data class Done(override val restoring: Boolean, val bytes: Long) : BackupState
    data class Failed(override val restoring: Boolean, val problem: Problem) : BackupState
}

/**
 * Makes a backup into a document the user picked, or brings one back from it, one at a time and
 * in the app's own scope: neither needs the private key, so a lock does not stop it, and how it
 * stands, or ended, is kept here for whichever screen is up to show.
 */
class BackupRunner(
    private val context: Context,
    private val backup: VaultBackup,
    vault: VaultManager,
    private val scope: CoroutineScope,
    /** Starts [BackupService], which keeps the process alive while there is work. */
    private val wakeService: () -> Unit,
) {
    private val _state = MutableStateFlow<BackupState>(BackupState.Idle)
    val state: StateFlow<BackupState> = _state.asStateFlow()

    private var job: Job? = null

    init {
        scope.launch(Dispatchers.IO) { backup.clearLeftovers() }
        // A restored vault says so on its lock screen, until it is opened.
        scope.launch {
            vault.state.collect { state ->
                if (state is VaultState.Unlocked) _state.update { if (it is BackupState.Done && it.restoring) BackupState.Idle else it }
            }
        }
    }

    /** Writes a backup of the vault to [target]; what was written of one that fails is deleted. */
    fun backUp(target: Uri) = start(restoring = false) {
        try {
            val out = context.contentResolver.openOutputStream(target, "wt") ?: throw IOException("cannot write $target")
            val summary = out.use { backup.write(it) { done, total -> _state.value = BackupState.Running(false, done, total) } }
            summary.bytes
        } catch (e: Throwable) {
            runCatching { DocumentsContract.deleteDocument(context.contentResolver, target) }
            throw e
        }
    }

    /** Makes the backup at [source] the vault, where there is none. */
    fun restore(source: Uri) = start(restoring = true) {
        val total = sizeOf(source)
        val input = try {
            context.contentResolver.openInputStream(source)
        } catch (e: IOException) {
            null
        } ?: throw ProblemException(Problem.Code.FILE_UNREADABLE, source.lastPathSegment)
        var read = 0L
        input.use {
            backup.restore(it) { bytes ->
                read = bytes
                _state.value = BackupState.Running(true, bytes, total)
            }
        }
        maxOf(read, total)
    }

    /** Stops the one under way; its file, or the vault half laid out, is removed. */
    fun cancel() {
        job?.cancel()
    }

    @Synchronized
    private fun start(restoring: Boolean, work: suspend () -> Long) {
        if (job?.isActive == true) return
        _state.value = BackupState.Running(restoring)
        runCatching(wakeService)
        job = scope.launch(Dispatchers.IO) {
            _state.value = try {
                BackupState.Done(restoring, work())
            } catch (e: CancellationException) {
                _state.value = BackupState.Idle
                throw e
            } catch (e: Exception) {
                BackupState.Failed(restoring, e.backupProblem())
            }
        }
    }

    private fun sizeOf(uri: Uri): Long = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else 0L
        }
    }.getOrNull() ?: 0L
}

/** What stopped a backup: the problem met, or else the storage it was written to or laid out on, in its own words. */
internal fun Throwable.backupProblem(): Problem = when (this) {
    is HasProblem -> problem
    is IOException, is SecurityException -> Problem(Problem.Code.STORAGE_WRITE, message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName)
    else -> Problem(Problem.Code.UNKNOWN, message ?: javaClass.simpleName)
}
