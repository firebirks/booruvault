package io.github.firebirks.booruvault.download

import io.github.firebirks.booruvault.util.Problem
import io.github.firebirks.booruvault.util.toProblem
import io.github.firebirks.booruvault.vault.MediaTooLargeException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * A transfer cut by the network is taken up where it stopped; one the phone's storage refused
 * is not. The two must not be taken for each other: the rest asked of the host would be sealed
 * after a stream that may already hold part of the piece the storage refused.
 */
class ReceiveTest {
    private val file = ByteArray(200_000) { (it * 31).toByte() }

    /** Takes [allowed] bytes, then fails every write as [failure] says. */
    private class FailingOutput(private val allowed: Int, private val failure: () -> IOException) : OutputStream() {
        var written = 0

        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (written + len > allowed) throw failure()
            written += len
        }
    }

    @Test
    fun `the whole input is copied, and the end is no failure`() = runBlocking {
        val out = ByteArrayOutputStream()
        var counted = 0
        assertNull(receive(ByteArrayInputStream(file), out) { counted += it })
        assertArrayEquals(file, out.toByteArray())
        assertEquals(file.size, counted)
    }

    @Test
    fun `a read cut short is returned, with what came before it counted`() = runBlocking {
        val cut = IOException("unexpected end of stream")
        val input = object : InputStream() {
            var given = 0

            override fun read(): Int = throw UnsupportedOperationException()

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (given >= 100_000) throw cut
                val n = minOf(len, 100_000 - given)
                given += n
                return n
            }
        }
        var counted = 0
        assertSame(cut, receive(input, ByteArrayOutputStream()) { counted += it })
        assertEquals(100_000, counted)
    }

    @Test
    fun `a write the storage refuses is thrown as such, not returned as a cut`() = runBlocking {
        val out = FailingOutput(allowed = 70_000) { IOException("No space left on device") }
        var counted = 0
        val thrown = runCatching { receive(ByteArrayInputStream(file), out) { counted += it } }.exceptionOrNull()

        assertTrue(thrown is StorageWriteException)
        val problem = thrown!!.toProblem()
        assertEquals(Problem.Code.STORAGE_WRITE, problem.code)
        assertEquals("No space left on device", problem.detail)
        // Only what was written is counted: the piece refused is not.
        assertEquals(out.written, counted)
    }

    @Test
    fun `a write failure that says what it is keeps its own word`() = runBlocking {
        val tooLarge = MediaTooLargeException()
        val thrown = runCatching { receive(ByteArrayInputStream(file), FailingOutput(allowed = 0) { tooLarge }) {} }.exceptionOrNull()
        assertSame(tooLarge, thrown)
        assertEquals(Problem.Code.TOO_LARGE, thrown!!.toProblem().code)
    }
}
