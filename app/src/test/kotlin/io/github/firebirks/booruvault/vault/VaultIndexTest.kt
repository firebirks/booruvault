package io.github.firebirks.booruvault.vault

import io.github.firebirks.booruvault.vault.crypto.Kdf
import io.github.firebirks.booruvault.vault.crypto.SealedFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.random.Random

/**
 * The index is the whole list in one file, so it is written when changes have settled and not
 * for each of them. It may therefore lag behind the vault, at a lock or when the process ends,
 * and must cost nothing when it does: the meta files say what the vault holds, and an unlock
 * brings the list, and then the index, in line with them.
 */
class VaultIndexTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val fastKdf get() = Kdf.newParams(memoryKiB = 1024, iterations = 1)
    private val password = "correct horse"

    @After
    fun tearDown() = scope.cancel()

    /** The app over a vault folder, as one run of its process; [quietMs] and [maxWaitMs] are the index's waits. */
    private inner class Run(val root: File, quietMs: Long, maxWaitMs: Long = 600_000) {
        val vault = VaultManager(root, json)
        val store = VaultStore(root, vault, json, scope, indexQuietMs = quietMs, indexMaxWaitMs = maxWaitMs)

        suspend fun add(name: String): String {
            val id = store.newId()
            val size = store.writeMedia(id) { it.write(Random(name.hashCode()).nextBytes(300)) }
            store.commit(VaultItem(id = id, addedAt = System.currentTimeMillis(), fileName = name, mimeType = "image/png", size = size))
            return id
        }

        suspend fun awaitItems(count: Int) = withTimeout(10_000) { store.items.first { it.size == count } }

        suspend fun awaitWrites(count: Int) = withTimeout(10_000) { while (store.indexWrites < count) delay(20) }

        /** The names the index on storage lists, or null when there is no index. */
        fun indexed(): Set<String>? {
            val file = File(root, "index.bin").takeIf { it.exists() } ?: return null
            val bytes = SealedFile(file, vault.keys!!).use { it.readAll() }
            return json.decodeFromString(ListSerializer(VaultItem.serializer()), bytes.decodeToString()).map { it.fileName }.toSet()
        }
    }

    private val never = 600_000L

    @Test
    fun `a run of new items is listed at once and written to the index once, when it has settled`() = runBlocking {
        val run = Run(tmp.newFolder(), quietMs = 2_000)
        run.vault.create(password.toCharArray(), fastKdf)
        // Spread over several passes of the list, each of which would have rewritten the index.
        repeat(12) {
            run.add("f$it")
            delay(150)
        }
        run.awaitItems(12)
        assertEquals(0, run.store.indexWrites)
        run.awaitWrites(1)
        delay(500)
        assertEquals(1, run.store.indexWrites)
        assertEquals((0 until 12).map { "f$it" }.toSet(), run.indexed())
    }

    @Test
    fun `changes that never stop coming do not keep the index waiting past its longest wait`() = runBlocking {
        val run = Run(tmp.newFolder(), quietMs = never, maxWaitMs = 1_500)
        run.vault.create(password.toCharArray(), fastKdf)
        withTimeout(15_000) {
            var n = 0
            while (run.store.indexWrites == 0) {
                run.add("f${n++}")
                delay(200)
            }
        }
        assertTrue(run.indexed()!!.isNotEmpty())
    }

    @Test
    fun `an index left behind when the process ends is caught up at the next unlock`() = runBlocking {
        val root = tmp.newFolder()
        val first = Run(root, quietMs = 0)
        first.vault.create(password.toCharArray(), fastKdf)
        val a = first.add("a")
        first.add("b")
        first.awaitItems(2)
        withTimeout(10_000) { while (first.indexed() != setOf("a", "b")) delay(20) }
        first.vault.lock()

        // A run that adds and deletes, and ends before its index is ever written.
        val second = Run(root, quietMs = never)
        assertTrue(second.vault.unlock(password.toCharArray()))
        second.awaitItems(2)
        second.add("c")
        second.add("d")
        second.awaitItems(4)
        second.store.delete(setOf(a))
        assertEquals(setOf("b", "c", "d"), second.store.items.value.map { it.fileName }.toSet())
        assertEquals(0, second.store.indexWrites)
        assertEquals(setOf("a", "b"), second.indexed())

        // The next one finds an index that lists a deleted item and misses two: the list is
        // what the meta files say, and the index is written to match as the vault opens.
        val third = Run(root, quietMs = never)
        assertTrue(third.vault.unlock(password.toCharArray()))
        third.awaitItems(3)
        assertEquals(setOf("b", "c", "d"), third.store.items.value.map { it.fileName }.toSet())
        third.awaitWrites(1)
        assertEquals(setOf("b", "c", "d"), third.indexed())
        // An index that is in line is left alone.
        third.vault.lock()
        val fourth = Run(root, quietMs = never)
        assertTrue(fourth.vault.unlock(password.toCharArray()))
        fourth.awaitItems(3)
        delay(300)
        assertEquals(0, fourth.store.indexWrites)
    }

    @Test
    fun `a lock writes nothing and a write that was waiting does not take the emptied list for the vault's`() = runBlocking {
        val root = tmp.newFolder()
        val run = Run(root, quietMs = 1_000)
        run.vault.create(password.toCharArray(), fastKdf)
        repeat(3) { run.add("f$it") }
        run.awaitItems(3)
        run.vault.lock()
        withTimeout(5000) { run.store.items.first { it.isEmpty() } }
        // Well past the wait the index was owed after.
        delay(2_000)
        assertEquals(0, run.store.indexWrites)
        assertFalse(File(root, "index.bin").exists())
        assertTrue(run.vault.unlock(password.toCharArray()))
        run.awaitItems(3)
        run.awaitWrites(1)
        assertEquals(setOf("f0", "f1", "f2"), run.indexed())
    }

    @Test
    fun `a password change writes the index first and keeps the list`() = runBlocking {
        val run = Run(tmp.newFolder(), quietMs = never)
        run.vault.create(password.toCharArray(), fastKdf)
        repeat(3) { run.add("f$it") }
        run.awaitItems(3)
        assertEquals(0, run.store.indexWrites)
        assertTrue(run.vault.changePassword(password.toCharArray(), "second password".toCharArray()))
        // Sealed to the new pair before the vault is read anew under it, so it is read whole.
        run.awaitWrites(1)
        assertEquals(setOf("f0", "f1", "f2"), run.indexed())
        assertEquals(3, run.store.items.value.size)
    }
}
