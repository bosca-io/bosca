package bosca.devices.graphql

import bosca.devices.model.PushToken
import bosca.devices.model.PushProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

/**
 * Verifies that the [PushTokenController] correctly masks push notification tokens
 * when resolving the `token` field on the PushToken GraphQL type.
 *
 * Tokens longer than 8 characters have their leading characters replaced with
 * asterisks so that only the last 8 characters are visible, preventing accidental
 * exposure of full token values in API responses.
 */
class PushTokenControllerTest {

    private val controller = PushTokenController()

    private fun pushToken(token: String) = PushToken(
        deviceId = Uuid.NIL,
        token = token,
        provider = PushProvider.APNS,
    )

    @Test
    fun `provider exposes the issuing push service`() {
        assertEquals(PushProvider.APNS, controller.provider(pushToken("token")))
    }

    /**
     * A token longer than 8 characters should have all characters except the
     * last 8 replaced with asterisks.
     */
    @Test
    fun `token longer than 8 chars masks leading characters with asterisks`() {
        val token = "abcdefghijklmnop" // 16 chars
        val result = controller.token(pushToken(token))
        assertEquals("********ijklmnop", result)
    }

    /**
     * A token of exactly 8 characters does not exceed the masking threshold
     * and should be returned unchanged.
     */
    @Test
    fun `token exactly 8 chars is returned as-is`() {
        val token = "12345678"
        val result = controller.token(pushToken(token))
        assertEquals("12345678", result)
    }

    /**
     * A token shorter than 8 characters is below the masking threshold
     * and should be returned unchanged.
     */
    @Test
    fun `token shorter than 8 chars is returned as-is`() {
        val token = "short"
        val result = controller.token(pushToken(token))
        assertEquals("short", result)
    }

    /**
     * A token of exactly 9 characters should have only the first character
     * masked with a single asterisk, leaving the last 8 characters visible.
     */
    @Test
    fun `token of length 9 masks first char only`() {
        val token = "123456789"
        val result = controller.token(pushToken(token))
        assertEquals("*23456789", result)
    }

    /**
     * A long token (100 characters) should have 92 leading asterisks followed
     * by the last 8 characters of the original token.
     */
    @Test
    fun `long token of 100 chars masks 92 chars with asterisks`() {
        val token = "a".repeat(92) + "LAST8CHR"
        val result = controller.token(pushToken(token))
        assertEquals("*".repeat(92) + "LAST8CHR", result)
    }
}
