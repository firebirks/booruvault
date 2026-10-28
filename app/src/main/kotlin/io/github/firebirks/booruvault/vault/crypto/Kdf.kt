package io.github.firebirks.booruvault.vault.crypto

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
     * Whether [params] are ones this app would have written. The header is in clear, and a
     * memory or pass count altered by whoever can write it would make Argon2 eat the process
     * at the next unlock; a vault that asks for more than this is refused as damaged. The
     * ceilings leave room above [newParams] for stronger vaults later, but stay near what
     * Android grants one app's Java heap, where BouncyCastle keeps the whole Argon2 memory.
     */
    fun sane(params: KdfParams): Boolean =
        params.algorithm == KdfParams.ALGORITHM &&
            params.memoryKiB in MIN_MEMORY_KIB..MAX_MEMORY_KIB &&
            params.iterations in 1..MAX_ITERATIONS &&
            params.parallelism in 1..MAX_PARALLELISM &&
            runCatching { params.salt.fromBase64().size >= 8 }.getOrDefault(false)

    const val MIN_MEMORY_KIB = 1024
    const val MAX_MEMORY_KIB = 256 * 1024
    const val MAX_ITERATIONS = 10
    const val MAX_PARALLELISM = 8

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
