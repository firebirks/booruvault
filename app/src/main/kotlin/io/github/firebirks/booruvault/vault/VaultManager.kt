package io.github.firebirks.booruvault.vault

import io.github.firebirks.booruvault.util.moveOver
import io.github.firebirks.booruvault.vault.crypto.Aead
import io.github.firebirks.booruvault.vault.crypto.EcKeys
import io.github.firebirks.booruvault.vault.crypto.EciesBox
import io.github.firebirks.booruvault.vault.crypto.Kdf
import io.github.firebirks.booruvault.vault.crypto.KdfParams
import io.github.firebirks.booruvault.vault.crypto.Recipient
import io.github.firebirks.booruvault.vault.crypto.Secrets
import io.github.firebirks.booruvault.vault.crypto.VaultKeys
import io.github.firebirks.booruvault.vault.crypto.fromBase64
import io.github.firebirks.booruvault.vault.crypto.toBase64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.GeneralSecurityException
import javax.crypto.Cipher

/** The vault's only clear-text file: how to turn the password into the private key. */
@Serializable
data class VaultHeader(
    val version: Int = 1,
    val createdAt: Long,
    val kdf: KdfParams,
    /** X.509 P-256 public key; everything in the vault is sealed to it. */
    val publicKey: String,
    /** PKCS#8 private key, AES-GCM under the Argon2id key. */
    val wrappedPrivateKey: String,
    /** The same private key under a biometric-gated Keystore key, when enabled. */
    val biometricWrapped: String? = null,
    val biometricIv: String? = null,
    /** Pairs replaced by a password change whose files are still being re-sealed. */
    val retired: List<RetiredKey> = emptyList(),
)

/** A replaced key pair: its private key is boxed to the current public key, so any unlock recovers it. */
@Serializable
data class RetiredKey(val publicKey: String, val boxedPrivateKey: String)

/** The header's public key is not the private key's: the header was altered or damaged. */
class VaultHeaderException : GeneralSecurityException("vault header public key does not match the private key")

sealed interface VaultState {
    data object Missing : VaultState
    data object Locked : VaultState
    class Unlocked(val keys: VaultKeys) : VaultState
}

/**
 * Owns the vault's keys. The private key exists in memory only while unlocked; the public key is
 * always available, so new content can be sealed into a locked vault.
 */
class VaultManager(private val root: File, private val json: Json) {
    private val headerFile = File(root, "vault.json")
    private val mutex = Mutex()

    private var header: VaultHeader? = readHeader()
    private val _state = MutableStateFlow<VaultState>(if (header == null) VaultState.Missing else VaultState.Locked)
    val state: StateFlow<VaultState> = _state.asStateFlow()

    /** The sealing key, or null before a vault exists. */
    val recipient: Recipient?
        get() = header?.let { Recipient.fromEncoded(it.publicKey.fromBase64()) }

    val keys: VaultKeys? get() = (_state.value as? VaultState.Unlocked)?.keys

    private val _biometric = MutableStateFlow(header?.biometricWrapped != null)

    private val _rekeyPending = MutableStateFlow(header?.retired?.isNotEmpty() == true)

    /** True while files sealed to a retired pair may remain; [VaultRekeyer] clears it. */
    val rekeyPending: StateFlow<Boolean> = _rekeyPending.asStateFlow()

    /** Whether a biometric copy of the key exists; observed by the settings switch. */
    val biometric: StateFlow<Boolean> = _biometric.asStateFlow()

    val biometricEnabled: Boolean get() = _biometric.value

    val biometricIv: ByteArray? get() = header?.biometricIv?.fromBase64()

    /** [password] is wiped by every method that takes one. */
    suspend fun create(password: CharArray, kdf: KdfParams = Kdf.newParams()) = mutex.withLock {
        withContext(Dispatchers.Default) {
            check(header == null) { "vault already exists" }
            val pair = EcKeys.generate()
            val pkcs8 = pair.private.encoded
            val kek = try {
                Kdf.derive(password, kdf)
            } finally {
                Secrets.wipe(password)
            }
            val wrapped = try {
                Aead.seal(kek, pkcs8, PASSWORD_AAD)
            } finally {
                kek.fill(0)
                pkcs8.fill(0)
            }
            val created = VaultHeader(
                createdAt = System.currentTimeMillis(),
                kdf = kdf,
                publicKey = pair.public.encoded.toBase64(),
                wrappedPrivateKey = wrapped.toBase64(),
            )
            root.mkdirs()
            writeHeader(created)
            header = created
            _state.value = VaultState.Unlocked(VaultKeys(Recipient.fromEncoded(pair.public.encoded), pair.private))
        }
    }

