package io.github.firebirks.booruvault.vault

import io.github.firebirks.booruvault.vault.crypto.Kdf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * An item is its meta file: media and a thumbnail that no meta file names were left by a
 * process that ended between writing a file and committing it, as a download does while it
 * fetches the preview, and no one would ever take them away. The next start of the app does.
 */
class UncommittedFilesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val fastKdf get() = Kdf.newParams(memoryKiB = 1024, iterations = 1)
    private val password = "correct horse"

    @After
    fun tearDown() = scope.cancel()

    /** The app over a vault folder, as one run of its process. */
    private inner class Run(val root: File) {
        val vault = VaultManager(root, json)
        val store = VaultStore(root, vault, json, scope)

        /** Writes an item's file and thumbnail, and commits it only if [commit]. */
        suspend fun write(commit: Boolean): String {
            val id = store.newId()
            val size = store.writeMedia(id) { it.write(ByteArray(300) { n -> n.toByte() }) }
            store.writeThumbnail(id, ByteArray(100))
            if (commit) {
                store.commit(VaultItem(id = id, addedAt = System.currentTimeMillis(), fileName = "$id.png", mimeType = "image/png", size = size, hasThumbnail = true))
            }
            return id
        }

        /** Unlocks, and returns once the list is read: by then the start's sweep is over. */
        suspend fun open(count: Int): List<VaultItem> {
            assertTrue(vault.unlock(password.toCharArray()))
            return withTimeout(10_000) { store.items.first { it.size == count } }
        }

        fun media(id: String) = File(root, "media/$id")
        fun thumb(id: String) = File(root, "thumbs/$id")
    }

    @Test
    fun `the files of an item never committed are gone at the next start`() = runBlocking {
        val root = tmp.newFolder()
        val first = Run(root)
        first.vault.create(password.toCharArray(), fastKdf)
        val kept = first.write(commit = true)
        val left = first.write(commit = false)
        withTimeout(10_000) { first.store.items.first { it.size == 1 } }
        assertTrue(first.media(left).isFile)
        assertTrue(first.thumb(left).isFile)

        val next = Run(root)
        assertEquals(listOf(kept), next.open(1).map { it.id })
        assertFalse(next.media(left).exists())
        assertFalse(next.thumb(left).exists())
        assertTrue(next.media(kept).isFile)
        assertTrue(next.thumb(kept).isFile)
    }

    @Test
    fun `a deletion cut short after the meta file went leaves nothing behind at the next start`() = runBlocking {
        val root = tmp.newFolder()
        val first = Run(root)
        first.vault.create(password.toCharArray(), fastKdf)
        val gone = first.write(commit = true)
        withTimeout(10_000) { first.store.items.first { it.size == 1 } }
        // What a deletion does first, and all a process that ended right after did.
        assertTrue(File(root, "meta/$gone").delete())

        val next = Run(root)
        next.open(0)
        assertFalse(next.media(gone).exists())
        assertFalse(next.thumb(gone).exists())
    }

    @Test
    fun `nothing is taken away when the meta files cannot be listed`() = runBlocking {
        val root = tmp.newFolder()
        val first = Run(root)
        first.vault.create(password.toCharArray(), fastKdf)
        val kept = first.write(commit = true)
        withTimeout(10_000) { first.store.items.first { it.size == 1 } }
        val meta = File(root, "meta")
        meta.setReadable(false)
        try {
            // Root reads any folder: there the listing works, and the case cannot be made.
            assumeTrue(meta.list() == null)
            val next = Run(root)
            // The start's sweep is over once a write, which waits for it, is.
            next.store.writeMedia(next.store.newId()) { it.write(1) }
            assertTrue(next.media(kept).isFile)
            assertTrue(next.thumb(kept).isFile)
        } finally {
            meta.setReadable(true)
        }
    }
}
