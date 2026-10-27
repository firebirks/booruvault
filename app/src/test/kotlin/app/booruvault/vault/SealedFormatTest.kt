package app.booruvault.vault

import app.booruvault.vault.crypto.EcKeys
import app.booruvault.vault.crypto.Recipient
import app.booruvault.vault.crypto.SealedFile
import app.booruvault.vault.crypto.SealedOutputStream
import app.booruvault.vault.crypto.VaultKeys
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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

    private fun seal(data: ByteArray, chunk: Int = 1024): File {
        val file = tmp.newFile()
        SealedOutputStream(file.outputStream(), keys.recipient, chunk).use { out ->
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
        file.writeBytes(bytes.copyOf(bytes.size - (1024 + 16)))
        expectFailure { SealedFile(file, keys).use { it.readAll() } }
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
}
