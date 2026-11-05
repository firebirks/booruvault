package io.github.firebirks.booruvault.vault

import io.github.firebirks.booruvault.vault.crypto.Kdf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** A lock asked for while the password is still being turned into the key holds: the vault does not open after it. */
class LockWhileUnlockingTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true }
    private val fastKdf get() = Kdf.newParams(memoryKiB = 1024, iterations = 1)

    @Test
    fun aLockAskedForWhileTheKeyIsDerivedLeavesTheVaultLocked() = runBlocking {
        val deriving = CountDownLatch(1)
        val locked = CountDownLatch(1)
        var slow = false
        val vault = VaultManager(tmp.newFolder(), json, derive = { password, params ->
            if (slow) {
                deriving.countDown()
                locked.await(10, TimeUnit.SECONDS)
            }
            Kdf.derive(password, params)
        })
        vault.create("pw-123456".toCharArray(), fastKdf)
        vault.lock()
        assertEquals(VaultState.Locked, vault.state.value)

        slow = true
        val unlock = async(Dispatchers.Default) { vault.unlock("pw-123456".toCharArray()) }
        assertTrue(deriving.await(10, TimeUnit.SECONDS))
        // The auto-lock fires, the app gone to the background, with the derivation still under way.
        vault.lock()
        locked.countDown()
        // The password was right, so there is no wrong password to report; the vault stays locked all the same.
        assertTrue(withTimeout(10_000) { unlock.await() })
        assertEquals(VaultState.Locked, vault.state.value)
    }
}
