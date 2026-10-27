package io.github.firebirks.booruvault.settings

import io.github.firebirks.booruvault.booru.BooruEngine
import io.github.firebirks.booruvault.booru.BooruSource
import io.github.firebirks.booruvault.net.SecureCookieJar
import io.github.firebirks.booruvault.vault.VaultManager
import io.github.firebirks.booruvault.vault.crypto.Kdf
import io.github.firebirks.booruvault.vault.crypto.Secrets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Settings, API keys and cookies are sealed on disk and gone from memory while locked. */
class SealedStateTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val fastKdf get() = Kdf.newParams(memoryKiB = 1024, iterations = 1)

    @After
    fun tearDown() = scope.cancel()

    private fun File.containsText(text: String): Boolean =
        walkTopDown().filter { it.isFile }.any { file -> file.readBytes().decodeToString(throwOnInvalidSequence = false).contains(text) }

    @Test
    fun settingsAreSealedAndForgottenOnLock() = runBlocking {
        val root = tmp.newFolder()
        val vault = VaultManager(root, json)
        val settings = SettingsRepository(root, vault, json, scope)
        assertFalse(settings.ready.value)
        vault.create("pw-123456".toCharArray(), fastKdf)
        withTimeout(5000) { settings.ready.first { it } }

        val secret = "SECRET-API-KEY-31337"
        val source = BooruSource("mine", "Private Booru", BooruEngine.DANBOORU, "https://example.org", "someone", secret)
        settings.setSources(listOf(source))
        settings.setBlacklist("very_private_tag")
        assertTrue(File(root, "config.bin").exists())
        assertFalse("API key on disk in clear", root.containsText(secret))
        assertFalse("blacklist on disk in clear", root.containsText("very_private_tag"))

        vault.lock()
        withTimeout(5000) { settings.ready.first { !it } }
        assertEquals(AppSettings(), settings.settings.value)

        val reopened = VaultManager(root, json)
        val reopenedSettings = SettingsRepository(root, reopened, json, scope)
        assertTrue(reopened.unlock("pw-123456".toCharArray()))
        withTimeout(5000) { reopenedSettings.ready.first { it } }
        assertEquals(listOf(source), reopenedSettings.settings.value.sources)
        assertEquals("very_private_tag", reopenedSettings.settings.value.blacklist)
    }

    @Test
    fun cookiesAreSealedAndUnavailableWhileLocked() = runBlocking {
        val root = tmp.newFolder()
        val vault = VaultManager(root, json)
        val jar = SecureCookieJar(root, vault, json, scope)
        vault.create("pw-123456".toCharArray(), fastKdf)
        val url = "https://e926.net/posts.json".toHttpUrl()
        // The jar loads asynchronously after the unlock.
        withTimeout(5000) {
            while (true) {
                jar.recordClearance("e926.net", "TestAgent/1", listOf(Cookie.parse(url, "cf_clearance=CLEARANCE-VALUE-42; Max-Age=3600; Domain=e926.net")!!))
                if (jar.userAgentFor("static1.e926.net") != null) break
                delay(20)
            }
        }
        assertEquals("cf_clearance", jar.loadForRequest(url).single().name)
        withTimeout(5000) { while (!File(root, "cookies.bin").exists()) delay(20) }
        assertFalse(root.containsText("CLEARANCE-VALUE-42"))

        vault.lock()
        withTimeout(5000) { while (jar.loadForRequest(url).isNotEmpty()) delay(20) }
        assertNull(jar.userAgentFor("e926.net"))

        assertTrue(vault.unlock("pw-123456".toCharArray()))
        withTimeout(5000) { while (jar.loadForRequest(url).isEmpty()) delay(20) }
        assertEquals("TestAgent/1", jar.userAgentFor("e926.net"))
    }

    @Test
    fun cookiesStayOnlyForDownloadsRunningBehindTheLock() = runBlocking {
        val root = tmp.newFolder()
        val vault = VaultManager(root, json)
        val jar = SecureCookieJar(root, vault, json, scope)
        var downloading = true
        jar.retainWhileLocked = { downloading }
        vault.create("pw-123456".toCharArray(), fastKdf)
        val url = "https://e926.net/posts.json".toHttpUrl()
        withTimeout(5000) {
            while (jar.loadForRequest(url).isEmpty()) {
                jar.recordClearance("e926.net", "TestAgent/1", listOf(Cookie.parse(url, "cf_clearance=V; Max-Age=3600; Domain=e926.net")!!))
                delay(20)
            }
        }

        vault.lock()
        delay(200)
        // Downloads still running behind the lock keep what they need to reach the site.
        assertEquals(1, jar.loadForRequest(url).size)
        assertEquals("TestAgent/1", jar.userAgentFor("e926.net"))

        downloading = false
        jar.releaseIfLocked()
        assertTrue(jar.loadForRequest(url).isEmpty())
        assertNull(jar.userAgentFor("e926.net"))
    }

    @Test
    fun onlyCookiesBroughtFromTheBrowserAreKept() = runBlocking {
        val root = tmp.newFolder()
        val vault = VaultManager(root, json)
        val jar = SecureCookieJar(root, vault, json, scope)
        vault.create("pw-123456".toCharArray(), fastKdf)
        val url = "https://gelbooru.com/index.php".toHttpUrl()
        withTimeout(5000) {
            while (jar.userAgentFor("gelbooru.com") == null) {
                jar.recordClearance("gelbooru.com", "UA", listOf(Cookie.parse(url, "fringeBenefits=yup; Max-Age=3600; Domain=gelbooru.com")!!))
                delay(20)
            }
        }
        // Set by the site on its own: usable now, but not kept.
        jar.saveFromResponse(url, listOf(Cookie.parse(url, "PHPSESSID=tracker-id; Max-Age=3600")!!))
        assertEquals(setOf("fringeBenefits", "PHPSESSID"), jar.loadForRequest(url).map { it.name }.toSet())

        vault.lock()
        withTimeout(5000) { while (jar.loadForRequest(url).isNotEmpty()) delay(20) }
        assertTrue(vault.unlock("pw-123456".toCharArray()))
        withTimeout(5000) { while (jar.loadForRequest(url).isEmpty()) delay(20) }
        assertEquals(listOf("fringeBenefits"), jar.loadForRequest(url).map { it.name })
    }

    @Test
    fun passwordArraysAreWiped() = runBlocking {
        val root = tmp.newFolder()
        val vault = VaultManager(root, json)
        val created = "first-pass".toCharArray()
        vault.create(created, fastKdf)
        assertTrue(created.all { it == '\u0000' })
        vault.lock()
        val wrong = "wrong-pass".toCharArray()
        assertFalse(vault.unlock(wrong))
        assertTrue(wrong.all { it == '\u0000' })
        val right = "first-pass".toCharArray()
        assertTrue(vault.unlock(right))
        assertTrue(right.all { it == '\u0000' })
    }

    @Test
    fun utf8EncodingMatchesStrings() {
        for (text in listOf("abc", "pässwörd", "密码", "emoji 🔐")) {
            assertArrayEquals(text.toByteArray(), Secrets.utf8(text.toCharArray()))
        }
    }
}
