package app.booruvault.vault

import app.booruvault.vault.crypto.Kdf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.random.Random

class VaultTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val fastKdf get() = Kdf.newParams(memoryKiB = 1024, iterations = 1)

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun passwordUnlocksAndWrongPasswordDoesNot() = runBlocking {
        val root = tmp.newFolder()
        val vault = VaultManager(root, json)
        vault.create("correct horse".toCharArray(), fastKdf)
        assertTrue(vault.state.value is VaultState.Unlocked)
        vault.lock()

        val reopened = VaultManager(root, json)
        assertTrue(reopened.state.value is VaultState.Locked)
        assertFalse(reopened.unlock("wrong".toCharArray()))
        assertTrue(reopened.unlock("correct horse".toCharArray()))
    }

    @Test
    fun changePasswordKeepsContent() = runBlocking {
        val root = tmp.newFolder()
        val vault = VaultManager(root, json)
        vault.create("first".toCharArray(), fastKdf)
        val store = VaultStore(root, vault, json, scope)
        val data = Random(1).nextBytes(10_000)
        add(store, data, "a")
        awaitItems(store, 1)

        assertFalse(vault.changePassword("nope".toCharArray(), "second".toCharArray()))
        assertTrue(vault.changePassword("first".toCharArray(), "second".toCharArray()))
        vault.lock()
        assertFalse(vault.unlock("first".toCharArray()))
        assertTrue(vault.unlock("second".toCharArray()))
        awaitItems(store, 1)
        assertArrayEquals(data, store.openMedia(store.items.value.single().id).use { it.readAll() })
    }

    @Test
    fun itemsWrittenWhileLockedAppearOnUnlock() = runBlocking {
        val root = tmp.newFolder()
        val vault = VaultManager(root, json)
        vault.create("pw".toCharArray(), fastKdf)
        val store = VaultStore(root, vault, json, scope)
        vault.lock()
        withTimeout(5000) { store.items.first { it.isEmpty() } }

        val data = Random(2).nextBytes(70_000)
        add(store, data, "locked")
        // Nothing readable while locked, and the media is not stored in clear.
        assertTrue(store.items.value.isEmpty())
        val stored = File(root, "media").listFiles()!!.single().readBytes()
        assertFalse(stored.toList().windowed(32).any { it == data.copyOfRange(100, 132).toList() })

        assertTrue(vault.unlock("pw".toCharArray()))
        awaitItems(store, 1)
        val item = store.items.value.single()
        assertEquals("locked", item.fileName)
        assertArrayEquals(data, store.openMedia(item.id).use { it.readAll() })
    }

    @Test
    fun indexSurvivesRestartAndIsRebuiltWhenLost() = runBlocking {
        val root = tmp.newFolder()
        val vault = VaultManager(root, json)
        vault.create("pw".toCharArray(), fastKdf)
        val store = VaultStore(root, vault, json, scope)
        repeat(3) { add(store, Random(it).nextBytes(500), "f$it") }
        awaitItems(store, 3)

        File(root, "index.bin").delete()
        val restarted = VaultManager(root, json)
        val restartedStore = VaultStore(root, restarted, json, scope)
        assertTrue(restarted.unlock("pw".toCharArray()))
        awaitItems(restartedStore, 3)
        // The list is published first and the rebuilt index written right after.
        withTimeout(5000) { while (!File(root, "index.bin").exists()) kotlinx.coroutines.delay(20) }
    }

    @Test
    fun duplicatesFromTheSamePostAreDropped() = runBlocking {
        val root = tmp.newFolder()
        val vault = VaultManager(root, json)
        vault.create("pw".toCharArray(), fastKdf)
        val store = VaultStore(root, vault, json, scope)
        vault.lock()
        add(store, byteArrayOf(1, 2, 3), "one", postId = 42)
        add(store, byteArrayOf(1, 2, 3), "two", postId = 42)
        assertTrue(vault.unlock("pw".toCharArray()))
        awaitItems(store, 1)
        assertEquals(1, File(root, "media").listFiles()!!.size)
    }

    @Test
    fun deleteRemovesFiles() = runBlocking {
        val root = tmp.newFolder()
        val vault = VaultManager(root, json)
        vault.create("pw".toCharArray(), fastKdf)
        val store = VaultStore(root, vault, json, scope)
        add(store, byteArrayOf(9), "x")
        awaitItems(store, 1)
        store.delete(setOf(store.items.value.single().id))
        assertTrue(store.items.value.isEmpty())
        assertTrue(File(root, "media").listFiles()!!.isEmpty())
        assertTrue(File(root, "meta").listFiles()!!.isEmpty())
    }

    private suspend fun add(store: VaultStore, data: ByteArray, name: String, postId: Long? = null) {
        val id = store.newId()
        val size = store.writeMedia(id) { it.write(data) }
        store.commit(
            VaultItem(
                id = id, addedAt = System.currentTimeMillis(), fileName = name, mimeType = "image/png", size = size,
                sourceId = postId?.let { "test" }, postId = postId,
            ),
        )
    }

    private suspend fun awaitItems(store: VaultStore, count: Int) {
        withTimeout(10_000) { store.items.first { it.size == count } }
    }
}
