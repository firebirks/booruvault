package io.github.firebirks.booruvault.vault

import io.github.firebirks.booruvault.util.moveOver
import io.github.firebirks.booruvault.vault.crypto.Recipient
import io.github.firebirks.booruvault.vault.crypto.SealedBlob
import io.github.firebirks.booruvault.vault.crypto.VaultKeys
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * A value kept as JSON inside a sealed file. Writing needs only the public key; reading needs the
 * unlocked vault. The plaintext bytes are wiped as soon as they are encoded or decoded.
 */
class SealedJson<T>(private val file: File, private val serializer: KSerializer<T>, private val json: Json) {
    /**
     * The value kept, or null when there is no file. A file that cannot be read, or decoded, is
     * set aside as `.corrupt` rather than written over on the next save, and [onUnreadable] is
     * told of it, so that whoever starts afresh for want of it can say so.
     */
    fun read(keys: VaultKeys, onUnreadable: (Exception) -> Unit = {}): T? {
        val bytes = try {
            SealedBlob.read(file, keys)
        } catch (e: Exception) {
            setAside(e, onUnreadable)
            null
        } ?: return null
        return try {
            json.decodeFromString(serializer, bytes.decodeToString())
        } catch (e: Exception) {
            setAside(e, onUnreadable)
            null
        } finally {
            bytes.fill(0)
        }
    }

    private fun setAside(cause: Exception, onUnreadable: (Exception) -> Unit) {
        file.moveOver(File(file.path + ".corrupt"))
        onUnreadable(cause)
    }

    @Synchronized
    fun write(recipient: Recipient, value: T) {
        val bytes = json.encodeToString(serializer, value).toByteArray()
        try {
            SealedBlob.write(file, recipient, bytes)
        } finally {
            bytes.fill(0)
        }
    }

    /**
     * Writes the copy [take] returns, taken under the same lock as the write: of two saves that
     * race, the copy taken later is the one left. Null writes nothing.
     */
    @Synchronized
    fun write(recipient: Recipient, take: () -> T?) {
        write(recipient, take() ?: return)
    }

    fun delete() {
        file.delete()
    }
}
