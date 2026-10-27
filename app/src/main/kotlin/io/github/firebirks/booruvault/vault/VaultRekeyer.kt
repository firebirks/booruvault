package io.github.firebirks.booruvault.vault

import io.github.firebirks.booruvault.vault.crypto.SealedFile
import io.github.firebirks.booruvault.vault.crypto.SealedOutputStream
import io.github.firebirks.booruvault.vault.crypto.VaultKeys
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

/** How far the re-sealing has come, in files. */
data class RekeyProgress(val done: Int, val total: Int)

/**
 * After a password change, re-seals every file to the new key pair. Runs whenever the vault is
 * unlocked and still holds a retired pair; locking stops it and the next unlock resumes it, so
 * a crash or an auto-lock midway costs nothing. Once a full pass finds nothing sealed to a
 * retired pair, and no write that may have begun under the old pair is still in flight, the
 * retired pairs are dropped from the header.
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

    init {
        scope.launch(Dispatchers.IO) {
            vault.state.collectLatest { state ->
                try {
                    if (state is VaultState.Unlocked && state.keys.retired.isNotEmpty()) run(state.keys)
                } finally {
                    _progress.value = null
                }
            }
        }
    }

    private suspend fun run(keys: VaultKeys) {
        val started = System.currentTimeMillis()
        root.walkTopDown().filter { it.isFile && it.name.endsWith(SUFFIX) }.forEach { it.delete() }
        while (true) {
            val files = candidates()
            var done = 0
            var resealed = 0
            _progress.value = RekeyProgress(0, files.size)
            for (file in files) {
                currentCoroutineContext().ensureActive()
                if (reseal(file, keys)) resealed++
                done++
                _progress.value = RekeyProgress(done, files.size)
            }
            // A write that read the old recipient just before the header changed may still land.
            val settled = resealed == 0 && store.writesInFlight == 0 && System.currentTimeMillis() - started >= GRACE_MS
            if (settled) {
                vault.retireFinished()
                return
            }
            delay(if (resealed == 0) 1000 else 50)
        }
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

    /** Re-seals [file] if a retired pair sealed it. False when it is current, gone, or unreadable. */
    private fun reseal(file: File, keys: VaultKeys): Boolean {
        val sealed = try {
            SealedFile(file, keys)
        } catch (e: Exception) {
            return false
        }
        sealed.use { source ->
            if (source.sealedTo.encoded.contentEquals(keys.recipient.encoded)) return false
            val modified = file.lastModified()
            val length = file.length()
            val tmp = File(file.path + SUFFIX)
            try {
                SealedOutputStream(tmp.outputStream().buffered(256 * 1024), keys.recipient).use { out ->
                    val buffer = ByteArray(256 * 1024)
                    try {
                        val input = source.inputStream()
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                        }
                    } finally {
                        buffer.fill(0)
                    }
                }
                // Rewritten meanwhile, under the current pair, or deleted: the copy is stale.
                val unchanged = file.isFile && file.lastModified() == modified && file.length() == length
                if (!unchanged || !tmp.renameTo(file)) {
                    tmp.delete()
                    return false
                }
                return true
            } catch (e: Exception) {
                tmp.delete()
                return false
            }
        }
    }

    private companion object {
        const val SUFFIX = ".rekey"
        const val GRACE_MS = 2000L
    }
}
