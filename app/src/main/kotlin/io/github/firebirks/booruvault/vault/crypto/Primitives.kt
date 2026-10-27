package io.github.firebirks.booruvault.vault.crypto

import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal val secureRandom = SecureRandom()

fun randomBytes(n: Int): ByteArray = ByteArray(n).also(secureRandom::nextBytes)

fun ByteArray.toBase64(): String = Base64.getEncoder().encodeToString(this)
fun String.fromBase64(): ByteArray = Base64.getDecoder().decode(this)

/** Handling of secrets in memory that the app owns and can therefore overwrite. */
object Secrets {
    /** UTF-8 bytes of [chars] without an intermediate String; the caller wipes the result. */
    fun utf8(chars: CharArray): ByteArray {
        val encoded = Charsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
            .encode(CharBuffer.wrap(chars))
        val out = ByteArray(encoded.remaining())
        encoded.get(out)
        if (encoded.hasArray()) encoded.array().fill(0)
        return out
    }

    fun wipe(chars: CharArray) = chars.fill('\u0000')
}

/** RFC 5869 HKDF with HMAC-SHA256. */
object Hkdf {
    fun derive(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 1..255 * 32)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(if (salt.isEmpty()) ByteArray(32) else salt, "HmacSHA256"))
        val prk = mac.doFinal(ikm)
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        val out = ByteArray(length)
        var block = ByteArray(0)
        var pos = 0
        var counter = 1
        while (pos < length) {
            mac.update(block)
            mac.update(info)
            mac.update(counter.toByte())
            block = mac.doFinal()
            val n = minOf(block.size, length - pos)
            System.arraycopy(block, 0, out, pos, n)
            pos += n
            counter++
        }
        prk.fill(0)
        block.fill(0)
        return out
    }
}

/** One-shot AES-256-GCM for small secrets. Output is nonce(12) || ciphertext || tag(16). */
object Aead {
    private const val NONCE = 12
    private const val TAG_BITS = 128

    fun seal(key: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray {
        val nonce = randomBytes(NONCE)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(aad)
        return nonce + cipher.doFinal(plaintext)
    }

    /** Throws [GeneralSecurityException] when the key is wrong or the data was altered. */
    fun open(key: ByteArray, blob: ByteArray, aad: ByteArray): ByteArray {
        if (blob.size < NONCE + 16) throw GeneralSecurityException("ciphertext too short")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, blob, 0, NONCE))
        cipher.updateAAD(aad)
        return cipher.doFinal(blob, NONCE, blob.size - NONCE)
    }
}

/**
 * A small secret sealed to a public key: ECIES with a fresh P-256 pair, ECDH, HKDF-SHA256 and
 * AES-256-GCM. Output is ephemeralKeyLength(2) || ephemeralKey(X.509) || nonce || ciphertext || tag.
 */
object EciesBox {
    private val INFO = "BooruVault key box v1".toByteArray()

    fun seal(recipient: Recipient, plaintext: ByteArray, aad: ByteArray): ByteArray {
        val ephemeral = EcKeys.generate()
        val ephemeralKey = ephemeral.public.encoded
        val key = boxKey(EcKeys.agree(ephemeral.private, recipient.key), ephemeralKey, recipient)
        val box = try {
            Aead.seal(key, plaintext, aad + ephemeralKey)
        } finally {
            key.fill(0)
        }
        return java.nio.ByteBuffer.allocate(2 + ephemeralKey.size + box.size)
            .putShort(ephemeralKey.size.toShort()).put(ephemeralKey).put(box).array()
    }

    /** Throws [GeneralSecurityException] when [keys] are not the pair the box was sealed to. */
    fun open(keys: VaultKeys, blob: ByteArray, aad: ByteArray): ByteArray {
        if (blob.size < 2) throw GeneralSecurityException("box too short")
        val length = ((blob[0].toInt() and 0xff) shl 8) or (blob[1].toInt() and 0xff)
        if (length !in 1..512 || blob.size < 2 + length) throw GeneralSecurityException("bad box")
        val ephemeralKey = blob.copyOfRange(2, 2 + length)
        val key = boxKey(EcKeys.agree(keys.privateKey, EcKeys.publicKey(ephemeralKey)), ephemeralKey, keys.recipient)
        return try {
            Aead.open(key, blob.copyOfRange(2 + length, blob.size), aad + ephemeralKey)
        } finally {
            key.fill(0)
        }
    }

    private fun boxKey(shared: ByteArray, ephemeralKey: ByteArray, recipient: Recipient): ByteArray = try {
        Hkdf.derive(shared, ByteArray(0), INFO + ephemeralKey + recipient.encoded, 32)
    } finally {
        shared.fill(0)
    }
}

/** P-256 key handling for the vault's sealing key pair. */
object EcKeys {
    fun generate(): KeyPair = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"), secureRandom)
    }.generateKeyPair()

    fun publicKey(x509: ByteArray): PublicKey =
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(x509))

    fun privateKey(pkcs8: ByteArray): PrivateKey =
        KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(pkcs8))

    fun agree(privateKey: PrivateKey, publicKey: PublicKey): ByteArray =
        KeyAgreement.getInstance("ECDH").run {
            init(privateKey)
            doPhase(publicKey, true)
            generateSecret()
        }

    /**
     * Whether [publicKey] is [privateKey]'s, without reaching into either: ECDH with a throwaway
     * pair agrees from both sides only when the two belong together.
     */
    fun isPair(privateKey: PrivateKey, publicKey: PublicKey): Boolean {
        val probe = generate()
        val fromPrivate = agree(privateKey, probe.public)
        val fromPublic = agree(probe.private, publicKey)
        val same = MessageDigest.isEqual(fromPrivate, fromPublic)
        fromPrivate.fill(0)
        fromPublic.fill(0)
        return same
    }
}