    /** Returns false for a wrong password. */
    suspend fun unlock(password: CharArray): Boolean = mutex.withLock {
        withContext(Dispatchers.Default) {
            val current = header ?: return@withContext false.also { Secrets.wipe(password) }
            val pkcs8 = unwrapWithPassword(current, password) ?: return@withContext false
            finishUnlock(current, pkcs8)
            true
        }
    }

    /**
     * Drops the private keys, the retired ones with the current. The JCA key objects cannot be
     * overwritten from Kotlin; once nothing references them the GC frees their native copies,
     * which BoringSSL clears on release.
     */
    fun lock() {
        val unlocked = _state.value as? VaultState.Unlocked ?: return
        _state.value = VaultState.Locked
        unlocked.keys.destroyPrivateKeys()
    }

    /**
     * Changes the password and replaces the key pair with it, so that an old copy of the header
     * opened with the old password is worth nothing once every file is re-sealed. The old pair is
     * kept boxed to the new public key until [VaultRekeyer] has re-sealed everything. The
     * biometric copy, which held the old key, is dropped; the user can enrol again.
     */
    suspend fun changePassword(old: CharArray, new: CharArray): Boolean = mutex.withLock {
        withContext(Dispatchers.Default) {
            val current = header ?: return@withContext false.also {
                Secrets.wipe(old)
                Secrets.wipe(new)
            }
            val oldPkcs8 = unwrapWithPassword(current, old) ?: return@withContext false.also { Secrets.wipe(new) }
            val oldKeys = try {
                withRetired(current, VaultKeys(Recipient.fromEncoded(current.publicKey.fromBase64()), EcKeys.privateKey(oldPkcs8)))
            } finally {
                oldPkcs8.fill(0)
            }
            if (!EcKeys.isPair(oldKeys.privateKey, oldKeys.recipient.key)) throw VaultHeaderException()
            val pair = EcKeys.generate()
            val recipient = Recipient.fromEncoded(pair.public.encoded)
            val kdf = Kdf.newParams()
            val kek = try {
                Kdf.derive(new, kdf)
            } finally {
                Secrets.wipe(new)
            }
            val pkcs8 = pair.private.encoded
            val wrapped = try {
                Aead.seal(kek, pkcs8, PASSWORD_AAD)
            } finally {
                kek.fill(0)
                pkcs8.fill(0)
            }
            val retiredKeys = listOf(VaultKeys(oldKeys.recipient, oldKeys.privateKey)) + oldKeys.retired
            val retired = retiredKeys.map { boxRetired(recipient, it) }
            val updated = current.copy(
                kdf = kdf,
                publicKey = pair.public.encoded.toBase64(),
                wrappedPrivateKey = wrapped.toBase64(),
                biometricWrapped = null,
                biometricIv = null,
                retired = retired,
            )
            writeHeader(updated)
            header = updated
            _biometric.value = false
            _rekeyPending.value = true
            if (_state.value is VaultState.Unlocked) _state.value = VaultState.Unlocked(VaultKeys(recipient, pair.private, retiredKeys))
            true
        }
    }

