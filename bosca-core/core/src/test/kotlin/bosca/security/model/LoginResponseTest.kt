package bosca.security.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.uuid.Uuid

class LoginResponseTest {

    private val principalId = Uuid.parse("550e8400-e29b-41d4-a716-446655440000")
    private val token = Token(expiresAt = 1000, issuedAt = 500, token = "jwt-token")

    @Test
    fun fieldsArePreserved() {
        val response = LoginResponse(principalId = principalId, refreshToken = "refresh-123", token = token)
        assertEquals(principalId, response.principalId)
        assertEquals("refresh-123", response.refreshToken)
        assertEquals(token, response.token)
    }

    @Test
    fun nullableRefreshToken() {
        val response = LoginResponse(principalId = principalId, refreshToken = null, token = token)
        assertNull(response.refreshToken)
    }

    @Test
    fun `equality copy defaults and hashes cover every response field`() {
        val base = LoginResponse(principalId, "refresh", token, accountCreated = true, originator = "studio")
        assertEquals(base, base)
        assertEquals(base, base.copy())
        assertEquals(base.hashCode(), base.copy().hashCode())
        assertFalse(base.equals(null))
        assertFalse(base.equals("login"))
        listOf(
            base.copy(principalId = Uuid.random()),
            base.copy(refreshToken = null),
            base.copy(token = token.copy(token = "different")),
            base.copy(accountCreated = false),
            base.copy(originator = null),
        ).forEach { assertNotEquals(base, it) }
        LoginResponse(principalId, "refresh", token, accountCreated = true).hashCode()
        LoginResponse(principalId, "refresh", token, originator = "studio").hashCode()
    }
}
