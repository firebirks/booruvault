package io.github.firebirks.booruvault.vault

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import io.github.firebirks.booruvault.settings.SettingsRepository
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A timed lock that does not count on the process running: Android freezes a cached app, and a
 * frozen app's own timer never fires, so an alarm from the system has to bring it back.
 */
class AutoLockTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val fastKdf get() = Kdf.newParams(memoryKiB = 1024, iterations = 1)
    private val owner = object : LifecycleOwner {
        override val lifecycle: Lifecycle get() = error("not used")
    }

    private var now = 1_000_000L
    private val alarm = object : LockAlarm {
        var at: Long? = null

        override fun set(atElapsed: Long) {
            at = atElapsed
        }

        override fun cancel() {
            at = null
        }
    }

    @After
    fun tearDown() = scope.cancel()

    private fun autoLock(vault: VaultManager) = AutoLock(vault, settings(vault), scope, alarm) { now }

    private fun settings(vault: VaultManager) = SettingsRepository(tmp.root, vault, json, scope)

    private fun openVault() = runBlocking { VaultManager(tmp.root, json).also { it.create("pw".toCharArray(), fastKdf) } }

    @Test
    fun leavingTheAppSetsAnAlarmForTheTimeout() {
        val lock = autoLock(openVault())
        lock.onStop(owner)
        // The default timeout, five minutes.
        assertEquals(now + 300_000, alarm.at)
    }

    @Test
    fun theAlarmLocksAVaultLeftForTheTimeout() {
        val vault = openVault()
        val lock = autoLock(vault)
        lock.onStop(owner)
        now += 300_000
        lock.lockIfDue()
        assertEquals(VaultState.Locked, vault.state.value)
    }

    @Test
    fun comingBackCancelsTheAlarm() {
        val lock = autoLock(openVault())
        lock.onStop(owner)
        now += 10_000
        lock.onStart(owner)
        assertNull(alarm.at)
    }

    @Test
    fun anAlarmDeliveredAfterTheReturnLeavesTheVaultOpen() {
        val vault = openVault()
        val lock = autoLock(vault)
        lock.onStop(owner)
        now += 10_000
        lock.onStart(owner)
        now += 300_000
        lock.lockIfDue()
        assertTrue(vault.state.value is VaultState.Unlocked)
    }

    @Test
    fun leavingALockedVaultSetsNoAlarm() {
        val vault = openVault()
        vault.lock()
        autoLock(vault).onStop(owner)
        assertNull(alarm.at)
    }

    @Test
    fun aHoldStretchesLockImmediatelyForOneTripOnly() = runBlocking {
        val vault = openVault()
        val settings = settings(vault)
        withTimeout(5000) { settings.ready.first { it } }
        settings.setAutoLock(0)
        val lock = AutoLock(vault, settings, scope, alarm) { now }
        // A picker is opened, and the screen that opened it is gone when it closes: never released.
        lock.hold()
        lock.onStop(owner)
        assertTrue("the picker's trip does not lock the vault", vault.state.value is VaultState.Unlocked)
        assertEquals(now + 300_000, alarm.at)
        now += 10_000
        lock.onStart(owner)
        assertEquals("a hold ends with the trip it covered", 0, lock.holdCount)
        // The next time the app is left, the setting is back in force.
        lock.onStop(owner)
        assertEquals(VaultState.Locked, vault.state.value)
    }

    @Test
    fun aHoldReleasedAfterItsTripIsHarmless() = runBlocking {
        val vault = openVault()
        val lock = autoLock(vault)
        val release = lock.hold()
        lock.onStop(owner)
        lock.onStart(owner)
        release()
        assertEquals(0, lock.holdCount)
        val another = lock.hold()
        assertEquals(1, lock.holdCount)
        another()
        assertEquals(0, lock.holdCount)
    }
}
