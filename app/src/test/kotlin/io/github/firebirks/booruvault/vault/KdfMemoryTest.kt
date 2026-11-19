package io.github.firebirks.booruvault.vault

import io.github.firebirks.booruvault.vault.crypto.Kdf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * BouncyCastle keeps all of Argon2's memory on the heap. A header altered to ask for more than the
 * phone gives the app, though no more than the ceiling allows, must make the unlock say the vault
 * cannot be opened, not end the app.
 */
class KdfMemoryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true }
    private val mib = 1024L * 1024

    @Test
    fun `what the app writes fits a small heap, the ceiling only a large one`() {
        val written = Kdf.newParams()
        assertTrue(Kdf.fits(written, maxHeap = 128 * mib))
        val ceiling = written.copy(memoryKiB = Kdf.MAX_MEMORY_KIB)
        assertFalse(Kdf.fits(ceiling, maxHeap = 256 * mib))
        assertTrue(Kdf.fits(ceiling, maxHeap = 512 * mib))
    }

    @Test
    fun `an unlock that runs out of memory says the vault cannot be opened`() = runBlocking {
        val root = tmp.newFolder()
        VaultManager(root, json).create("password".toCharArray(), Kdf.newParams(memoryKiB = 1024, iterations = 1))
        val starved = VaultManager(root, json, derive = { _, _ -> throw OutOfMemoryError("Argon2") })
        try {
            starved.unlock("password".toCharArray())
            fail("the unlock must not succeed, nor end the app")
        } catch (_: VaultHeaderException) {
        }
        assertEquals(VaultState.Locked, starved.state.value)
    }
}
