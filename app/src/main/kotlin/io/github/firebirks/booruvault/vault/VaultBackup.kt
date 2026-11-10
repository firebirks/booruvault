package io.github.firebirks.booruvault.vault

import io.github.firebirks.booruvault.util.Problem
import io.github.firebirks.booruvault.util.ProblemException
import io.github.firebirks.booruvault.vault.crypto.SealedFormat
import io.github.firebirks.booruvault.vault.crypto.SyncedFileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.TimeZone
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.coroutines.CoroutineContext

/** The first entry of a backup: which layout the rest follows. */
@Serializable
internal data class BackupInfo(val format: Int)

/** The last entry of a backup: how many came before it, so one cut short between two files shows. */
@Serializable
internal data class BackupEnd(val entries: Int)

/**
 * The whole vault as one file, and back.
 *
 * A backup is a ZIP of the vault's own files as they lie on storage: the header, the settings,
 * and each item's media, thumbnail and metadata, all but the header sealed to the vault's key.
 * Nothing is decrypted to make it and the private key is never needed, so it holds what the
 * vault's folder holds and tells what that folder tells: how many files there are and their
 * padded sizes. It opens with the password the vault had when it was made, which is all that
 * protects it once it has left the phone.
 *
 * Left out: the index, rebuilt from the metadata; the site cookies, which belong to the route
 * and the browser they were earned with; the download queue; and the biometric copy of the key,
 * which only this phone's Keystore opens.
 *
 * ```
 * backup.json            {"format":1}
 * vault.json             the header
 * config.bin             settings, sources, collections (when there are any)
 * media/<id> thumbs/<id> meta/<id>   one item after the other, its metadata last
 * end.json               {"entries":n}
 * ```
 * Stored without compression, sealed data having none to give, and without the files' own
 * dates, which would say when each item was saved.
 */
class VaultBackup(private val root: File, private val vault: VaultManager, private val json: Json) {
    /** Where a backup is laid out before it becomes the vault: next to it, so the move is one step. */
    private val staging = File(root.absoluteFile.parentFile, root.name + ".restore")

    /** What a finished backup holds. */
    data class Summary(val items: Int, val bytes: Long)

    /** Removes what a restore interrupted by the end of the process left behind. */
    fun clearLeftovers() {
        staging.deleteRecursively()
    }

    /**
     * Writes a backup of the vault to [out], which the caller closes. Items saved while it runs
     * may or may not be in it; one deleted meanwhile is left out whole. A password changed
     * meanwhile makes it fail with [Problem.Code.BACKUP_KEY_CHANGED]: files re-sealed since
     * would be to a key the header written first does not hold.
     */
    suspend fun write(out: OutputStream, onProgress: (done: Long, total: Long) -> Unit = { _, _ -> }): Summary = withContext(Dispatchers.IO) {
        val header = vault.headerForBackup() ?: throw ProblemException(Problem.Code.NO_VAULT)
        val config = File(root, CONFIG)
        val ids = File(root, META).list().orEmpty().filter(VaultStore.ID::matches)
        val total = config.length() + ids.sumOf { id -> ITEM_DIRS.sumOf { File(File(root, it), id).length() } }
        val context = coroutineContext
        var done = 0L
        var entries = 0
        var items = 0
        val zip = ZipOutputStream(out.buffered(BUFFER))
        zip.setLevel(Deflater.NO_COMPRESSION)
        // A ZIP keeps dates as the local clock reads them: the same reading on every phone, so
        // that not even the time zone shows.
        val stamp = ENTRY_DAY - TimeZone.getDefault().getOffset(ENTRY_DAY)

        fun entry(name: String, write: (OutputStream) -> Unit) {
            zip.putNextEntry(ZipEntry(name).apply { time = stamp })
            write(zip)
            zip.closeEntry()
            entries++
        }

        fun copy(name: String, input: InputStream) = entry(name) { target ->
            done += input.copyTo(target, context) { moved -> onProgress(done + moved, total) }
        }

        onProgress(0, total)
        entry(INFO) { it.write(json.encodeToString(BackupInfo.serializer(), BackupInfo(FORMAT)).toByteArray()) }
        entry(HEADER) { it.write(json.encodeToString(VaultHeader.serializer(), header).toByteArray()) }
        open(config)?.use { copy(CONFIG, it) }
        for (id in ids) {
            ensureActive()
            // Opened together, before any of the item is written: a file replaced or deleted
            // from here on is still read whole, as it was, through the stream that holds it.
            val meta = open(File(File(root, META), id)) ?: continue
            val media = open(File(File(root, MEDIA), id))
            val thumb = open(File(File(root, THUMBS), id))
            try {
                // Deleted since the listing: its metadata went first, and stands for nothing now.
                if (media == null) continue
                copy("$MEDIA/$id", media)
                thumb?.let { copy("$THUMBS/$id", it) }
                copy("$META/$id", meta)
                items++
            } finally {
                meta.close()
                media?.close()
                thumb?.close()
            }
        }
        val current = vault.headerForBackup() ?: throw ProblemException(Problem.Code.NO_VAULT)
        if (current.publicKey != header.publicKey) throw ProblemException(Problem.Code.BACKUP_KEY_CHANGED)
        val before = entries
        entry(END) { it.write(json.encodeToString(BackupEnd.serializer(), BackupEnd(before)).toByteArray()) }
        zip.finish()
        zip.flush()
        onProgress(total, total)
        Summary(items, done)
    }

