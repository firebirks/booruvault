package io.github.firebirks.booruvault.vault.crypto

import io.github.firebirks.booruvault.util.moveOver
import java.io.Closeable
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.PrivateKey
import java.security.PublicKey
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.security.auth.Destroyable

/** The vault's public key together with the exact bytes it is stored as, which feed the KDF. */
class Recipient(val key: PublicKey, val encoded: ByteArray) {
    companion object {
        fun fromEncoded(encoded: ByteArray) = Recipient(EcKeys.publicKey(encoded), encoded)
    }
}

/**
 * The vault's key pair, plus any [retired] pairs whose files are still being re-sealed after a
 * password change. A reader tries the current pair first, then the retired ones.
 */
class VaultKeys(val recipient: Recipient, val privateKey: PrivateKey, val retired: List<VaultKeys> = emptyList()) {
    /**
     * Asks the private key, and every retired one, to wipe itself; a key that cannot stops none
     * of the others. The platform's EC keys cannot be wiped this way: their native copy is
     * cleared only when the GC frees it, so this is a try, not a guarantee.
     */
    fun destroyPrivateKeys() {
        (listOf(this) + retired).forEach { keys -> (keys.privateKey as? Destroyable)?.let { runCatching { it.destroy() } } }
    }
}

/**
 * A file sealed to the vault's public key: ECIES (P-256 ECDH, HKDF-SHA256) wrapping a chunked
 * AES-256-GCM stream in the style of the STREAM construction.
 *
 * Anyone holding the public key can write one, which is what lets downloads land in the vault
 * while it is locked; only the private key, unlocked by the password, can read it back.
 *
 * Layout:
 * ```
 * "BVS1" | version(1) | chunkSize(4) | salt(16) | ephemeralKeyLength(2) | ephemeralKey(X.509)
 * chunk 0 .. chunk n-1, each AES-GCM(plaintext[≤ chunkSize]) || tag(16)
 * ```
 * The nonce of chunk i is prefix(7) || i(4) || last(1), so chunks cannot be reordered and a
 * file truncated on a chunk boundary fails authentication instead of reading short. The whole
 * header is the associated data of every chunk. Every chunk except the last is full, which
 * makes any plaintext offset addressable without reading what comes before it.
 *
 * Since version 2 the plaintext ends with padding: zero bytes, then its own length as a
 * big-endian int in the last [TRAILER] bytes, from [TRAILER] to [MAX_PADDING] bytes in all,
 * chosen so that the file's length on disk is a multiple of a quantum the writer picks:
 * [MEDIA_QUANTUM] for media, [SMALL_QUANTUM] for the rest. The padding is sealed with the rest,
 * so the file's length says nothing of the payload's beyond the quantum it falls in, and a
 * reader strips what the trailer names without needing to know the quantum. Version 1 files,
 * with no padding, are still read; they are written anew by the background re-sealing.
 */
object SealedFormat {
    val MAGIC = byteArrayOf('B'.code.toByte(), 'V'.code.toByte(), 'S'.code.toByte(), '1'.code.toByte())

    /** The version written: 2, padded. */
    const val VERSION: Byte = 2

    /** The oldest version still read: 1, unpadded. */
    const val OLDEST_VERSION: Byte = 1
    const val DEFAULT_CHUNK = 64 * 1024
    const val TAG = 16

    /**
     * A media file's length on disk is a multiple of this: one chunk. A site's API states the
     * size of every post, so the exact size would name the post; this hides it among every
     * size within the same 64 KiB, at a cost of up to that much storage per file.
     */
    const val MEDIA_QUANTUM = 64 * 1024

    /**
     * Every other sealed file's length on disk is a multiple of this: the storage's own block,
     * so it costs nothing. Metadata is a few hundred bytes and a thumbnail tens of KiB, and
     * no one outside the app knows their sizes; what is hidden is the exact length alone.
     */
    const val SMALL_QUANTUM = 4 * 1024

    /** The bytes at the very end of the padding that say how long it is, itself included. */
    const val TRAILER = 4

