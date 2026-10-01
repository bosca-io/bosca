package bosca.security.encryption

import bosca.serialization.UUID
import bosca.server.BoscaApplication
import bosca.server.config.ApplicationConfig
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse

class EncryptionServiceImplTest {

    @Test
    fun `encrypts and decrypts with a fresh nonce for every value`() = runTest {
        val service = service("primary-key")
        val id = UUID.random()
        val plaintext = "security-sensitive-value".toByteArray()

        val first = service.encrypt(plaintext, id)
        val second = service.encrypt(plaintext, id)

        assertContentEquals(plaintext, service.decrypt(first, id))
        assertContentEquals(plaintext, service.decrypt(second, id))
        assertFalse(first.nonce.contentEquals(second.nonce))
        assertFalse(first.data.contentEquals(second.data))
    }

    @Test
    fun `rejects ciphertext encrypted with another key`() = runTest {
        val id = UUID.random()
        val encrypted = service("first-key").encrypt("secret".toByteArray(), id)

        assertFails {
            service("second-key").decrypt(encrypted, id)
        }
    }

    private fun service(key: String): EncryptionServiceImpl {
        val config = ApplicationConfig.load(
            """
            security:
              encryption:
                key: $key
            """.trimIndent().byteInputStream(),
        )
        return EncryptionServiceImpl(BoscaApplication(config))
    }
}