    /**
     * Called once no file is sealed to a retired pair any more, every one re-sealed to
     * [resealedTo]: the retired pairs are forgotten, unless a password change since has retired
     * [resealedTo] as well.
     */
    suspend fun retireFinished(resealedTo: Recipient) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val current = header ?: return@withContext
            if (current.retired.isEmpty()) return@withContext
            if (!current.publicKey.fromBase64().contentEquals(resealedTo.encoded)) return@withContext
            val updated = current.copy(retired = emptyList())
            writeHeader(updated)
            header = updated
            _rekeyPending.value = false
            (_state.value as? VaultState.Unlocked)?.let {
                _state.value = VaultState.Unlocked(VaultKeys(it.keys.recipient, it.keys.privateKey))
                it.keys.retired.forEach { retired -> retired.destroyPrivateKeys() }
            }
        }
    }

    private fun boxRetired(recipient: Recipient, keys: VaultKeys): RetiredKey {
        val pkcs8 = keys.privateKey.encoded
        val boxed = try {
            EciesBox.seal(recipient, pkcs8, RETIRED_AAD)
        } finally {
            pkcs8.fill(0)
        }
        return RetiredKey(keys.recipient.encoded.toBase64(), boxed.toBase64())
    }

    /** [keys] together with the retired pairs of [current] that they can recover. */
    private fun withRetired(current: VaultHeader, keys: VaultKeys): VaultKeys {
        if (current.retired.isEmpty()) return keys
        val retired = current.retired.mapNotNull { entry ->
            val pkcs8 = try {
                EciesBox.open(keys, entry.boxedPrivateKey.fromBase64(), RETIRED_AAD)
            } catch (_: GeneralSecurityException) {
                // A damaged entry: its files are unreadable either way.
                return@mapNotNull null
            }
            val pair = try {
                VaultKeys(Recipient.fromEncoded(entry.publicKey.fromBase64()), EcKeys.privateKey(pkcs8))
            } finally {
                pkcs8.fill(0)
            }
            pair.takeIf { EcKeys.isPair(it.privateKey, it.recipient.key) }
        }
        return VaultKeys(keys.recipient, keys.privateKey, retired)
    }

    /** Stores the private key under [cipher], an ENCRYPT_MODE cipher the user just authorised. */
    suspend fun enableBiometric(cipher: Cipher) = mutex.withLock {
        withContext(Dispatchers.Default) {
            val current = header ?: error("no vault")
            val unlocked = keys ?: error("vault locked")
            val pkcs8 = unlocked.privateKey.encoded
            val wrapped = try {
                cipher.doFinal(pkcs8)
            } finally {
                pkcs8.fill(0)
            }
            val updated = current.copy(biometricWrapped = wrapped.toBase64(), biometricIv = cipher.iv.toBase64())
            writeHeader(updated)
            header = updated
            _biometric.value = true
        }
    }

    /** [cipher] is a DECRYPT_MODE cipher the user just authorised with a fingerprint or face. */
    suspend fun unlockWithBiometric(cipher: Cipher): Boolean = mutex.withLock {
        withContext(Dispatchers.Default) {
            val current = header ?: return@withContext false
            val wrapped = current.biometricWrapped?.fromBase64() ?: return@withContext false
            val pkcs8 = try {
                cipher.doFinal(wrapped)
            } catch (_: GeneralSecurityException) {
                return@withContext false
            }
            finishUnlock(current, pkcs8)
            true
        }
    }

    suspend fun disableBiometric() = mutex.withLock {
        withContext(Dispatchers.IO) {
            val current = header ?: return@withContext
            if (current.biometricWrapped == null) return@withContext
            val updated = current.copy(biometricWrapped = null, biometricIv = null)
            writeHeader(updated)
            header = updated
            _biometric.value = false
        }
    }

    /** Deletes the vault and everything in it. Irreversible. */
    suspend fun destroy() = mutex.withLock {
        withContext(Dispatchers.IO) {
            val unlocked = _state.value as? VaultState.Unlocked
            _state.value = VaultState.Missing
            header = null
            _biometric.value = false
            _rekeyPending.value = false
            unlocked?.keys?.destroyPrivateKeys()
            root.deleteRecursively()
        }
    }

    private fun unwrapWithPassword(current: VaultHeader, password: CharArray): ByteArray? {
        if (!Kdf.sane(current.kdf)) {
            Secrets.wipe(password)
            throw VaultHeaderException()
        }
        val kek = try {
            Kdf.derive(password, current.kdf)
        } finally {
            Secrets.wipe(password)
        }
        return try {
            Aead.open(kek, current.wrappedPrivateKey.fromBase64(), PASSWORD_AAD)
        } catch (_: GeneralSecurityException) {
            null
        } finally {
            kek.fill(0)
        }
    }

    /** Throws [VaultHeaderException] when the header's public key is not this private key's. */
    private fun finishUnlock(current: VaultHeader, pkcs8: ByteArray) {
        val privateKey = try {
            EcKeys.privateKey(pkcs8)
        } finally {
            pkcs8.fill(0)
        }
        val recipient = Recipient.fromEncoded(current.publicKey.fromBase64())
        if (!EcKeys.isPair(privateKey, recipient.key)) throw VaultHeaderException()
        _state.value = VaultState.Unlocked(withRetired(current, VaultKeys(recipient, privateKey)))
    }

    private fun readHeader(): VaultHeader? =
        headerFile.takeIf { it.exists() }?.let { json.decodeFromString(VaultHeader.serializer(), it.readText()) }

    private fun writeHeader(value: VaultHeader) {
        val tmp = File(root, "vault.json.tmp")
        tmp.outputStream().use { out ->
            out.write(json.encodeToString(VaultHeader.serializer(), value).toByteArray())
            out.fd.sync()
        }
        if (!tmp.moveOver(headerFile)) throw java.io.IOException("cannot write vault header")
    }

    private companion object {
        val PASSWORD_AAD = "BooruVault private key v1".toByteArray()
        val RETIRED_AAD = "BooruVault retired key v1".toByteArray()
    }
}
