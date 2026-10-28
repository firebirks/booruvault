package io.github.firebirks.booruvault.vault

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * A hardware-backed AES key that only works right after a strong biometric check. It wraps a
 * second copy of the vault's private key, so the password stays the root of trust and enrolling
 * a new fingerprint invalidates the shortcut.
 */
object BiometricKey {
    private const val ALIAS = "booruvault.biometric.v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    fun isAvailable(context: Context): Boolean =
        BiometricManager.from(context).canAuthenticate(BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS

    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun newKey(): SecretKey {
        delete()
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
                }
            }
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(spec)
            generateKey()
        }
    }

    /** A fresh key's cipher, ready to be authorised by [authenticate] and handed to the vault. */
    fun encryptCipher(): Cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, newKey()) }

    /**
     * Null when the key is gone, was invalidated by a change in enrolled biometrics, or the
     * Keystore will not hand it out for any other reason: the password is always there.
     */
    fun decryptCipher(iv: ByteArray): Cipher? = try {
        val key = keyStore().getKey(ALIAS, null) as? SecretKey ?: return null
        Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv)) }
    } catch (_: KeyPermanentlyInvalidatedException) {
        delete()
        null
    } catch (_: GeneralSecurityException) {
        null
    }

    fun delete() {
        runCatching { keyStore().deleteEntry(ALIAS) }
    }

    /** Shows the system prompt; [onResult] gets the authorised cipher, or null if the user gave up. */
    fun authenticate(
        activity: FragmentActivity,
        title: String,
        cipher: Cipher,
        onResult: (Cipher?, String?) -> Unit,
    ) {
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onResult(result.cryptoObject?.cipher, null)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    val cancelled = errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                        errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                        errorCode == BiometricPrompt.ERROR_CANCELED
                    onResult(null, if (cancelled) null else errString.toString())
                }
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setNegativeButtonText(activity.getString(io.github.firebirks.booruvault.R.string.biometric_use_password))
            .setAllowedAuthenticators(BIOMETRIC_STRONG)
            .build()
        prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
    }
}