    /**
     * The most padding bytes a file carries: two of the largest quantum, since every chunk the
     * padding runs into brings a tag of its own, which skips some lengths.
     */
    const val MAX_PADDING = 2 * MEDIA_QUANTUM
    private const val SALT = 16
    private const val PREFIX = 7
    private val INFO = "BooruVault sealed stream v1".toByteArray()

    internal class Header(val bytes: ByteArray, val version: Byte, val chunkSize: Int, val salt: ByteArray, val ephemeralKey: ByteArray) {
        val padded: Boolean get() = version >= 2
    }

    internal class StreamKey(val key: SecretKeySpec, val prefix: ByteArray)

    internal fun newHeader(chunkSize: Int, ephemeralKey: ByteArray, version: Byte = VERSION): Header {
        require(version in OLDEST_VERSION..VERSION) { "unknown sealed version $version" }
        val salt = randomBytes(SALT)
        val buffer = ByteBuffer.allocate(MAGIC.size + 1 + 4 + SALT + 2 + ephemeralKey.size)
        buffer.put(MAGIC).put(version).putInt(chunkSize).put(salt)
            .putShort(ephemeralKey.size.toShort()).put(ephemeralKey)
        return Header(buffer.array(), version, chunkSize, salt, ephemeralKey)
    }

    internal fun readHeader(input: RandomAccessFile): Header {
        val fixed = ByteArray(MAGIC.size + 1 + 4 + SALT + 2)
        input.seek(0)
        input.readFully(fixed)
        val buffer = ByteBuffer.wrap(fixed)
        val magic = ByteArray(MAGIC.size).also { buffer.get(it) }
        if (!magic.contentEquals(MAGIC)) throw GeneralSecurityException("not a sealed vault file")
        val version = buffer.get()
        if (version !in OLDEST_VERSION..VERSION) throw GeneralSecurityException("unsupported sealed version $version")
        val chunkSize = buffer.getInt()
        if (chunkSize !in 1024..(16 shl 20)) throw GeneralSecurityException("bad chunk size")
        val salt = ByteArray(SALT).also { buffer.get(it) }
        val keyLength = buffer.getShort().toInt() and 0xffff
        if (keyLength !in 1..512) throw GeneralSecurityException("bad key length")
        val ephemeralKey = ByteArray(keyLength)
        input.readFully(ephemeralKey)
        return Header(fixed + ephemeralKey, version, chunkSize, salt, ephemeralKey)
    }

    /**
     * The version a sealed [file] was written with, read from its first bytes alone, or null for
     * a file that is not one. Cheap enough to ask of every file of the vault at each unlock.
     */
    fun versionOf(file: File): Byte? = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            val head = ByteArray(MAGIC.size + 1)
            raf.readFully(head)
            if (head.copyOf(MAGIC.size).contentEquals(MAGIC)) head[MAGIC.size] else null
        }
    }.getOrNull()

    /**
     * How many padding bytes bring a file of [headerLength] header bytes and [plain] payload
     * bytes, in chunks of [chunkSize], to a length on disk that is a multiple of [quantum]: the
     * fewest that do, from [TRAILER] up. The padding may run into chunks of its own, whose tags
     * count too, so the search goes chunk by chunk: within one, the length on disk grows with
     * the payload one for one, and the first payload length that lands on the quantum is taken
     * if it fits the chunk.
     */
    internal fun paddingFor(headerLength: Int, plain: Long, chunkSize: Int, quantum: Int): Int {
        require(quantum in 1..MEDIA_QUANTUM) { "quantum out of range" }
        val least = plain + TRAILER
        var chunks = maxOf(1, (least + chunkSize - 1) / chunkSize)
        while (true) {
            val lowest = maxOf((chunks - 1) * chunkSize + 1, least)
            val highest = chunks * chunkSize
            if (lowest <= highest) {
                val wanted = Math.floorMod(-(headerLength + TAG * chunks), quantum.toLong())
                val total = lowest + Math.floorMod(wanted - lowest, quantum.toLong())
                if (total <= highest) {
                    val padding = total - plain
                    if (padding > MAX_PADDING) throw IllegalStateException("no padding brings the file to a multiple of $quantum")
                    return padding.toInt()
                }
            }
            chunks++
        }
    }

    internal fun streamKey(shared: ByteArray, header: Header, recipient: Recipient): StreamKey {
        val material = Hkdf.derive(shared, header.salt, INFO + header.bytes + recipient.encoded, 32 + PREFIX)
        shared.fill(0)
        val key = SecretKeySpec(material, 0, 32, "AES")
        val prefix = material.copyOfRange(32, 32 + PREFIX)
        material.fill(0)
        return StreamKey(key, prefix)
    }

    internal fun nonce(prefix: ByteArray, index: Long, last: Boolean): GCMParameterSpec {
        require(index in 0..0xffffffffL) { "stream too long" }
        val nonce = ByteBuffer.allocate(12).put(prefix).putInt(index.toInt()).put(if (last) 1 else 0).array()
        return GCMParameterSpec(TAG * 8, nonce)
    }
}

