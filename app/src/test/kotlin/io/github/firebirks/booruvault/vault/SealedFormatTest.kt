package io.github.firebirks.booruvault.vault

import io.github.firebirks.booruvault.vault.crypto.EcKeys
import io.github.firebirks.booruvault.vault.crypto.Recipient
import io.github.firebirks.booruvault.vault.crypto.SealedFile
import io.github.firebirks.booruvault.vault.crypto.SealedFormat
import io.github.firebirks.booruvault.vault.crypto.SealedOutputStream
import io.github.firebirks.booruvault.vault.crypto.VaultKeys
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import java.security.GeneralSecurityException
import kotlin.random.Random

class SealedFormatTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val pair = EcKeys.generate()
    private val keys = VaultKeys(Recipient.fromEncoded(pair.public.encoded), pair.private)

    private fun seal(data: ByteArray, chunk: Int = 1024, version: Byte = SealedFormat.VERSION, quantum: Int = SealedFormat.MEDIA_QUANTUM): File {
        val file = tmp.newFile()
        SealedOutputStream(file.outputStream(), keys.recipient, chunk, quantum, version).use { out ->
            // Uneven writes exercise the chunk boundary logic.
            var pos = 0
            val random = Random(7)
            while (pos < data.size) {
                val n = minOf(data.size - pos, random.nextInt(1, 3000))
                out.write(data, pos, n)
                pos += n
            }
        }
        return file
    }

    @Test
    fun roundTripsAcrossSizes() {
        for (size in listOf(0, 1, 1023, 1024, 1025, 2048, 5000, 100_000)) {
            val data = Random(size).nextBytes(size)
            val file = seal(data)
            SealedFile(file, keys).use {
                assertEquals(size.toLong(), it.size)
                assertArrayEquals("size $size", data, it.readAll())
            }
        }
    }

    @Test
    fun randomAccessReadsMatch() {
        val data = Random(1).nextBytes(50_000)
        val file = seal(data)
        SealedFile(file, keys).use { sealed ->
            val random = Random(2)
            repeat(200) {
                val pos = random.nextInt(0, data.size)
                val len = random.nextInt(1, 4000)
                val buffer = ByteArray(len)
                val n = sealed.read(pos.toLong(), buffer, 0, len)
                val expected = minOf(len, data.size - pos)
                assertEquals(expected, n)
                assertArrayEquals(data.copyOfRange(pos, pos + n), buffer.copyOf(n))
            }
            assertEquals(-1, sealed.read(data.size.toLong(), ByteArray(4), 0, 4))
        }
    }

    @Test
    fun streamReadsEverything() {
        val data = Random(3).nextBytes(10_000)
        SealedFile(seal(data), keys).use { sealed ->
            assertArrayEquals(data, sealed.inputStream().readBytes())
        }
    }

    @Test
    fun ciphertextDoesNotContainPlaintext() {
        val data = ByteArray(4096) { 'A'.code.toByte() }
        val bytes = seal(data).readBytes()
        assertEquals(-1, bytes.toList().windowed(64).indexOfFirst { window -> window.all { it == 'A'.code.toByte() } })
    }

    @Test
    fun sameInputSealsDifferently() {
        val data = Random(4).nextBytes(3000)
        assertNotEquals(seal(data).readBytes().toList(), seal(data).readBytes().toList())
    }

    @Test
    fun tamperingIsDetected() {
        val data = Random(5).nextBytes(5000)
        val file = seal(data)
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(raf.length() - 40)
            val b = raf.read()
            raf.seek(raf.length() - 40)
            raf.write(b xor 1)
        }
        expectFailure { SealedFile(file, keys).use { it.readAll() } }
    }

    @Test
    fun truncationAtChunkBoundaryIsDetected() {
        val data = Random(6).nextBytes(4096)
        val file = seal(data)
        // Drop the final chunk: the one before it was not sealed as last, so it must not verify.
        val bytes = file.readBytes()
        val body = bytes.size - headerLength(bytes)
        val lastChunk = (body % (1024 + 16)).let { if (it == 0) 1024 + 16 else it }
        file.writeBytes(bytes.copyOf(bytes.size - lastChunk))
        expectFailure { SealedFile(file, keys).use { it.readAll() } }
    }

    /** Magic, version, chunk size, salt, key length, then the key the length names. */
    private fun headerLength(bytes: ByteArray): Int {
        val keyLength = ((bytes[27].toInt() and 0xff) shl 8) or (bytes[28].toInt() and 0xff)
        return 4 + 1 + 4 + 16 + 2 + keyLength
    }

    @Test
    fun everyFileIsAMultipleOfItsQuantumWhateverItHolds() {
        for (quantum in listOf(SealedFormat.MEDIA_QUANTUM, SealedFormat.SMALL_QUANTUM)) {
            val sizes = (0..40).toList() + listOf(1000, 1023, 1024, 1025, 2047, 2048, 2049, 3000, 100_000, 200_000) +
                // Payloads on either side of the quantum, where the padding is least or greatest.
                (-140..140 step 7).map { quantum + it } + (-140..140 step 7).map { 2 * quantum - 118 - 16 * 128 + it }
            for (size in sizes.filter { it >= 0 }) {
                val data = Random(size).nextBytes(size)
                val file = seal(data, quantum = quantum)
                assertEquals("quantum $quantum size $size", 0L, file.length() % quantum)
                SealedFile(file, keys).use {
                    assertEquals("quantum $quantum size $size", size.toLong(), it.size)
                    assertEquals(SealedFormat.VERSION, it.version)
                    assertArrayEquals("quantum $quantum size $size", data, it.readAll())
                }
            }
        }
    }

    @Test
    fun theLengthOnDiskSaysOnlyWhichQuantumThePayloadFallsIn() {
        // Media payloads from a byte to tens of KiB all seal to a file of the same length.
        val lengths = listOf(1, 300, 5000, 20_000, 40_000).map { seal(ByteArray(it)).length() }
        assertEquals(1, lengths.toSet().size)
        assertEquals(0L, lengths.first() % SealedFormat.MEDIA_QUANTUM)
        // A small file costs a block, not a chunk: the same payloads under the small quantum.
        val small = listOf(1, 300, 2000).map { seal(ByteArray(it), quantum = SealedFormat.SMALL_QUANTUM).length() }
        assertEquals(1, small.toSet().size)
        assertEquals(SealedFormat.SMALL_QUANTUM.toLong(), small.first())
    }

    @Test
    fun randomAccessAndStreamingStopShortOfThePadding() {
        val data = Random(9).nextBytes(5000)
        SealedFile(seal(data), keys).use { sealed ->
            assertArrayEquals(data, sealed.inputStream().readBytes())
            val tail = ByteArray(200)
            assertEquals(3, sealed.read(4997, tail, 0, tail.size))
            assertArrayEquals(data.copyOfRange(4997, 5000), tail.copyOf(3))
            assertEquals(-1, sealed.read(5000, tail, 0, tail.size))
        }
    }

    @Test
    fun unpaddedFilesOfTheFirstVersionStillRead() {
        for (size in listOf(0, 1, 1024, 5000)) {
            val data = Random(size).nextBytes(size)
            val file = seal(data, version = 1)
            assertEquals(1.toByte(), SealedFormat.versionOf(file))
            SealedFile(file, keys).use {
                assertEquals(size.toLong(), it.size)
                assertEquals(1.toByte(), it.version)
                assertArrayEquals(data, it.readAll())
            }
        }
        assertEquals(SealedFormat.VERSION, SealedFormat.versionOf(seal(ByteArray(10))))
        assertEquals(null, SealedFormat.versionOf(tmp.newFile().apply { writeText("not sealed") }))
    }

    @Test
    fun theMostPaddingIsTwoQuantaLessTheTrailerAndTheTagsItBrings() {
        // The worst case: the payload ends just where a chunk's tag skips the lengths that fit.
        assertEquals(131_059, SealedFormat.paddingFor(118, 267_845_639L, 65536, SealedFormat.MEDIA_QUANTUM))
        assertEquals(8_179, SealedFormat.paddingFor(118, 16_248_839L, 65536, SealedFormat.SMALL_QUANTUM))
        assertTrue(131_059 <= SealedFormat.MAX_PADDING)
    }

    @Test
    fun paddingAlwaysExistsAndIsTheLeast() {
        // For every way the payload can end against the chunks, one padding from the trailer up
        // fits, and none shorter would.
        val header = 118
        for (quantum in listOf(SealedFormat.MEDIA_QUANTUM, SealedFormat.SMALL_QUANTUM)) {
            for (chunk in listOf(1024, SealedFormat.DEFAULT_CHUNK)) {
                val plains = (0L..3000L) + (1L..40L).map { quantum - 118 - 16 - it } + listOf(100_000L, 1_000_000L)
                for (plain in plains) {
                    val k = SealedFormat.paddingFor(header, plain, chunk, quantum)
                    assertTrue("quantum $quantum chunk $chunk plain $plain padding $k", k in SealedFormat.TRAILER..SealedFormat.MAX_PADDING)
                    fun length(padding: Int): Long {
                        val total = plain + padding
                        return header + total + ((total + chunk - 1) / chunk) * SealedFormat.TAG
                    }
                    assertEquals("quantum $quantum chunk $chunk plain $plain", 0L, length(k) % quantum)
                    for (shorter in SealedFormat.TRAILER until k) assertTrue(length(shorter) % quantum != 0L)
                }
            }
        }
    }

    @Test
    fun wrongKeyFails() {
        val file = seal(Random(8).nextBytes(2000))
        val other = EcKeys.generate()
        val otherKeys = VaultKeys(Recipient.fromEncoded(other.public.encoded), other.private)
        expectFailure { SealedFile(file, otherKeys).use { it.readAll() } }
    }

    private fun expectFailure(block: () -> Unit) {
        try {
            block()
            fail("expected an authentication failure")
        } catch (_: GeneralSecurityException) {
        } catch (_: java.io.IOException) {
        }
    }

    @Test
    fun retiredPairStillReadsAndIsReported() {
        val data = Random(11).nextBytes(5_000)
        val file = seal(data)
        val newPair = EcKeys.generate()
        val newKeys = VaultKeys(Recipient.fromEncoded(newPair.public.encoded), newPair.private, retired = listOf(keys))
        SealedFile(file, newKeys).use { sealed ->
            assertArrayEquals(data, sealed.readAll())
            assertArrayEquals(keys.recipient.encoded, sealed.sealedTo.encoded)
        }
        SealedFile(file, keys).use { assertArrayEquals(keys.recipient.encoded, it.sealedTo.encoded) }
        try {
            SealedFile(file, VaultKeys(newKeys.recipient, newKeys.privateKey)).use { it.readAll() }
            fail("a pair the file was not sealed to must not read it")
        } catch (_: GeneralSecurityException) {
        }
    }
}
