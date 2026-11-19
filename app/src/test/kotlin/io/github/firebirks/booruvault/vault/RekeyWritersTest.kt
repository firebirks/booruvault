package io.github.firebirks.booruvault.vault

import io.github.firebirks.booruvault.vault.crypto.FileLocks
import io.github.firebirks.booruvault.vault.crypto.Kdf
import io.github.firebirks.booruvault.vault.crypto.SealedBlob
import io.github.firebirks.booruvault.vault.crypto.SealedFile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.GeneralSecurityException

/**
 * After a password change the retired pair is dropped only once nothing it sealed is left: not
 * the settings, cookies or queue, whose saves read the key as they begin and may land late, and
 * not a file the app set aside as unreadable, which an old copy of the header could still read.
 */
class RekeyWritersTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val fastKdf get() = Kdf.newParams(memoryKiB = 1024, iterations = 1)

    @After
    fun tearDown() = scope.cancel()

    private suspend fun openVault(root: File): VaultManager =
        VaultManager(root, json).also { it.create("first".toCharArray(), fastKdf) }

    /** Opens [file] with the old header, as one copied before the change, and the old password. */
    private suspend fun readsWithTheOldHeader(root: File, oldHeader: ByteArray, file: File): Boolean {
        val stale = tmp.newFolder()
        File(stale, "vault.json").writeBytes(oldHeader)
        file.copyTo(File(stale, file.name))
        val staleVault = VaultManager(stale, json)
        assertTrue(staleVault.unlock("first".toCharArray()))
        return try {
            SealedFile(File(stale, file.name), staleVault.keys!!).use { it.readAll() }
            true
        } catch (_: GeneralSecurityException) {
            false
        }
    }

    @Test
    fun `a write counts from before it reads the key until its file is in place`() = runBlocking {
        val vault = openVault(tmp.newFolder())
        assertEquals(0, vault.writesInFlight)
        val seen = vault.sealing { recipient ->
            assertArrayEquals(vault.recipient!!.encoded, recipient.encoded)
            vault.writesInFlight
        }
        assertEquals(1, seen)
        assertEquals(0, vault.writesInFlight)
        runCatching { vault.sealing<Unit> { error("the storage refused it") } }
        assertEquals(0, vault.writesInFlight)
        assertNull("without a vault nothing is run", VaultManager(tmp.newFolder(), json).sealing { fail("ran"); 1 })
    }

    @Test
    fun `a settings save that read the old key before the change is waited for`() = runBlocking {
        val root = tmp.newFolder()
        val vault = openVault(root)
        VaultStore(root, vault, json, scope)
        VaultRekeyer(root, vault, scope)
        val config = File(root, "config.bin")
        val bytes = "{\"pageSize\":40}".toByteArray()
        val began = CompletableDeferred<Unit>()
        val land = CompletableDeferred<Unit>()
        // Read the key, then stalled in the storage longer than the re-keyer's passes and grace.
        val save = scope.launch(Dispatchers.IO) {
            vault.sealing { recipient ->
                began.complete(Unit)
                runBlocking { land.await() }
                SealedBlob.write(config, recipient, bytes)
            }
        }
        began.await()
        assertTrue(vault.changePassword("first".toCharArray(), "second".toCharArray()))
        delay(5_000)
        assertTrue("the old pair is kept while a write sealed to it may still land", vault.rekeyPending.value)
        land.complete(Unit)
        save.join()
        withTimeout(30_000) { vault.rekeyPending.first { !it } }
        // Re-sealed to the new pair, which alone the vault now has.
        assertArrayEquals(bytes, SealedBlob.read(config, vault.keys!!))
    }

    @Test
    fun `a file set aside as unreadable is re-sealed with the rest`() = runBlocking {
        val root = tmp.newFolder()
        val vault = openVault(root)
        VaultStore(root, vault, json, scope)
        VaultRekeyer(root, vault, scope)
        val setAside = File(root, "config.bin.corrupt")
        val bytes = "{\"apiKey\":\"secret\"}".toByteArray()
        SealedBlob.write(setAside, vault.recipient!!, bytes)
        val oldHeader = File(root, "vault.json").readBytes()

        assertTrue(vault.changePassword("first".toCharArray(), "second".toCharArray()))
        withTimeout(30_000) { vault.rekeyPending.first { !it } }

        assertArrayEquals(bytes, SealedBlob.read(setAside, vault.keys!!))
        assertFalse("an old copy of the header reads nothing of it", readsWithTheOldHeader(root, oldHeader, setAside))
    }

    @Test
    fun `a file set aside that no key opens goes with the old pair`() = runBlocking {
        val root = tmp.newFolder()
        val vault = openVault(root)
        VaultStore(root, vault, json, scope)
        VaultRekeyer(root, vault, scope)
        val elsewhere = openVault(tmp.newFolder())
        val setAside = File(root, "cookies.bin.corrupt")
        SealedBlob.write(setAside, elsewhere.recipient!!, "{}".toByteArray())

        assertTrue(vault.changePassword("first".toCharArray(), "second".toCharArray()))
        withTimeout(60_000) { vault.rekeyPending.first { !it } }

        assertFalse(setAside.exists())
    }

    @Test
    fun `a file and its lock are the same whoever asks`() {
        val file = File(tmp.root, "config.bin")
        assertTrue(FileLocks.of(file) === FileLocks.of(File(tmp.root.path + "/config.bin")))
    }
}