/**
 * Writes a sealed stream. Closing it pads the file to a multiple of [quantum] and writes the
 * final chunk; a stream abandoned midway stays unreadable. [version] is the format written:
 * tests write version 1 to check it still reads.
 */
class SealedOutputStream(
    private val out: OutputStream,
    private val recipient: Recipient,
    chunkSize: Int = SealedFormat.DEFAULT_CHUNK,
    private val quantum: Int = SealedFormat.MEDIA_QUANTUM,
    version: Byte = SealedFormat.VERSION,
) : OutputStream() {
    private val header: SealedFormat.Header
    private val streamKey: SealedFormat.StreamKey
    private val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    private val buffer = ByteArray(chunkSize)
    private val output = ByteArray(chunkSize + SealedFormat.TAG)
    private var buffered = 0
    private var index = 0L
    private var closed = false

    /** Plaintext bytes written so far. */
    var bytesWritten = 0L
        private set

    init {
        val ephemeral = EcKeys.generate()
        header = SealedFormat.newHeader(chunkSize, ephemeral.public.encoded, version)
        streamKey = SealedFormat.streamKey(EcKeys.agree(ephemeral.private, recipient.key), header, recipient)
        out.write(header.bytes)
    }

    override fun write(b: Int) {
        write(byteArrayOf(b.toByte()), 0, 1)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        if (closed) throw IOException("stream closed")
        append(b, off, len)
        bytesWritten += len
    }

    private fun append(b: ByteArray, off: Int, len: Int) {
        var offset = off
        var remaining = len
        while (remaining > 0) {
            // A full buffer is only known not to be the last chunk once more data arrives.
            if (buffered == buffer.size) emit(last = false)
            val n = minOf(remaining, buffer.size - buffered)
            System.arraycopy(b, offset, buffer, buffered, n)
            buffered += n
            offset += n
            remaining -= n
        }
    }

    /** Appends the padding that brings the file to a multiple of the size quantum; see [SealedFormat]. */
    private fun pad() {
        val padding = SealedFormat.paddingFor(header.bytes.size, index * buffer.size + buffered, buffer.size, quantum)
        val zeros = ByteArray(minOf(padding - SealedFormat.TRAILER, 8 * 1024))
        var remaining = padding - SealedFormat.TRAILER
        while (remaining > 0) {
            val n = minOf(remaining, zeros.size)
            append(zeros, 0, n)
            remaining -= n
        }
        append(ByteBuffer.allocate(SealedFormat.TRAILER).putInt(padding).array(), 0, SealedFormat.TRAILER)
    }

    private fun emit(last: Boolean) {
        cipher.init(Cipher.ENCRYPT_MODE, streamKey.key, SealedFormat.nonce(streamKey.prefix, index, last))
        cipher.updateAAD(header.bytes)
        val n = cipher.doFinal(buffer, 0, buffered, output, 0)
        out.write(output, 0, n)
        index++
        buffered = 0
    }

    override fun flush() = out.flush()

    override fun close() {
        if (closed) return
        closed = true
        try {
            if (header.padded) pad()
            emit(last = true)
            out.flush()
        } finally {
            buffer.fill(0)
            out.close()
        }
    }

    /**
     * Drops the stream with no final chunk, for a file that is to be deleted: nothing more is
     * written or synced, and the plaintext held for the next chunk is wiped.
     */
    fun abandon() {
        if (closed) return
        closed = true
        buffer.fill(0)
        runCatching { out.close() }
    }
}

