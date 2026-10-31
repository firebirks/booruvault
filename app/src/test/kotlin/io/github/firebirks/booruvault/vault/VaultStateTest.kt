package io.github.firebirks.booruvault.vault

import io.github.firebirks.booruvault.vault.crypto.EcKeys
import io.github.firebirks.booruvault.vault.crypto.Recipient
import io.github.firebirks.booruvault.vault.crypto.VaultKeys
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

/** Keys swapped in by a password change or the end of re-keying never reopen a vault that locked. */
class VaultStateTest {
    private fun open() = EcKeys.generate().let { VaultState.Unlocked(VaultKeys(Recipient.fromEncoded(it.public.encoded), it.private)) }

    @Test
    fun keysSwappedInWhileTheVaultLocksLeaveItLocked() {
        val state = MutableStateFlow<VaultState>(open())
        val replacement = open()
        val making = CountDownLatch(1)
        val locked = CountDownLatch(1)
        val swap = thread {
            state.replaceKeys {
                making.countDown()
                locked.await()
                replacement
            }
        }
        making.await()
        state.close()
        locked.countDown()
        swap.join()
        assertEquals(VaultState.Locked, state.value)
    }

    @Test
    fun aLockedVaultGetsNoKeys() {
        val state = MutableStateFlow<VaultState>(VaultState.Locked)
        assertNull(state.replaceKeys { open() })
        assertEquals(VaultState.Locked, state.value)
    }
}
