package io.github.firebirks.booruvault.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FilesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun aFileMovedOverAnotherReplacesIt() {
        val target = tmp.newFile("header.json").apply { writeText("old") }
        val written = tmp.newFile("header.json.tmp").apply { writeText("new") }

        assertTrue(written.moveOver(target))
        assertEquals("new", target.readText())
        assertFalse(written.exists())
    }

    @Test
    fun aFileThatIsNotThereIsNotMoved() {
        val target = tmp.newFile("header.json").apply { writeText("old") }

        assertFalse(File(tmp.root, "missing.tmp").moveOver(target))
        assertEquals("old", target.readText())
    }
}
