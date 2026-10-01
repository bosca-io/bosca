package bosca.firebase

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PasswordTest {

    @Test
    fun `known Firebase password hash matches`() {
        passwordVerifier().use { verifier ->
            assertTrue(verifier.matches(SALT, PASSWORD_HASH, PASSWORD))
        }
    }

    @Test
    fun `incorrect password does not match Firebase hash`() {
        passwordVerifier().use { verifier ->
            assertFalse(verifier.matches(SALT, PASSWORD_HASH, "incorrect-password"))
        }
    }

    private fun passwordVerifier() = Password(
        base64SaltSeparator = SALT_SEPARATOR,
        base64SignerKey = SIGNER_KEY,
        memCost = 14u,
        rounds = 8u
    )

    private companion object {
        const val SALT_SEPARATOR = "Bw=="
        const val SIGNER_KEY =
            "jxspr8Ki0RYycVU8zykbdLGjFQ3McFUH0uiiTvC8pVMXAn210wjLNmdZJzxUECKbm0QsEmYUSDzZvpjeJ9WmXA=="
        const val PASSWORD = "user1password"
        const val SALT = "42xEC+ixf3L2lw=="
        const val PASSWORD_HASH =
            "lSrfV15cpx95/sZS2W9c9Kp6i/LVgQNDNC/qzrCnh1SAyZvqmZqAjTdn3aoItz+VHjoZilo78198JAdRuid5lQ=="
    }
}
