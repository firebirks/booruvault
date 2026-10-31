package io.github.firebirks.booruvault.vault

import io.github.firebirks.booruvault.vault.crypto.EcKeys
import io.github.firebirks.booruvault.vault.crypto.Recipient
import io.github.firebirks.booruvault.vault.crypto.VaultKeys
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A lock that finds the screen stopped closes it; one the user watches happen does not. */
class CloseOnLockTest {
    private val unlocked = EcKeys.generate().let { VaultState.Unlocked(VaultKeys(Recipient.fromEncoded(it.public.encoded), it.private)) }

    @Test
    fun aLockWhileTheScreenIsStoppedClosesIt() {
        assertTrue(closesOnLock(was = unlocked, now = VaultState.Locked, started = false))
    }

    @Test
    fun aLockInFrontOfTheUserLeavesTheScreenToShowTheLock() {
        assertFalse(closesOnLock(was = unlocked, now = VaultState.Locked, started = true))
    }

    @Test
    fun aScreenThatHasNotSeenTheVaultOpenIsLeftAlone() {
        assertFalse(closesOnLock(was = null, now = VaultState.Locked, started = false))
        assertFalse(closesOnLock(was = VaultState.Locked, now = VaultState.Locked, started = false))
        assertFalse(closesOnLock(was = VaultState.Locked, now = unlocked, started = false))
        assertFalse(closesOnLock(was = unlocked, now = unlocked, started = false))
    }
}
