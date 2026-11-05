package io.github.firebirks.booruvault.vault

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import io.github.firebirks.booruvault.util.HasProblem
import io.github.firebirks.booruvault.util.Mime
import io.github.firebirks.booruvault.util.Problem
import io.github.firebirks.booruvault.util.ProblemException
import io.github.firebirks.booruvault.util.safeFileName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException

/** Moving files between the device and the vault. Plaintext only ever streams through memory. */
class VaultTransfer(private val context: Context, private val store: VaultStore) {
    private val resolver: ContentResolver get() = context.contentResolver

    /** A file that could not be imported: which, and why. */
    data class Failure(val name: String, val problem: Problem)

    data class ImportResult(val imported: Int, val failures: List<Failure>, val originalsNotDeleted: Int) {
        val failed: Int get() = failures.size
    }

    /** How many of the files were written, where, and what stopped the first that was not. */
    data class ExportResult(val done: Int, val total: Int, val folderName: String?, val firstProblem: Problem?) {
        val failed: Int get() = total - done
    }

    suspend fun import(uris: List<Uri>, deleteOriginals: Boolean, onProgress: (Int, Int) -> Unit): ImportResult =
        withContext(Dispatchers.IO) {
            var imported = 0
            val failures = ArrayList<Failure>()
            var notDeleted = 0
            uris.forEachIndexed { index, uri ->
                ensureActive()
                onProgress(index, uris.size)
                val id = store.newId()
                var name = uri.lastPathSegment ?: "file"
                try {
                    val (described, declared) = describe(uri)
                    name = described
                    if (declared != null && declared > VaultStore.MAX_MEDIA_BYTES) throw MediaTooLargeException()
                    val mime = resolver.getType(uri)?.takeIf { it != "application/octet-stream" }
                        ?: Mime.fromExtension(name.substringAfterLast('.', ""))
                    val size = store.writeMedia(id) { out ->
                        resolver.openInputStream(uri)?.use { copyWiping(it, out) } ?: throw ProblemException(Problem.Code.FILE_UNREADABLE, name)
                    }
                    val thumb = store.openMedia(id).use { Thumbnails.from(it, mime) }
                    thumb.jpeg?.let {
                        try {
                            store.writeThumbnail(id, it)
                        } finally {
                            it.fill(0)
                        }
                    }
                    store.commit(
                        VaultItem(
                            id = id,
                            addedAt = System.currentTimeMillis(),
                            fileName = safeFileName(name),
                            mimeType = mime,
                            size = size,
                            width = thumb.width,
                            height = thumb.height,
                            hasThumbnail = thumb.jpeg != null,
                        ),
                    )
                    imported++
                    if (deleteOriginals) {
                        val deleted = runCatching { DocumentsContract.deleteDocument(resolver, uri) }.getOrDefault(false)
                        if (!deleted) notDeleted++
                    }
                } catch (e: Exception) {
                    store.discard(id)
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    failures += Failure(name, e.readProblem(name))
                }
            }
            onProgress(uris.size, uris.size)
            ImportResult(imported, failures, notDeleted)
        }

    /**
     * Writes decrypted copies into a folder the user picked. A file the folder refuses, or that
     * cannot be written, is counted and the first reason kept, so the message can say what went
     * where rather than just how many.
     */
    suspend fun exportToFolder(items: List<VaultItem>, tree: Uri, onProgress: (Int, Int) -> Unit): ExportResult =
        withContext(Dispatchers.IO) {
            val folder = DocumentFile.fromTreeUri(context, tree) ?: throw ProblemException(Problem.Code.FOLDER_UNAVAILABLE)
            var done = 0
            var firstProblem: Problem? = null
            items.forEachIndexed { index, item ->
                ensureActive()
                onProgress(index, items.size)
                val target = folder.createFile(item.mimeType, item.fileName)
                if (target == null) {
                    if (firstProblem == null) firstProblem = Problem(Problem.Code.STORAGE_WRITE, item.fileName)
                    return@forEachIndexed
                }
                try {
                    write(item, target.uri)
                    done++
                } catch (e: Exception) {
                    target.delete()
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    if (firstProblem == null) firstProblem = e.writeProblem(item.fileName)
                }
            }
            onProgress(items.size, items.size)
            ExportResult(done, items.size, folder.name, firstProblem)
        }

    suspend fun exportTo(item: VaultItem, target: Uri) = withContext(Dispatchers.IO) { write(item, target) }

    private fun write(item: VaultItem, target: Uri) {
        val out = resolver.openOutputStream(target, "wt") ?: throw IOException("cannot write $target")
        out.use { stream -> store.openMedia(item.id).use { copyWiping(it.inputStream(), stream) } }
    }

    /** Like copyTo, but the plaintext passing through the buffer does not outlive the copy. */
    private fun copyWiping(input: java.io.InputStream, output: java.io.OutputStream) {
        val buffer = ByteArray(256 * 1024)
        try {
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                output.write(buffer, 0, n)
            }
        } finally {
            buffer.fill(0)
        }
    }

    /** What stopped a file from being read in: the storage's own word, or the problem met. */
    private fun Throwable.readProblem(name: String): Problem =
        if (this is HasProblem) problem else Problem(Problem.Code.FILE_UNREADABLE, message?.takeIf { it.isNotBlank() } ?: name)

    /** What stopped a file from being written out: the storage's own word, or the problem met. */
    private fun Throwable.writeProblem(name: String): Problem =
        if (this is HasProblem) problem else Problem(Problem.Code.STORAGE_WRITE, message?.takeIf { it.isNotBlank() } ?: name)

    private fun describe(uri: Uri): Pair<String, Long?> {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val name = c.getString(0) ?: "file"
                val size = if (c.isNull(1)) null else c.getLong(1)
                return name to size
            }
        }
        return (uri.lastPathSegment ?: "file") to null
    }
}
