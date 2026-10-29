package io.github.firebirks.booruvault.vault

import io.github.firebirks.booruvault.vault.crypto.EcKeys
import io.github.firebirks.booruvault.vault.crypto.Recipient
import io.github.firebirks.booruvault.vault.crypto.SealedBlob
import io.github.firebirks.booruvault.vault.crypto.VaultKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Collections
import kotlin.concurrent.thread

class SealedBlobTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val pair = EcKeys.generate()
    private val keys = VaultKeys(Recipient.fromEncoded(pair.public.encoded), pair.private)

    @Test
    fun writesRacingForOneFileLeaveItWhole() {
        // As a save of the cookie jar does when the vault locks while another save is running.
        val file = File(tmp.root, "cookies.bin")
        val contents = List(4) { i -> ByteArray(200_000) { i.toByte() } }
        val failures = Collections.synchronizedList(mutableListOf<Throwable>())
        contents.map { bytes ->
            thread {
                repeat(25) {
                    try {
                        SealedBlob.write(file, keys.recipient, bytes)
                    } catch (e: Exception) {
                        failures += e
                    }
                }
            }
        }.forEach { it.join() }

        assertEquals(emptyList<Throwable>(), failures)
        val read = SealedBlob.read(file, keys)!!
        assertTrue(contents.any { it.contentEquals(read) })
    }
}
