package io.github.firebirks.booruvault.vault

import io.github.firebirks.booruvault.vault.crypto.Kdf
import io.github.firebirks.booruvault.vault.crypto.SealedFile
import io.github.firebirks.booruvault.vault.crypto.SealedOutputStream
import io.github.firebirks.booruvault.vault.crypto.toBase64
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
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
    fun changePasswordReplacesTheKeyPair() = runBlocking {
        val root = tmp.newFolder()
        val vault = VaultManager(root, json)
        vault.create("first".toCharArray(), fastKdf)
        val store = VaultStore(root, vault, json, scope)
        val rekeyer = VaultRekeyer(root, vault, store, scope)
        val data = Random(1).nextBytes(10_000)
        add(store, data, "a")
        awaitItems(store, 1)
        val oldHeader = File(root, "vault.json").readBytes()

        assertFalse(vault.changePassword("nope".toCharArray(), "second".toCharArray()))
        assertTrue(vault.changePassword("first".toCharArray(), "second".toCharArray()))
        assertTrue(vault.rekeyPending.value)
        // Readable throughout, first through the retired pair and then through the new one.
        assertArrayEquals(data, store.openMedia(store.items.value.single().id).use { it.readAll() })
        withTimeout(20_000) { vault.rekeyPending.first { !it } }
        assertFalse(File(root, "vault.json").readText().contains("boxedPrivateKey"))
        vault.lock()
        assertFalse(vault.unlock("first".toCharArray()))
        assertTrue(vault.unlock("second".toCharArray()))
        awaitItems(store, 1)
        val item = store.items.value.single()
        assertArrayEquals(data, store.openMedia(item.id).use { it.readAll() })
        assertEquals(1, File(root, "media").listFiles()!!.size)

        // An old copy of the header with the old password opens, but reads nothing.
        val stale = tmp.newFolder()
        root.copyRecursively(stale)
        File(stale, "vault.json").writeBytes(oldHeader)
        val staleVault = VaultManager(stale, json)
        assertTrue(staleVault.unlock("first".toCharArray()))
        try {
            SealedFile(File(stale, "media/${item.id}"), staleVault.keys!!).use { it.readAll() }
            fail("the re-sealed file must not open with the retired key")
        } catch (_: java.security.GeneralSecurityException) {
        }
        Unit
    }

    @Test
    fun aWriteBegunUnderTheOldPairThatLandsDuringAPassIsResealed() = runBlocking {
        val root = tmp.newFolder()
        val vault = VaultManager(root, json)
        vault.create("first".toCharArray(), fastKdf)
        val store = VaultStore(root, vault, json, scope)
        val rekeyer = VaultRekeyer(root, vault, store, scope)
        // Enough files for a pass to take a while, so that a write can land while one runs.
        val media = File(root, "media").apply { mkdirs() }
        repeat(800) { n -> SealedOutputStream(File(media, store.newId()).outputStream(), vault.recipient!!).use { it.write(n) } }
        val data = Random(5).nextBytes(10_000)
        val id = store.newId()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        // A download that read the recipient before the password change.
        val download = scope.launch {
            store.writeMedia(id) { out ->
                started.complete(Unit)
                release.await()
                out.write(data)
            }
        }
        started.await()
        assertTrue(vault.changePassword("first".toCharArray(), "second".toCharArray()))
        // The first pass re-seals the files and the second finds nothing to do; the write lands in
        // the middle of the third, after its listing, and that pass would be the second clean one.
        var passes = 0
        var last = -1
        withTimeout(60_000) {
            rekeyer.progress.first { p ->
                if (p != null && p.done < last) passes++
                last = p?.done ?: last
                p != null && passes >= 2 && p.done in p.total / 5..p.total * 3 / 5
            }
        }
        release.complete(Unit)
        download.join()
        withTimeout(60_000) { vault.rekeyPending.first { !it } }
        SealedFile(File(media, id), vault.keys!!).use { assertArrayEquals(data, it.readAll()) }
    }

    @Test
    fun rekeyingResumesAfterRestart() = runBlocking {
        val root = tmp.newFolder()
        val vault = VaultManager(root, json)
        vault.create("first".toCharArray(), fastKdf)
        val store = VaultStore(root, vault, json, scope)
        val contents = List(3) { Random(it).nextBytes(3_000) }
        contents.forEachIndexed { i, data -> add(store, data, "f$i") }
        awaitItems(store, 3)
        // No rekeyer here: the change is recorded, nothing is re-sealed, and the app "restarts".
        assertTrue(vault.changePassword("first".toCharArray(), "second".toCharArray()))
        vault.lock()

        val restarted = VaultManager(root, json)
        assertTrue(restarted.rekeyPending.value)
        val restartedStore = VaultStore(root, restarted, json, scope)
        val rekeyer = VaultRekeyer(root, restarted, restartedStore, scope)
        assertTrue(restarted.unlock("second".toCharArray()))
        awaitItems(restartedStore, 3)
        withTimeout(20_000) { restarted.rekeyPending.first { !it } }
        restarted.lock()

        // Everything reads with the new pair alone.
        val fresh = VaultManager(root, json)
        assertFalse(fresh.rekeyPending.value)
        val freshStore = VaultStore(root, fresh, json, scope)
        assertTrue(fresh.unlock("second".toCharArray()))
        awaitItems(freshStore, 3)
        assertTrue(fresh.keys!!.retired.isEmpty())
        freshStore.items.value.sortedBy { it.fileName }.forEachIndexed { i, item ->
            assertArrayEquals(contents[i], freshStore.openMedia(item.id).use { it.readAll() })
        }
    }

    @Test
    fun aFileThatCannotBeRewrittenKeepsTheRetiredPair() = runBlocking {
        val root = tmp.newFolder()
        val vault = VaultManager(root, json)
        vault.create("first".toCharArray(), fastKdf)
        val store = VaultStore(root, vault, json, scope)
        VaultRekeyer(root, vault, store, scope)
        val data = Random(2).nextBytes(10_000)
        add(store, data, "a")
        awaitItems(store, 1)
        // Something in the way of the new copy, as a full disk would be.
        val media = File(root, "media").listFiles()!!.single()
        val obstacle = File(media.path + ".rekey").apply { mkdir() }
        File(obstacle, "x").writeText("")

        assertTrue(vault.changePassword("first".toCharArray(), "second".toCharArray()))
        // Long past the five failed passes after which an unreadable file is given up.
        assertNull(withTimeoutOrNull(8_000) { vault.rekeyPending.first { !it } })
        assertArrayEquals(data, store.openMedia(store.items.value.single().id).use { it.readAll() })

        obstacle.deleteRecursively()
        withTimeout(20_000) { vault.rekeyPending.first { !it } }
        vault.lock()
        assertTrue(vault.unlock("second".toCharArray()))
        awaitItems(store, 1)
        assertArrayEquals(data, store.openMedia(store.items.value.single().id).use { it.readAll() })
    }

    @Test
    fun aRetireMeantForAPairReplacedMeanwhileDropsNothing() = runBlocking {
        val root = tmp.newFolder()
        val vault = VaultManager(root, json)
        vault.create("first".toCharArray(), fastKdf)
        val store = VaultStore(root, vault, json, scope)
        assertTrue(vault.changePassword("first".toCharArray(), "second".toCharArray()))
        val second = vault.keys!!.recipient
        val data = Random(4).nextBytes(10_000)
        add(store, data, "a")
        awaitItems(store, 1)

        // The first change's rekeyer re-sealed everything to the second pair and was waiting for
        // the vault while a second change retired that pair too; it gets the vault after it.
        assertTrue(vault.changePassword("second".toCharArray(), "third".toCharArray()))
        vault.retireFinished(second)

        assertTrue(vault.rekeyPending.value)
        vault.lock()
        assertTrue(vault.unlock("third".toCharArray()))
        awaitItems(store, 1)
        assertArrayEquals(data, store.openMedia(store.items.value.single().id).use { it.readAll() })
    }

    @Test
    fun aFileDamagedPastItsFirstChunkIsGivenUp() = runBlocking {
        val root = tmp.newFolder()
        val vault = VaultManager(root, json)
        vault.create("first".toCharArray(), fastKdf)
        val store = VaultStore(root, vault, json, scope)
        VaultRekeyer(root, vault, store, scope)
        add(store, Random(3).nextBytes(200_000), "a")
        awaitItems(store, 1)
        // It opens, as its first chunk is sound, but cannot be read to the end under any key.
        val media = File(root, "media").listFiles()!!.single()
        val bytes = media.readBytes()
        bytes[bytes.size - 100] = (bytes[bytes.size - 100].toInt() xor 1).toByte()
        media.writeBytes(bytes)

        assertTrue(vault.changePassword("first".toCharArray(), "second".toCharArray()))
        withTimeout(20_000) { vault.rekeyPending.first { !it } }
        Unit
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

    @Test
    fun biometricCopyIsReportedAndUnlocks() = runBlocking {
        val root = tmp.newFolder()
        val vault = VaultManager(root, json)
        vault.create("pw".toCharArray(), fastKdf)
        assertFalse(vault.biometric.value)
        // A plain AES key stands in for the Keystore one, which only exists on a device.
        val key = javax.crypto.KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val encrypt = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding").apply { init(javax.crypto.Cipher.ENCRYPT_MODE, key) }
        vault.enableBiometric(encrypt)
        assertTrue(vault.biometric.value)
        vault.lock()

        val reopened = VaultManager(root, json)
        assertTrue("the setting must survive a restart", reopened.biometric.value)
        val decrypt = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            .apply { init(javax.crypto.Cipher.DECRYPT_MODE, key, javax.crypto.spec.GCMParameterSpec(128, reopened.biometricIv)) }
        assertTrue(reopened.unlockWithBiometric(decrypt))
        reopened.disableBiometric()
        assertFalse(reopened.biometric.value)
    }

    @Test
    fun tamperedPublicKeyIsRefused() = runBlocking {
        val root = tmp.newFolder()
        val vault = VaultManager(root, json)
        vault.create("pw".toCharArray(), fastKdf)
        vault.lock()
        // Someone with write access swaps the public key for their own, keeping the wrapped private key.
        val headerFile = File(root, "vault.json")
        val header = json.decodeFromString(VaultHeader.serializer(), headerFile.readText())
        val foreign = io.github.firebirks.booruvault.vault.crypto.EcKeys.generate().public.encoded
        headerFile.writeText(json.encodeToString(VaultHeader.serializer(), header.copy(publicKey = foreign.toBase64())))

        val reopened = VaultManager(root, json)
        try {
            reopened.unlock("pw".toCharArray())
            fail("a header whose public key is not the private key's must not unlock")
        } catch (_: VaultHeaderException) {
        }
        assertTrue(reopened.state.value is VaultState.Locked)
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

    @Test
    fun absurdKdfParametersAreRefusedAsDamage() = runBlocking {
        val root = tmp.newFolder()
        val vault = VaultManager(root, json)
        vault.create("pw".toCharArray(), fastKdf)
        vault.lock()
        // Whoever can write the header could ask Argon2 for memory the process does not have.
        val headerFile = File(root, "vault.json")
        val header = json.decodeFromString(VaultHeader.serializer(), headerFile.readText())
        headerFile.writeText(json.encodeToString(VaultHeader.serializer(), header.copy(kdf = header.kdf.copy(memoryKiB = 512 * 1024))))

        val reopened = VaultManager(root, json)
        try {
            reopened.unlock("pw".toCharArray())
            fail("a header asking for 512 MiB must be refused, not attempted")
        } catch (_: VaultHeaderException) {
        }
        assertTrue(reopened.state.value is VaultState.Locked)
    }

    @Test
    fun kdfCeilingsStayWithinAPhonesHeap() {
        // 256 MiB and ten passes: above that a phone would sooner kill the process than finish.
        assertEquals(256 * 1024, Kdf.MAX_MEMORY_KIB)
        assertEquals(10, Kdf.MAX_ITERATIONS)
        val written = Kdf.newParams()
        assertTrue(Kdf.sane(written))
        assertTrue(Kdf.sane(written.copy(memoryKiB = Kdf.MAX_MEMORY_KIB, iterations = Kdf.MAX_ITERATIONS)))
        assertFalse(Kdf.sane(written.copy(memoryKiB = Kdf.MAX_MEMORY_KIB + 1)))
        assertFalse(Kdf.sane(written.copy(iterations = Kdf.MAX_ITERATIONS + 1)))
        assertFalse(Kdf.sane(written.copy(memoryKiB = Kdf.MIN_MEMORY_KIB - 1)))
        assertFalse(Kdf.sane(written.copy(iterations = 0)))
    }

    @Test
    fun aMetaFileNamingAnotherIdIsIgnored() = runBlocking {
        val root = tmp.newFolder()
        val vault = VaultManager(root, json)
        vault.create("pw".toCharArray(), fastKdf)
        val store = VaultStore(root, vault, json, scope)
        add(store, byteArrayOf(1, 2, 3), "a.jpg")
        awaitItems(store, 1)
        val real = store.items.value.single()
        // A forged meta file, sealed to the public key, that names a path outside the vault.
        val forged = real.copy(id = "../../escape")
        val metaDir = File(root, "meta")
        val bytes = json.encodeToString(VaultItem.serializer(), forged).toByteArray()
        io.github.firebirks.booruvault.vault.crypto.SealedBlob.write(File(metaDir, "forged"), vault.recipient!!, bytes)
        vault.lock()
        vault.unlock("pw".toCharArray())
        awaitItems(store, 1)
        assertEquals(listOf(real.id), store.items.value.map { it.id })
        try {
            store.openMedia("../../escape")
            fail("an id that is not a UUID must be refused")
        } catch (_: IllegalArgumentException) {
        }
    }
}