/** Random-access reader for a sealed file. Thread-safe; decrypted chunks never leave memory. */
class SealedFile(file: File, keys: VaultKeys) : Closeable {
    private val raf = RandomAccessFile(file, "r")
    private val header: SealedFormat.Header
    private var streamKey: SealedFormat.StreamKey
    private val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    private val cipherChunk: Int
    private val chunkCount: Long
    private val fileLength: Long
    private val ciphertext: ByteArray
    private val plain: ByteArray
    private var plainIndex = -1L
    private var plainLength = 0

    /** Plaintext length in bytes, the padding left out. */
    val size: Long

    /** The key pair this file is sealed to: the vault's current one, or a retired one. */
    val sealedTo: Recipient

    /** The format version the file was written with; see [SealedFormat.VERSION]. */
    val version: Byte

    init {
        try {
            header = SealedFormat.readHeader(raf)
            version = header.version
            val ephemeral = EcKeys.publicKey(header.ephemeralKey)
            cipherChunk = header.chunkSize + SealedFormat.TAG
            fileLength = raf.length()
            val body = fileLength - header.bytes.size
            if (body < SealedFormat.TAG) throw GeneralSecurityException("truncated sealed file")
            chunkCount = (body + cipherChunk - 1) / cipherChunk
            val lastLength = body - (chunkCount - 1) * cipherChunk
            if (lastLength < SealedFormat.TAG) throw GeneralSecurityException("truncated sealed file")
            val sealedLength = body - chunkCount * SealedFormat.TAG
            ciphertext = ByteArray(cipherChunk)
            plain = ByteArray(header.chunkSize)
            streamKey = streamKeyFor(keys, ephemeral)
            sealedTo = if (keys.retired.isEmpty()) {
                keys.recipient
            } else {
                // Nothing in the header says which pair a file is sealed to; the first chunk tells.
                (listOf(keys) + keys.retired).firstOrNull { candidate ->
                    streamKey = streamKeyFor(candidate, ephemeral)
                    runCatching { load(0) }.isSuccess
                }?.recipient ?: throw GeneralSecurityException("sealed to a key the vault no longer has")
            }
            size = if (header.padded) sealedLength - paddingLength(sealedLength) else sealedLength
        } catch (e: Exception) {
            raf.close()
            throw e
        }
    }

    /**
     * How many padding bytes end the [sealedLength] bytes of plaintext: what the trailer says,
     * once every byte before it that it counts is found to be zero. Anything else is a file
     * that was not written by this format.
     */
    private fun paddingLength(sealedLength: Long): Int {
        if (sealedLength < SealedFormat.TRAILER) throw GeneralSecurityException("missing padding")
        val trailer = ByteArray(SealedFormat.TRAILER)
        readWithin(sealedLength, sealedLength - SealedFormat.TRAILER, trailer, 0, trailer.size)
        val padding = ByteBuffer.wrap(trailer).getInt()
        if (padding < SealedFormat.TRAILER || padding > SealedFormat.MAX_PADDING || padding > sealedLength) throw GeneralSecurityException("bad padding")
        val zeros = ByteArray(minOf(padding - SealedFormat.TRAILER, 8 * 1024))
        var position = sealedLength - padding
        val end = sealedLength - SealedFormat.TRAILER
        while (position < end) {
            val n = readWithin(sealedLength, position, zeros, 0, minOf(zeros.size.toLong(), end - position).toInt())
            if (n <= 0) throw EOFException()
            for (i in 0 until n) if (zeros[i] != 0.toByte()) throw GeneralSecurityException("bad padding")
            position += n
        }
        return padding
    }

    private fun streamKeyFor(candidate: VaultKeys, ephemeral: PublicKey): SealedFormat.StreamKey =
        SealedFormat.streamKey(EcKeys.agree(candidate.privateKey, ephemeral), header, candidate.recipient)

    /** Reads up to [length] bytes at [position]; returns -1 at the end. */
    @Synchronized
    fun read(position: Long, dst: ByteArray, offset: Int, length: Int): Int = readWithin(size, position, dst, offset, length)