    /**
     * Makes the backup read from [input] the vault, where there is none; it comes locked.
     * [onProgress] is told how many bytes of the backup have been read. The backup is laid out
     * whole beside the vault's place first, and only a complete one is moved there: a file that
     * is not a backup, one cut short, or one a later version wrote, leaves no vault at all.
     */
    suspend fun restore(input: InputStream, onProgress: (read: Long) -> Unit = {}) = withContext(Dispatchers.IO) {
        check(vault.state.value == VaultState.Missing) { "vault already exists" }
        val context = coroutineContext
        staging.deleteRecursively()
        try {
            staging.writing { if (!mkdirs()) throw IOException("cannot make room for the backup") }
            val zip = ZipInputStream(CountingInputStream(input, onProgress).buffered(BUFFER))
            var entries = 0
            var complete = false
            while (true) {
                ensureActive()
                val entry = reading { zip.nextEntry } ?: break
                val name = entry.name
                // Nothing follows the end, and nothing but a file is ever there.
                if (complete || entry.isDirectory) throw unreadable()
                when {
                    entries == 0 -> {
                        if (name != INFO) throw unreadable()
                        val info = decode(BackupInfo.serializer(), zip.readSmall(context))
                        if (info.format > FORMAT) throw ProblemException(Problem.Code.BACKUP_NEWER)
                        if (info.format < 1) throw unreadable()
                    }
                    entries == 1 -> {
                        if (name != HEADER) throw unreadable()
                        val bytes = zip.readSmall(context)
                        if (decode(VaultHeader.serializer(), bytes).version > 1) throw ProblemException(Problem.Code.BACKUP_NEWER)
                        File(staging, HEADER).writing { writeBytes(bytes) }
                    }
                    name == END -> {
                        if (decode(BackupEnd.serializer(), zip.readSmall(context)).entries != entries) throw unreadable()
                        complete = true
                    }
                    else -> {
                        val limit = limitFor(name) ?: throw unreadable()
                        val target = File(staging, name)
                        if (target.exists()) throw unreadable()
                        target.parentFile?.writing { mkdirs() }
                        zip.copySealedTo(target, limit, context)
                    }
                }
                entries++
            }
            if (!complete) throw unreadable()
            try {
                vault.adopt(staging)
            } catch (e: VaultHeaderException) {
                throw unreadable(e)
            } catch (e: IllegalArgumentException) {
                // A header whose fields do not decode.
                throw unreadable(e)
            }
        } catch (e: Throwable) {
            staging.deleteRecursively()
            throw e
        }
    }

    /** The most bytes an entry called [name] may hold, or null for a name no backup uses. */
    private fun limitFor(name: String): Long? {
        if (name == CONFIG) return MAX_CONFIG
        val dir = name.substringBefore('/', "")
        if (dir.isEmpty() || !VaultStore.ID.matches(name.substring(dir.length + 1))) return null
        return when (dir) {
            MEDIA -> VaultStore.MAX_MEDIA_BYTES + VaultStore.MAX_MEDIA_BYTES / 64 + SealedFormat.MAX_PADDING + SLACK
            THUMBS -> MAX_THUMB
            META -> MAX_META
            else -> null
        }
    }

    private fun <T> decode(serializer: kotlinx.serialization.KSerializer<T>, bytes: ByteArray): T = try {
        json.decodeFromString(serializer, bytes.decodeToString())
    } catch (e: IllegalArgumentException) {
        // What kotlinx.serialization throws for text that is not the JSON asked for.
        throw unreadable(e)
    }

