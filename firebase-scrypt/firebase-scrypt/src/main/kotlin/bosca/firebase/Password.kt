package bosca.firebase

import uniffi.firebase_scrypt_util.PasswordUtil
import uniffi.firebase_scrypt_util.UniffiLib

class Password(
    base64SaltSeparator: String,
    base64SignerKey: String,
    memCost: UInt,
    rounds: UInt
) : AutoCloseable {

    companion object {
        init {
            Class.forName(UniffiLib::class.java.name)
        }
    }

    private val password = PasswordUtil(base64SaltSeparator, base64SignerKey, memCost, rounds)

    fun matches(salt: String, passwordHash: String, password: String) = this.password.matches(salt, passwordHash, password)

    override fun close() {
        password.close()
    }
}