    /** [read], over the first [limit] bytes of the plaintext: the whole of it, padding included, while the padding is being measured. */
    private fun readWithin(limit: Long, position: Long, dst: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (position >= limit) return -1
        var pos = position
        var out = offset
        var remaining = minOf(length.toLong(), limit - position).toInt()
        var total = 0
        while (remaining > 0) {
            val index = pos / header.chunkSize
            val within = (pos % header.chunkSize).toInt()
            load(index)
            val n = minOf(remaining, plainLength - within)
            if (n <= 0) throw EOFException()
            System.arraycopy(plain, within, dst, out, n)
            pos += n
            out += n
            remaining -= n
            total += n
        }
        return total
    }

    private fun load(index: Long) {
        if (index == plainIndex) return
        plainIndex = -1
        val last = index == chunkCount - 1
        val start = header.bytes.size + index * cipherChunk
        val length = if (last) (fileLength - start).toInt() else cipherChunk
        raf.seek(start)
        raf.readFully(ciphertext, 0, length)
        cipher.init(Cipher.DECRYPT_MODE, streamKey.key, SealedFormat.nonce(streamKey.prefix, index, last))
        cipher.updateAAD(header.bytes)
        plainLength = cipher.doFinal(ciphertext, 0, length, plain, 0)
        plainIndex = index
    }

    /** Reads and authenticates the whole file. For small files such as metadata and thumbnails. */
    fun readAll(): ByteArray {
        require(size <= Int.MAX_VALUE - 64) { "file too large to read at once" }
        val out = ByteArray(size.toInt())
        var pos = 0
        while (pos < out.size) {
            val n = read(pos.toLong(), out, pos, out.size - pos)
            if (n < 0) throw EOFException()
            pos += n
        }
        // An empty unpadded file still has its final chunk, and only decrypting it proves it is
        // genuine; a padded one had its last chunk decrypted for the padding when opened.
        if (out.isEmpty()) synchronized(this) { load(0) }
        return out
    }

    fun inputStream(start: Long = 0): InputStream = object : InputStream() {
        private var position = start

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = this@SealedFile.read(position, b, off, len)
            if (n > 0) position += n
            return n
        }

        override fun skip(n: Long): Long {
            val skipped = n.coerceIn(0, size - position)
            position += skipped
            return skipped
        }

        override fun available(): Int = (size - position).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
    }

    @Synchronized
    override fun close() {
        plain.fill(0)
        plainIndex = -1
        raf.close()
    }
}

/** Small whole files sealed to the vault: written atomically, readable only when unlocked. */
object SealedBlob {
    /** One writer at a time for each file: two at once would write over each other's copy. */
    private val writing = ConcurrentHashMap<String, Any>()

    fun write(file: File, recipient: Recipient, bytes: ByteArray) = synchronized(writing.computeIfAbsent(file.absolutePath) { Any() }) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        try {
            // Synced before the rename, as the vault header is, so a power cut right after it
            // leaves the previous file in place rather than an empty new one.
            SealedOutputStream(SyncedFileOutputStream(tmp).buffered(), recipient, quantum = SealedFormat.SMALL_QUANTUM).use { it.write(bytes) }
            if (!tmp.moveOver(file)) throw IOException("cannot write ${file.name}")
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
    }

    /** Null when the file does not exist. The caller wipes the result once decoded. */
    fun read(file: File, keys: VaultKeys): ByteArray? =
        if (!file.exists()) null else SealedFile(file, keys).use { it.readAll() }
}

/**
 * Synced to storage as it closes, the first time only: Android's finalizer closes a stream again
 * even if it was closed, and a sync then would fail on the released descriptor.
 */
internal class SyncedFileOutputStream(file: File) : FileOutputStream(file) {
    @Volatile
    private var closed = false

    override fun close() {
        if (closed) return
        closed = true
        try {
            fd.sync()
        } finally {
            super.close()
        }
    }

    /** Closes without a sync, for a file that is to be deleted. */
    fun abandon() {
        if (closed) return
        closed = true
        super.close()
    }
}