    /** One of the backup's own small JSON entries, whole; one longer than they come is not one. */
    private fun ZipInputStream.readSmall(context: CoroutineContext): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        while (true) {
            context.ensureActive()
            val n = reading { read(buffer) }
            if (n < 0) return out.toByteArray()
            if (out.size() + n > MAX_SMALL) throw unreadable()
            out.write(buffer, 0, n)
        }
    }

    /**
     * Writes the entry being read to [target], synced as every file of the vault is. It must
     * begin as a sealed file does and hold no more than [limit] bytes: a backup is a file from
     * outside, and nothing in it is taken on its word.
     */
    private fun ZipInputStream.copySealedTo(target: File, limit: Long, context: CoroutineContext) {
        val out = target.writing { SyncedFileOutputStream(this) }
        try {
            val buffer = ByteArray(BUFFER)
            var written = 0L
            while (true) {
                context.ensureActive()
                val n = reading { read(buffer) }
                if (n < 0) break
                if (written < MAGIC.size) {
                    // The first read may bring fewer bytes than the mark is long.
                    for (i in 0 until minOf(n.toLong(), MAGIC.size - written).toInt()) {
                        if (buffer[i] != MAGIC[written.toInt() + i]) throw unreadable()
                    }
                }
                if (written + n > limit) throw unreadable()
                target.writing { out.write(buffer, 0, n) }
                written += n
            }
            if (written < MAGIC.size) throw unreadable()
            target.writing { out.close() }
        } catch (e: Throwable) {
            out.abandon()
            throw e
        }
    }

    /** A step that reads the backup: what stops it is the backup's fault, unless the file itself could not be read. */
    private inline fun <T> reading(read: () -> T): T = try {
        read()
    } catch (e: SourceUnreadable) {
        throw ProblemException(Problem(Problem.Code.FILE_UNREADABLE, e.message ?: e.javaClass.simpleName), e)
    } catch (e: IOException) {
        throw unreadable(e)
    }

    /** A step that writes to the phone's storage: what stops it is the storage's word. */
    private inline fun <T> File.writing(write: File.() -> T): T = try {
        write()
    } catch (e: IOException) {
        throw ProblemException(Problem(Problem.Code.STORAGE_WRITE, e.message?.takeIf { it.isNotBlank() } ?: name), e)
    }

    private fun unreadable(cause: Throwable? = null) = ProblemException(Problem(Problem.Code.BACKUP_UNREADABLE), cause)

    private fun open(file: File): FileInputStream? = try {
        FileInputStream(file)
    } catch (_: FileNotFoundException) {
        null
    }

    /** Copies all of this stream to [out], telling [onMoved] how far it has come. Returns the bytes copied. */
    private fun InputStream.copyTo(out: OutputStream, context: CoroutineContext, onMoved: (Long) -> Unit): Long {
        val buffer = ByteArray(BUFFER)
        var moved = 0L
        while (true) {
            context.ensureActive()
            val n = read(buffer)
            if (n < 0) return moved
            out.write(buffer, 0, n)
            moved += n
            onMoved(moved)
        }
    }

    companion object {
        /** The layout written. A reader refuses a higher one, which it cannot know. */
        const val FORMAT = 1

        const val MIME = "application/zip"

        private const val INFO = "backup.json"
        private const val HEADER = "vault.json"
        private const val CONFIG = "config.bin"
        private const val END = "end.json"
        private const val MEDIA = "media"
        private const val THUMBS = "thumbs"
        private const val META = "meta"
        private val ITEM_DIRS = listOf(MEDIA, THUMBS, META)
        private val MAGIC = SealedFormat.MAGIC

        private const val BUFFER = 256 * 1024

        /** Midday of the first day a ZIP can name, given to every entry: the backup has no dates of its own. */
        private const val ENTRY_DAY = 315_576_000_000L

        // The most each kind of entry may be; far above what the app writes, well below a full disk.
        private const val MAX_SMALL = 1 shl 20
        private const val MAX_META = 4L shl 20
        private const val MAX_THUMB = 16L shl 20
        private const val MAX_CONFIG = 64L shl 20
        private const val SLACK = 1L shl 20
    }
}

/** Tells [onRead] how many bytes have been read through it so far, about once every megabyte. */
private class CountingInputStream(input: InputStream, private val onRead: (Long) -> Unit) : InputStream() {
    private val source = SourceStream(input)
    private var count = 0L
    private var told = 0L

    override fun read(): Int = source.read().also { if (it >= 0) counted(1) }

    override fun read(b: ByteArray, off: Int, len: Int): Int = source.read(b, off, len).also { if (it > 0) counted(it) }

    private fun counted(n: Int) {
        count += n
        if (count - told >= 1 shl 20) {
            told = count
            onRead(count)
        }
    }

    override fun close() = source.close()
}
