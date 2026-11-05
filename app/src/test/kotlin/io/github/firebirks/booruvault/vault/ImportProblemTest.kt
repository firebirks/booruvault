package io.github.firebirks.booruvault.vault

import io.github.firebirks.booruvault.util.Problem
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** An import that fails says which side failed: the file picked, or the storage the vault writes to. */
class ImportProblemTest {
    private class Unreadable(private val message: String) : InputStream() {
        override fun read(): Int = throw IOException(message)
    }

    private class Full(private val message: String) : OutputStream() {
        override fun write(b: Int): Unit = throw IOException(message)
    }

    private fun copying(input: InputStream, output: OutputStream): Problem = try {
        copyWiping(SourceStream(input), output)
        error("copied")
    } catch (e: IOException) {
        e.importProblem("picked.jpg")
    }

    @Test
    fun aFileThatCannotBeReadIsSaidToBeUnreadable() {
        assertEquals(Problem(Problem.Code.FILE_UNREADABLE, "Permission denied"), copying(Unreadable("Permission denied"), ByteArrayOutputStream()))
    }

    @Test
    fun aVaultThatCannotBeWrittenIsSaidToBeTheStorageFailing() {
        assertEquals(Problem(Problem.Code.STORAGE_WRITE, "No space left on device"), copying(ByteArrayInputStream(ByteArray(10)), Full("No space left on device")))
    }

    @Test
    fun aProblemOfItsOwnIsKept() {
        assertEquals(Problem(Problem.Code.TOO_LARGE), MediaTooLargeException().importProblem("picked.jpg"))
    }
}
