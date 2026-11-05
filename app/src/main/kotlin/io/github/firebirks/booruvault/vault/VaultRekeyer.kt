package io.github.firebirks.booruvault.vault

import io.github.firebirks.booruvault.util.moveOver
import io.github.firebirks.booruvault.vault.crypto.SealedFile
import io.github.firebirks.booruvault.vault.crypto.SealedFormat
import io.github.firebirks.booruvault.vault.crypto.SealedOutputStream
import io.github.firebirks.booruvault.vault.crypto.SyncedFileOutputStream
import io.github.firebirks.booruvault.vault.crypto.VaultKeys
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File
import java.security.GeneralSecurityException

/** How far the re-sealing has come, in files; [rekeying] after a password change, otherwise to the current format. */
data class RekeyProgress(val done: Int, val total: Int, val rekeying: Boolean = true)

/**
 * After a password change, re-seals every file to the new key pair. Runs whenever the vault is
 * unlocked and still holds a retired pair; locking stops it and the next unlock resumes it, so
 * a crash or an auto-lock midway costs nothing. Once two full passes in a row, each begun with
 * no write in flight, find nothing sealed to a retired pair and nothing that fails to open, and
 * no write is in flight still, the retired pairs are dropped from the header. A file that fails
 * to open is not taken for re-sealed: it is tried again on the next passes, and only after
 * [GIVE_UP_AFTER] failures in a row is it counted as unreadable under any key. A file that
 * opens but cannot be written anew, on a full disk for one, is never given up: dropping the
 * retired pairs would leave it sealed to a key the vault no longer has.
 *
 * It also brings files of an older sealed format up to the current one, the padded one that
 * hides their sizes: at an unlock, the files still in an older format are re-sealed in one
 * pass; what cannot be written now waits for the next unlock. Once a pass leaves none behind
 * a marker says so, and the files are not looked over again: nothing writes the older format.
 */
