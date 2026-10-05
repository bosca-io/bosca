package bosca.security.encryption

import bosca.security.model.HashedEncodedPassword
import bosca.security.service.PasswordEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.security.MessageDigest
import java.security.SecureRandom

@Serializable
class ArgonPassword(override val hash: String) : HashedEncodedPassword {
    override fun toString(): String = hash
}

// kotlin port of Argon2PasswordEncoder
class PasswordEncoderImpl(
    private val saltLength: Int = DEFAULT_SALT_LENGTH,
    private val hashLength: Int = DEFAULT_HASH_LENGTH,
    private val parallelism: Int = DEFAULT_PARALLELISM,
    private val memory: Int = 1 shl DEFAULT_MEMORY,
    private val iterations: Int = DEFAULT_ITERATIONS
) : PasswordEncoder<ArgonPassword> {

    private val secureRandom = SecureRandom()

    private fun generateSalt(): ByteArray {
        val salt = ByteArray(saltLength)
        secureRandom.nextBytes(salt)
        return salt
    }

    override suspend fun encode(rawPassword: String): ArgonPassword = withContext(Dispatchers.IO) {
        val salt = generateSalt()
        val hash = ByteArray(this@PasswordEncoderImpl.hashLength)
        val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withSalt(salt)
            .withParallelism(this@PasswordEncoderImpl.parallelism)
            .withMemoryAsKB(this@PasswordEncoderImpl.memory)
            .withIterations(this@PasswordEncoderImpl.iterations)
            .build()
        val generator = Argon2BytesGenerator()
        generator.init(params)
        generator.generateBytes(rawPassword.toCharArray(), hash)
        ArgonPassword(Argon2EncodingUtils.encode(hash, params))
    }

    override suspend fun matches(rawPassword: String, encodedPassword: ArgonPassword): Boolean = withContext(Dispatchers.IO) {
        val decoded: Argon2EncodingUtils.Argon2Hash
        try {
            decoded = Argon2EncodingUtils.decode(encodedPassword.toString())
        } catch (_: IllegalArgumentException) {
            return@withContext false
        }
        val hashBytes = ByteArray(decoded.hash.size)
        val generator = Argon2BytesGenerator()
        generator.init(decoded.parameters)
        generator.generateBytes(rawPassword.toCharArray(), hashBytes)
        constantTimeArrayEquals(decoded.hash, hashBytes)
    }

    private fun constantTimeArrayEquals(expected: ByteArray, actual: ByteArray): Boolean =
        MessageDigest.isEqual(expected, actual)

    private companion object {
        const val DEFAULT_SALT_LENGTH: Int = 16
        const val DEFAULT_HASH_LENGTH: Int = 32
        const val DEFAULT_PARALLELISM: Int = 1
        const val DEFAULT_MEMORY: Int = 1 shl 14
        const val DEFAULT_ITERATIONS: Int = 2
    }
}
