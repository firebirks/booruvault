package app.booruvault.vault.crypto

import kotlinx.serialization.Serializable
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters

/** Argon2id parameters, stored next to the vault so they can be raised for new vaults later. */
@Serializable
data class KdfParams(
    val algorithm: String = ALGORITHM,
    val salt: String,
    val memoryKiB: Int,
    val iterations: Int,
    val parallelism: Int,
) {
    companion object {
        const val ALGORITHM = "argon2id"
    }
}

object Kdf {
    /**
     * 64 MiB and three passes: OWASP's recommendation sits well below this, and BouncyCastle's
     * pure-Java Argon2 still finishes in one to three seconds on a current phone.
     */
    fun newParams(memoryKiB: Int = 64 * 1024, iterations: Int = 3): KdfParams =
        KdfParams(salt = randomBytes(16).toBase64(), memoryKiB = memoryKiB, iterations = iterations, parallelism = 1)

    fun derive(password: CharArray, params: KdfParams): ByteArray {
        require(params.algorithm == KdfParams.ALGORITHM) { "unsupported KDF ${params.algorithm}" }
        val argon = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withSalt(params.salt.fromBase64())
            .withMemoryAsKB(params.memoryKiB)
            .withIterations(params.iterations)
            .withParallelism(params.parallelism)
            .build()
        val generator = Argon2BytesGenerator()
        generator.init(argon)
        val out = ByteArray(32)
        // Encoded here rather than by BouncyCastle so the UTF-8 copy can be wiped afterwards.
        val bytes = Secrets.utf8(password)
        try {
            generator.generateBytes(bytes, out)
        } finally {
            bytes.fill(0)
        }
        return out
    }
}
