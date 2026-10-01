package bosca.security.encryption

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PasswordEncoderImplTest {

    private val encoder = PasswordEncoderImpl()

    @Test
    fun `encode produces non-empty hash`() = runBlocking {
        val encoded = encoder.encode("password123")
        assertTrue(encoded.hash.isNotEmpty())
    }

    @Test
    fun `encode produces argon2id formatted hash`() = runBlocking {
        val encoded = encoder.encode("myPassword")
        assertTrue(encoded.hash.startsWith("\$argon2id"))
    }

    @Test
    fun `encode produces different hashes for same password due to random salt`() = runBlocking {
        val hash1 = encoder.encode("samePassword")
        val hash2 = encoder.encode("samePassword")
        assertNotEquals(hash1.hash, hash2.hash)
    }

    @Test
    fun `matches returns true for correct password`() = runBlocking {
        val encoded = encoder.encode("correctPassword")
        assertTrue(encoder.matches("correctPassword", encoded))
    }

    @Test
    fun `matches returns false for incorrect password`() = runBlocking {
        val encoded = encoder.encode("correctPassword")
        assertFalse(encoder.matches("wrongPassword", encoded))
    }

    @Test
    fun `matches returns false for empty password against encoded`() = runBlocking {
        val encoded = encoder.encode("nonEmpty")
        assertFalse(encoder.matches("", encoded))
    }

    @Test
    fun `encode and match works for empty password`() = runBlocking {
        val encoded = encoder.encode("")
        assertTrue(encoder.matches("", encoded))
    }

    @Test
    fun `encode and match works for long password`() = runBlocking {
        val longPassword = "a".repeat(1000)
        val encoded = encoder.encode(longPassword)
        assertTrue(encoder.matches(longPassword, encoded))
    }

    @Test
    fun `matches returns false for invalid encoded hash`() = runBlocking {
        val invalid = ArgonPassword(hash = "not-a-valid-hash")
        assertFalse(encoder.matches("test", invalid))
    }
}
