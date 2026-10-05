package bosca.security.encryption

import bosca.firebase.Password
import bosca.security.model.ScryptCredentialAttributes
import io.mockk.every
import io.mockk.mockkConstructor
import io.mockk.unmockkConstructor
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ScryptPasswordEncoderImplTest {

    private val configuration = ScryptConfiguration(
        base64SaltSeparator = "separator",
        base64SignerKey = "signer",
    )
    private val encoded = ScryptPassword(
        ScryptCredentialAttributes(
            salt = "salt",
            identifier = "person@example.com",
            passwordHash = "hash",
        ),
    )

    @BeforeTest
    fun setUp() {
        mockkConstructor(Password::class)
        every { anyConstructed<Password>().close() } returns Unit
    }

    @AfterTest
    fun tearDown() {
        unmockkConstructor(Password::class)
    }

    @Test
    fun `matches delegates all configured values to firebase scrypt`() = runTest {
        every {
            anyConstructed<Password>().matches("salt", "hash", "password")
        } returns true

        assertTrue(ScryptPasswordEncoderImpl(configuration).matches("password", encoded))
    }

    @Test
    fun `matches returns false when the verifier fails`() = runTest {
        every {
            anyConstructed<Password>().matches(any(), any(), any())
        } throws IllegalStateException("invalid scrypt input")

        assertFalse(ScryptPasswordEncoderImpl(configuration).matches("password", encoded))
    }

    @Test
    fun `encode is deliberately unsupported`() = runTest {
        assertFailsWith<UnsupportedOperationException> {
            ScryptPasswordEncoderImpl(configuration).encode("password")
        }
    }

    @Test
    fun `scrypt configuration equality evaluates every property`() {
        assertEquals(configuration, configuration.copy())
        assertNotEquals(configuration, configuration.copy(algorithm = "OTHER"))
        assertNotEquals(configuration, configuration.copy(base64SaltSeparator = "other"))
        assertNotEquals(configuration, configuration.copy(base64SignerKey = "other"))
        assertNotEquals(configuration, configuration.copy(memCost = 15))
        assertNotEquals(configuration, configuration.copy(rounds = 9))
        assertFalse(configuration.equals(null))
        assertFalse(configuration.equals("configuration"))
    }

    @Test
    fun `scrypt password equality evaluates its credential`() {
        assertEquals(encoded, encoded.copy())
        assertNotEquals(
            encoded,
            encoded.copy(password = encoded.password.copy(passwordHash = "other")),
        )
        assertFalse(encoded.equals(null))
        assertFalse(encoded.equals("password"))
    }

    @Test
    fun `scrypt configuration serialization restores optional defaults`() {
        val decoded = Json.decodeFromString<ScryptConfiguration>(
            """{"base64SaltSeparator":"separator","base64SignerKey":"signer"}""",
        )

        assertEquals(configuration, decoded)
        assertEquals(configuration, Json.decodeFromString(Json.encodeToString(configuration)))
    }
}
