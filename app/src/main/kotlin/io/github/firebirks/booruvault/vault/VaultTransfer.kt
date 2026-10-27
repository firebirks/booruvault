package io.github.firebirks.booruvault.vault

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
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

    data class ImportResult(val imported: Int, val failed: Int, val originalsNotDeleted: Int)

    suspend fun import(uris: List<Uri>, deleteOriginals: Boolean, onProgress: (Int, Int) -> Unit): ImportResult =
        withContext(Dispatchers.IO) {
            var imported = 0
            var failed = 0
            var notDeleted = 0
            uris.forEachIndexed { index, uri ->
                ensureActive()
                onProgress(index, uris.size)
                val id = store.newId()
                try {
                    val (name, _) = describe(uri)
                    val mime = resolver.getType(uri)?.takeIf { it != "application/octet-stream" }
                        ?: Mime.fromExtension(name.substringAfterLast('.', ""))
                    val size = store.writeMedia(id) { out ->
                        resolver.openInputStream(uri)?.use { copyWiping(it, out) } ?: throw IOException("cannot open $uri")
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
                    failed++
                }
            }
            onProgress(uris.size, uris.size)
            ImportResult(imported, failed, notDeleted)
        }

    /** Writes decrypted copies into a folder the user picked. Returns how many succeeded. */
    suspend fun exportToFolder(items: List<VaultItem>, tree: Uri, onProgress: (Int, Int) -> Unit): Int =
        withContext(Dispatchers.IO) {
            val folder = DocumentFile.fromTreeUri(context, tree) ?: throw ProblemException(Problem.Code.FOLDER_UNAVAILABLE)
            var done = 0
            items.forEachIndexed { index, item ->
                ensureActive()
                onProgress(index, items.size)
                val target = folder.createFile(item.mimeType, item.fileName) ?: return@forEachIndexed
                try {
                    write(item, target.uri)
                    done++
                } catch (e: Exception) {
                    target.delete()
                    if (e is kotlinx.coroutines.CancellationException) throw e
                }
            }
            onProgress(items.size, items.size)
            done
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
