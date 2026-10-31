package io.github.firebirks.booruvault.vault

import io.github.firebirks.booruvault.vault.crypto.EcKeys
import io.github.firebirks.booruvault.vault.crypto.Recipient
import io.github.firebirks.booruvault.vault.crypto.VaultKeys
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class SealedJsonTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val pair = EcKeys.generate()
    private val keys = VaultKeys(Recipient.fromEncoded(pair.public.encoded), pair.private)

    @Test
    fun ofTwoSavesRacingTheCopyTakenLaterIsLeft() {
        // As the cookie jar's queued save and the one made on lock do.
        val store = SealedJson(File(tmp.root, "cookies.bin"), String.serializer(), Json)
        var state = "old"
        val taken = CountDownLatch(1)
        val overtaken = CountDownLatch(1)
        val first = thread {
            store.write(keys.recipient) {
                val copy = state
                taken.countDown()
                // Left behind while the other save runs, unless it is held back.
                overtaken.await(500, TimeUnit.MILLISECONDS)
                copy
            }
        }
        taken.await()
        state = "new"
        store.write(keys.recipient) { state }
        overtaken.countDown()
        first.join()
        assertEquals("new", store.read(keys))
    }
}