class VaultRekeyer(
    private val root: File,
    private val vault: VaultManager,
    private val store: VaultStore,
    scope: CoroutineScope,
) {
    private val _progress = MutableStateFlow<RekeyProgress?>(null)

    /** Non-null while re-sealing is under way. */
    val progress: StateFlow<RekeyProgress?> = _progress.asStateFlow()

    /** Exists once every file was found in the current format; holds nothing, and is gone with the vault. */
    private val formatMarker = File(root, "format-${SealedFormat.VERSION}")

    init {
        scope.launch(Dispatchers.IO) {
            vault.state.collectLatest { state ->
                try {
                    if (state is VaultState.Unlocked) {
                        val older = if (state.keys.retired.isEmpty() && !formatMarker.exists()) olderFormat() else null
                        when {
                            state.keys.retired.isNotEmpty() -> run(state.keys, rekey = true)
                            formatMarker.exists() || older == null -> Unit
                            older.isNotEmpty() -> run(state.keys, rekey = false)
                            else -> markFormatCurrent()
                        }
                    }
                } finally {
                    _progress.value = null
                }
            }
        }
    }

    private fun markFormatCurrent() {
        runCatching { formatMarker.createNewFile() }
    }

    /** [CORRUPT]: the file fails its own authentication, which no later try will mend; [UNREADABLE]: a read failed, which may pass. */
    private enum class Outcome { RESEALED, CURRENT, UNREADABLE, CORRUPT, NOT_WRITTEN }

    private class Unreadable(cause: Exception) : Exception(cause) {
        val outcome: Outcome get() = if (cause is GeneralSecurityException) Outcome.CORRUPT else Outcome.UNREADABLE
    }

    private suspend fun run(keys: VaultKeys, rekey: Boolean) {
        val started = System.currentTimeMillis()
        root.walkTopDown().filter { it.isFile && it.name.endsWith(SUFFIX) }.forEach { it.delete() }
        val failures = HashMap<String, Int>()
        var cleanPasses = 0
        while (true) {
            // A write begun under the old pair can land after the listing, unseen by this pass.
            val writesBefore = store.writesInFlight
            val files = if (rekey) candidates() else olderFormat() ?: return
            var done = 0
            var resealed = 0
            var failed = 0
            _progress.value = RekeyProgress(0, files.size, rekey)
            for (file in files) {
                currentCoroutineContext().ensureActive()
                when (reseal(file, keys)) {
                    Outcome.RESEALED -> {
                        resealed++
                        failures.remove(file.path)
                    }
                    Outcome.CURRENT -> failures.remove(file.path)
                    Outcome.UNREADABLE -> {
                        val count = (failures[file.path] ?: 0) + 1
                        failures[file.path] = count
                        if (count < GIVE_UP_AFTER) failed++
                    }
                    // Under one key, a file that fails its own check will fail it at every unlock:
                    // it must not keep the format pass from being counted as done. Under retired
                    // keys it may be one more try away from the right key, as above.
                    Outcome.CORRUPT -> if (rekey) {
                        val count = (failures[file.path] ?: 0) + 1
                        failures[file.path] = count
                        if (count < GIVE_UP_AFTER) failed++
                    }
                    Outcome.NOT_WRITTEN -> {
                        failures.remove(file.path)
                        failed++
                    }
                }
                done++
                _progress.value = RekeyProgress(done, files.size, rekey)
            }
            // Nothing hangs on an older format but its sizes: one pass, the rest at the next unlock.
            if (!rekey) {
                if (failed == 0) markFormatCurrent()
                return
            }
            cleanPasses = if (resealed == 0 && failed == 0 && writesBefore == 0) cleanPasses + 1 else 0
            // A write that read the old recipient just before the header changed may still land.
            val settled = cleanPasses >= 2 && store.writesInFlight == 0 && System.currentTimeMillis() - started >= GRACE_MS
            if (settled) {
                vault.retireFinished(keys.recipient)
                // Everything was written anew, in the current format.
                markFormatCurrent()
                return
            }
            delay(if (resealed == 0) 1000 else 50)
        }
    }

    /**
     * The sealed files still in a format older than the one written now, or null when a folder
     * could not be listed: then nothing is known, and no marker must say the vault is current.
     */
    private fun olderFormat(): List<File>? {
        val dirs = listOf("media", "thumbs", "meta").map { File(root, it) }
        if (dirs.any { it.isDirectory && it.list() == null }) return null
        return candidates().filter { file -> SealedFormat.versionOf(file)?.let { it < SealedFormat.VERSION } == true }
    }

    /** Every sealed file of the vault, finished writes only. */
    private fun candidates(): List<File> {
        val items = listOf("media", "thumbs", "meta").flatMap { dir ->
            File(root, dir).listFiles { f -> f.isFile && !f.name.endsWith(".part") && !f.name.endsWith(".tmp") && !f.name.endsWith(SUFFIX) }
                ?.toList().orEmpty()
        }
        val blobs = listOf("index.bin", "config.bin", "cookies.bin", "downloads.bin").map { File(root, it) }.filter { it.isFile }
        return items + blobs
    }

    /**
     * Re-seals [file] if a retired pair sealed it, or an older format did; tells a current file
     * from one that would not open. The copy is synced to storage before it replaces the file,
     * as the vault's other writes are, so a power cut right after leaves the old file rather
     * than an empty new one; and it stops where it is when the vault locks, the copy deleted.
     */
    private suspend fun reseal(file: File, keys: VaultKeys): Outcome {
        // Taken before the file is opened: a file replaced between the two would pass as unchanged.
        val modified = file.lastModified()
        val length = file.length()
        val sealed = try {
            SealedFile(file, keys)
        } catch (e: GeneralSecurityException) {
            return Outcome.CORRUPT
        } catch (e: Exception) {
            return if (file.isFile) Outcome.UNREADABLE else Outcome.CURRENT
        }
        sealed.use { source ->
            if (source.sealedTo.encoded.contentEquals(keys.recipient.encoded) && source.version == SealedFormat.VERSION) return Outcome.CURRENT
            // Without room for the copy, the write would fill the storage to the last byte, at every
            // unlock. A small file asks little room: a re-keying must get through them on a full phone.
            val media = file.parentFile?.name == "media"
            val room = file.parentFile?.usableSpace ?: 0L
            if (room < length + SealedFormat.MAX_PADDING + (if (media) SPACE_MARGIN else SMALL_SPACE_MARGIN)) return Outcome.NOT_WRITTEN
            // Proven before the copy: the last chunk, which a file cut short at a power cut lacks,
            // and which the copy of a large one would otherwise reach only after decrypting and
            // writing all the rest, at every unlock; for an empty payload, its one chunk.
            try {
                if (source.size == 0L) source.readAll() else source.read(source.size - 1, ByteArray(1), 0, 1)
            } catch (e: Exception) {
                return Unreadable(e).outcome
            }
            val tmp = File(file.path + SUFFIX)
            // Padded as the store would pad it: to the media quantum under media/, the small one elsewhere.
            val quantum = if (media) SealedFormat.MEDIA_QUANTUM else SealedFormat.SMALL_QUANTUM
            val raw = try {
                SyncedFileOutputStream(tmp)
            } catch (e: Exception) {
                return Outcome.NOT_WRITTEN
            }
            val out = SealedOutputStream(raw.buffered(256 * 1024), keys.recipient, quantum = quantum)
            // A copy given up is dropped as it stands, with no final chunk and no sync, then deleted.
            fun drop() {
                raw.abandon()
                out.abandon()
                tmp.delete()
            }
            try {
                val buffer = ByteArray(256 * 1024)
                try {
                    val input = source.inputStream()
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = try {
                            input.read(buffer)
                        } catch (e: Exception) {
                            throw Unreadable(e)
                        }
                        if (n < 0) break
                        out.write(buffer, 0, n)
                    }
                } finally {
                    buffer.fill(0)
                }
                out.close()
                // Closed before the move, as Windows will not replace a file that is open.
                source.close()
                // Rewritten meanwhile, under the current pair, or deleted: the copy is stale.
                val unchanged = file.isFile && file.lastModified() == modified && file.length() == length
                if (!unchanged || !tmp.moveOver(file)) {
                    tmp.delete()
                    return Outcome.NOT_WRITTEN
                }
                return Outcome.RESEALED
            } catch (e: CancellationException) {
                drop()
                throw e
            } catch (e: Unreadable) {
                drop()
                return e.outcome
            } catch (e: Exception) {
                drop()
                return Outcome.NOT_WRITTEN
            }
        }
    }

    private companion object {
        const val SUFFIX = ".rekey"
        const val GRACE_MS = 2000L
        const val GIVE_UP_AFTER = 5

        /** Storage left alone when a media copy is made: the vault's other writes, and the phone's, need some. */
        const val SPACE_MARGIN = 64L shl 20

        /** Storage left alone when a small file is copied: metadata, thumbnails and the blobs are a few KiB each. */
        const val SMALL_SPACE_MARGIN = 1L shl 20
    }
}
