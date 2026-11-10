package io.github.firebirks.booruvault.vault

import io.github.firebirks.booruvault.settings.SettingsRepository
import io.github.firebirks.booruvault.util.Problem
import io.github.firebirks.booruvault.util.ProblemException
import io.github.firebirks.booruvault.vault.crypto.Kdf
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.random.Random

/**
 * A backup is the vault's own sealed files in one file: made without the private key, and the
 * vault again on a phone that has none, opened by the password it had. What comes back from
 * outside is taken on nothing but what it proves: a file that is not a whole backup of a
 * layout this app knows leaves no vault, and nothing outside the place it is laid out in.
 */
class VaultBackupTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val fastKdf get() = Kdf.newParams(memoryKiB = 1024, iterations = 1)
    private val password = "correct horse"

    @After
    fun tearDown() = scope.cancel()

    /** A vault with its store, as the app wires them. */
    private inner class Phone(val root: File = File(tmp.newFolder(), "vault")) {
        val vault = VaultManager(root, json)
        val settings = SettingsRepository(root, vault, json, scope)
        val store = VaultStore(root, vault, json, scope)
        val backup = VaultBackup(root, vault, json)

        suspend fun create() = vault.create(password.toCharArray(), fastKdf)

        suspend fun add(data: ByteArray, name: String, thumbnail: ByteArray? = null): String {
            val id = store.newId()
            val size = store.writeMedia(id) { it.write(data) }
            thumbnail?.let { store.writeThumbnail(id, it) }
            store.commit(VaultItem(id = id, addedAt = System.currentTimeMillis(), fileName = name, mimeType = "image/png", size = size, hasThumbnail = thumbnail != null, tags = listOf("tag_of_$name")))
            return id
        }

        suspend fun awaitItems(count: Int) = withTimeout(10_000) { store.items.first { it.size == count } }

        suspend fun backedUp(): ByteArray = ByteArrayOutputStream().also { backup.write(it) }.toByteArray()

        fun media(id: String): ByteArray = store.openMedia(id).use { it.readAll() }

        /** Every file under the vault's folder and beside it, by path: what a failed restore must leave empty. */
        fun files(): List<String> = root.parentFile!!.walkTopDown().filter { it.isFile }.map { it.relativeTo(root.parentFile!!).path }.toList()
    }

    private fun entries(backup: ByteArray): List<Pair<String, ByteArray>> = ZipInputStream(backup.inputStream()).use { zip ->
        generateSequence { zip.nextEntry }.map { it.name to zip.readBytes() }.toList()
    }

    private fun zipOf(entries: List<Pair<String, ByteArray>>): ByteArray = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { zip ->
            entries.forEach { (name, data) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(data)
                zip.closeEntry()
            }
        }
    }.toByteArray()

    /** [entries] with a last entry that counts them, as a whole backup ends. */
    private fun ended(entries: List<Pair<String, ByteArray>>): List<Pair<String, ByteArray>> =
        entries + ("end.json" to """{"entries":${entries.size}}""".toByteArray())

    private fun assertRefused(phone: Phone, backup: ByteArray, code: Problem.Code = Problem.Code.BACKUP_UNREADABLE) = runBlocking {
        try {
            phone.backup.restore(backup.inputStream())
            fail("a backup that is not whole and known must be refused")
        } catch (e: ProblemException) {
            assertEquals(code, e.problem.code)
        }
        assertEquals(VaultState.Missing, phone.vault.state.value)
        assertEquals(emptyList<String>(), phone.files())
    }

    @Test
    fun `a backup is the vault again on a phone without one, opened by its password`() = runBlocking {
        val old = Phone()
        old.create()
        withTimeout(5000) { old.settings.ready.first { it } }
        old.settings.saveCollection(TagCollection("c1", "Landscapes", "landscape -rain", 1L))
        val picture = Random(1).nextBytes(200_000)
        val clip = Random(2).nextBytes(70_000)
        val thumbnail = Random(3).nextBytes(5_000)
        val first = old.add(picture, "a.png", thumbnail)
        val second = old.add(clip, "b.png")
        old.awaitItems(2)
        var told = 0L to 0L
        val bytes = ByteArrayOutputStream()
        val summary = old.backup.write(bytes) { done, total -> told = done to total }
        assertEquals(2, summary.items)
        assertEquals(told.second, told.first)
        // Made with the vault locked as well as open: nothing in it needs the private key.
        old.vault.lock()
        assertEquals(entries(bytes.toByteArray()).map { it.first }.toSet(), entries(old.backedUp()).map { it.first }.toSet())

        val new = Phone()
        assertEquals(VaultState.Missing, new.vault.state.value)
        var read = 0L
        new.backup.restore(bytes.toByteArray().inputStream()) { read = it }
        assertEquals(VaultState.Locked, new.vault.state.value)
        assertFalse(new.vault.unlock("wrong horse".toCharArray()))
        assertTrue(new.vault.unlock(password.toCharArray()))
        val items = new.awaitItems(2)
        assertEquals(setOf(first, second), items.map { it.id }.toSet())
        assertEquals(listOf("tag_of_a.png"), items.single { it.id == first }.tags)
        assertArrayEquals(picture, new.media(first))
        assertArrayEquals(clip, new.media(second))
        assertArrayEquals(thumbnail, new.store.openThumbnail(first).use { it.readAll() })
        withTimeout(5000) { new.settings.ready.first { it } }
        assertEquals(listOf("Landscapes"), new.settings.settings.value.collections.map { it.name })
        // Nothing is left of the place the backup was laid out in.
        assertTrue(new.files().all { it.startsWith("vault" + File.separator) })

        // The restored vault is a vault like any other: it reads the same after a restart.
        val restarted = Phone(new.root)
        assertEquals(VaultState.Locked, restarted.vault.state.value)
        assertTrue(restarted.vault.unlock(password.toCharArray()))
        assertEquals(2, restarted.awaitItems(2).size)
    }

    @Test
    fun `a backup holds the vault's sealed files and nothing that belongs to the phone`() = runBlocking {
        val phone = Phone()
        phone.create()
        val id = phone.add(Random(1).nextBytes(1000), "a.png", Random(2).nextBytes(100))
        phone.awaitItems(1)
        // What stays behind: the cookies, the queue, the index, and the fingerprint's copy of the key.
        File(phone.root, "cookies.bin").writeBytes(ByteArray(10))
        File(phone.root, "downloads.bin").writeBytes(ByteArray(10))
        val headerFile = File(phone.root, "vault.json")
        val header = json.decodeFromString(VaultHeader.serializer(), headerFile.readText())
        headerFile.writeText(json.encodeToString(VaultHeader.serializer(), header.copy(biometricWrapped = "AAAA", biometricIv = "AAAA")))
        val reopened = Phone(phone.root)
        assertTrue(reopened.vault.biometricEnabled)

        val backup = reopened.backedUp()
        val entries = entries(backup)
        assertEquals(listOf("backup.json", "vault.json", "media/$id", "thumbs/$id", "meta/$id", "end.json"), entries.map { it.first })
        val kept = json.decodeFromString(VaultHeader.serializer(), entries[1].second.decodeToString())
        assertNull(kept.biometricWrapped)
        assertNull(kept.biometricIv)
        assertEquals(header.wrappedPrivateKey, kept.wrappedPrivateKey)
        // The sealed files go in as they are, byte for byte, and every entry bears the same date.
        assertArrayEquals(File(phone.root, "media/$id").readBytes(), entries[2].second)
        assertEquals(1, ZipInputStream(backup.inputStream()).use { zip -> generateSequence { zip.nextEntry }.map { it.time }.toSet() }.size)

        // A fingerprint's copy that did come along is dropped where the backup is put back.
        val withCopy = zipOf(entries.map { (name, data) -> name to if (name == "vault.json") headerFile.readBytes() else data })
        val new = Phone()
        new.backup.restore(withCopy.inputStream())
        assertFalse(new.vault.biometricEnabled)
        assertNull(json.decodeFromString(VaultHeader.serializer(), File(new.root, "vault.json").readText()).biometricWrapped)
        assertTrue(Phone(new.root).vault.unlock(password.toCharArray()))
    }

    @Test
    fun `a backup made before a password change's re-sealing is done still reads every file`() = runBlocking {
        val old = Phone()
        old.create()
        val data = Random(1).nextBytes(50_000)
        val id = old.add(data, "a.png")
        old.awaitItems(1)
        // No re-sealer runs here: the file stays sealed to the pair the change retired.
        assertTrue(old.vault.changePassword(password.toCharArray(), "second password".toCharArray()))
        val backup = old.backedUp()

        val new = Phone()
        new.backup.restore(backup.inputStream())
        assertTrue(new.vault.rekeyPending.value)
        assertFalse(new.vault.unlock(password.toCharArray()))
        assertTrue(new.vault.unlock("second password".toCharArray()))
        new.awaitItems(1)
        assertArrayEquals(data, new.media(id))
    }

    @Test
    fun `a password changed between a backup's header and its files fails it`() = runBlocking {
        val phone = Phone()
        phone.create()
        // More than the writer buffers, so that the header is out while files are still to come.
        repeat(3) { phone.add(Random(it).nextBytes(200_000), "$it.png") }
        phone.awaitItems(3)
        var changed = false
        val out = object : OutputStream() {
            override fun write(b: Int) = Unit
            override fun write(b: ByteArray, off: Int, len: Int) {
                if (changed) return
                changed = true
                runBlocking { assertTrue(phone.vault.changePassword(password.toCharArray(), "second password".toCharArray())) }
            }
        }
        try {
            phone.backup.write(out)
            fail("files re-sealed since the header was written would be lost to it")
        } catch (e: ProblemException) {
            assertEquals(Problem.Code.BACKUP_KEY_CHANGED, e.problem.code)
        }
        assertTrue(changed)
    }

    @Test
    fun `an item whose file is gone is left out whole`() = runBlocking {
        val phone = Phone()
        phone.create()
        val kept = phone.add(Random(1).nextBytes(1000), "a.png")
        val gone = phone.add(Random(2).nextBytes(1000), "b.png")
        phone.awaitItems(2)
        File(phone.root, "media/$gone").delete()
        val names = entries(phone.backedUp()).map { it.first }
        assertTrue("media/$kept" in names && "meta/$kept" in names)
        assertTrue(names.none { gone in it })
        val new = Phone()
        new.backup.restore(zipOf(entries(phone.backedUp())).inputStream())
        assertTrue(new.vault.unlock(password.toCharArray()))
        assertEquals(listOf(kept), new.awaitItems(1).map { it.id })
    }

    @Test
    fun `a backup cut short is refused and leaves no vault`() = runBlocking {
        val phone = Phone()
        phone.create()
        repeat(2) { phone.add(Random(it).nextBytes(100_000), "$it.png") }
        phone.awaitItems(2)
        val backup = phone.backedUp()
        val whole = entries(backup)
        // Anywhere before the last file's end: past it a ZIP has only its own table of contents,
        // which is not read, and whose loss costs the backup nothing.
        val files = backup.size - whole.last().second.size - 2000
        for (length in listOf(0, 10, backup.size / 3, backup.size / 2, files)) {
            assertRefused(Phone(), backup.copyOf(length))
        }
        // Cut cleanly between two files, as no reader of ZIPs would notice: the end is not there to count them.
        assertRefused(Phone(), zipOf(whole.dropLast(1)))
        // A file fewer than the end counts.
        assertRefused(Phone(), zipOf(whole.filterNot { it.first.startsWith("meta/") }.take(whole.size - 3) + whole.last()))
        // And the whole one, written anew the same way, is taken.
        val new = Phone()
        new.backup.restore(zipOf(whole).inputStream())
        assertEquals(VaultState.Locked, new.vault.state.value)
    }

    @Test
    fun `a file that is not a backup is refused`() = runBlocking {
        val phone = Phone()
        phone.create()
        val id = phone.add(Random(1).nextBytes(1000), "a.png")
        phone.awaitItems(1)
        val whole = entries(phone.backedUp())
        val body = whole.dropLast(1)
        assertRefused(Phone(), Random(1).nextBytes(5000))
        assertRefused(Phone(), zipOf(listOf("holiday.jpg" to ByteArray(100))))
        // Out of order, a header that is not one, a file under a name no backup uses, a folder.
        assertRefused(Phone(), zipOf(ended(listOf(body[1], body[0]) + body.drop(2))))
        assertRefused(Phone(), zipOf(ended(body.map { (name, data) -> name to if (name == "vault.json") "{}".toByteArray() else data })))
        assertRefused(Phone(), zipOf(ended(body + ("notes.txt" to ByteArray(10)))))
        assertRefused(Phone(), zipOf(ended(body + ("media/" to ByteArray(0)))))
        assertRefused(Phone(), zipOf(ended(body + ("index.bin" to body.last().second))))
        // Something after the end.
        assertRefused(Phone(), zipOf(whole + ("meta/${phone.store.newId()}" to body.last().second)))
        // A file that does not begin as a sealed one does, however it is named.
        assertRefused(Phone(), zipOf(ended(body + ("meta/${phone.store.newId()}" to "{\"id\":\"x\"}".toByteArray()))))
        assertRefused(Phone(), zipOf(ended(body + ("meta/${phone.store.newId()}" to "BV".toByteArray()))))
        assertTrue(id.isNotEmpty())
    }

    @Test
    fun `no name in a backup reaches outside the vault`() = runBlocking {
        val phone = Phone()
        phone.create()
        phone.add(Random(1).nextBytes(1000), "a.png")
        phone.awaitItems(1)
        val body = entries(phone.backedUp()).dropLast(1)
        val sealed = body.last().second
        val id = phone.store.newId()
        for (name in listOf("../escaped", "media/../../escaped", "/tmp/escaped", "media/$id/../../../escaped", "meta/$id.tmp", "media/sub/$id", "other/$id", "meta/")) {
            assertRefused(Phone(), zipOf(ended(body + (name to sealed))))
        }
    }

    @Test
    fun `a backup of a layout a later version wrote is said to be newer`() = runBlocking {
        val phone = Phone()
        phone.create()
        val whole = entries(phone.backedUp())
        assertRefused(Phone(), zipOf(whole.map { (name, data) -> name to if (name == "backup.json") """{"format":2}""".toByteArray() else data }), Problem.Code.BACKUP_NEWER)
        val header = json.decodeFromString(VaultHeader.serializer(), whole[1].second.decodeToString())
        val later = json.encodeToString(VaultHeader.serializer(), header.copy(version = 2)).toByteArray()
        assertRefused(Phone(), zipOf(whole.map { (name, data) -> name to if (name == "vault.json") later else data }), Problem.Code.BACKUP_NEWER)
    }

    @Test
    fun `a backup is not put over a vault that is there`() = runBlocking {
        val phone = Phone()
        phone.create()
        val id = phone.add(Random(1).nextBytes(1000), "a.png")
        phone.awaitItems(1)
        val backup = phone.backedUp()
        try {
            phone.backup.restore(backup.inputStream())
            fail("a vault that exists must be deleted by the user first")
        } catch (_: IllegalStateException) {
        }
        phone.vault.lock()
        try {
            phone.backup.restore(backup.inputStream())
            fail("locked is still there")
        } catch (_: IllegalStateException) {
        }
        assertTrue(phone.vault.unlock(password.toCharArray()))
        assertEquals(1000, phone.media(id).size)
    }

    @Test
    fun `what an interrupted restore left is cleared`() = runBlocking {
        val phone = Phone()
        val left = File(phone.root.parentFile, "vault.restore/media").apply { mkdirs() }
        File(left, "half").writeBytes(ByteArray(10))
        phone.backup.clearLeftovers()
        assertEquals(emptyList<String>(), phone.files())
    }
}
