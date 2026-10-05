package bosca.security

import java.security.SecureRandom
import kotlin.io.encoding.Base64

/**
 * Cryptographically-strong one-time tokens for verification links, password resets, attribute-verification
 * challenges, and account-link challenges. 256 bits of entropy, Base64 without padding. A single shared
 * definition so every flow mints tokens of identical strength and format — change the recipe in one place.
 */
object SecureTokens {

    private val secureRandom = SecureRandom()

    /** A fresh 256-bit token, Base64-encoded without padding. */
    fun generate(): String {
        val bytes = ByteArray(32) // 256 bits
        secureRandom.nextBytes(bytes)
        return Base64.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL).encode(bytes)
    }
}
