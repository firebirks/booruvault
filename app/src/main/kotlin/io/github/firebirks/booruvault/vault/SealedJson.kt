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
    fun read(keys: VaultKeys): T? {
        val bytes = try {
            SealedBlob.read(file, keys)
        } catch (e: Exception) {
            // Unreadable: set aside rather than silently overwrite on the next save.
            file.moveOver(File(file.path + ".corrupt"))
            null
        } ?: return null
        return try {
            json.decodeFromString(serializer, bytes.decodeToString())
        } catch (e: Exception) {
            null
        } finally {
            bytes.fill(0)
        }
    }

    fun write(recipient: Recipient, value: T) {
        val bytes = json.encodeToString(serializer, value).toByteArray()
        try {
            SealedBlob.write(file, recipient, bytes)
        } finally {
            bytes.fill(0)
        }
    }

    fun delete() {
        file.delete()
    }
}
