package io.github.firebirks.booruvault.vault

import io.github.firebirks.booruvault.vault.crypto.EcKeys
import io.github.firebirks.booruvault.vault.crypto.Recipient
import io.github.firebirks.booruvault.vault.crypto.VaultKeys
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.PrivateKey
import javax.security.auth.Destroyable

/** A lock asks every private key the vault holds to wipe itself, the retired ones too. */
class DestroyKeysTest {
    /** A key that can be destroyed, unlike the platform's EC keys, so the asking shows. */
    private class WipeableKey : PrivateKey, Destroyable {
        var wiped = false
        override fun destroy() {
            wiped = true
        }
        override fun isDestroyed() = wiped
        override fun getAlgorithm() = "EC"
        override fun getFormat() = "PKCS#8"
        override fun getEncoded() = ByteArray(0)
    }

    /** A key whose provider cannot wipe it, as the platform's are. */
    private class StubbornKey : PrivateKey {
        override fun getAlgorithm() = "EC"
        override fun getFormat() = "PKCS#8"
        override fun getEncoded() = ByteArray(0)
    }

    private val recipient = Recipient.fromEncoded(EcKeys.generate().public.encoded)

    @Test
    fun theCurrentKeyAndEveryRetiredOneAreAskedToWipeThemselves() {
        val current = WipeableKey()
        val retired = listOf(WipeableKey(), WipeableKey())
        val keys = VaultKeys(recipient, current, retired.map { VaultKeys(recipient, it) })

        keys.destroyPrivateKeys()

        assertTrue(current.wiped)
        retired.forEach { assertTrue(it.wiped) }
    }

    @Test
    fun aKeyThatCannotBeWipedStopsNoneOfTheOthers() {
        val last = WipeableKey()
        val keys = VaultKeys(recipient, StubbornKey(), listOf(VaultKeys(recipient, StubbornKey()), VaultKeys(recipient, last)))

        keys.destroyPrivateKeys()

        assertTrue(last.wiped)
        assertFalse(keys.privateKey.isDestroyed)
    }
}
