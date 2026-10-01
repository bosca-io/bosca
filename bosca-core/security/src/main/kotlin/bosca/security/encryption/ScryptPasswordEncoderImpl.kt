package bosca.security.encryption

import bosca.firebase.Password
import bosca.security.model.EncodedPassword
import bosca.security.model.ScryptCredentialAttributes
import bosca.security.service.PasswordEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

@Serializable
data class ScryptConfiguration(
    val algorithm: String = "SCRYPT",
    val base64SaltSeparator: String,
    val base64SignerKey: String,
    val memCost: Int = 14,
    val rounds: Int = 8
)

@Serializable
data class ScryptPassword(val password: ScryptCredentialAttributes) : EncodedPassword

class ScryptPasswordEncoderImpl(
    private val configuration: ScryptConfiguration
) : PasswordEncoder<ScryptPassword> {

    override suspend fun encode(rawPassword: String): ScryptPassword {
        throw UnsupportedOperationException("Encoding passwords is not supported")
    }

    override suspend fun matches(rawPassword: String, encodedPassword: ScryptPassword): Boolean = withContext(Dispatchers.IO) {
        try {
            Password(
                configuration.base64SaltSeparator,
                configuration.base64SignerKey,
                configuration.memCost.toUInt(),
                configuration.rounds.toUInt()
            ).use {
                it.matches(encodedPassword.password.salt, encodedPassword.password.passwordHash, rawPassword)
            }
        } catch (e: Throwable) {
            log.error("Failed to verify scrypt password", e)
            false
        }
    }

    companion object {

        private val log = LoggerFactory.getLogger(ScryptPasswordEncoderImpl::class.java)
    }
}