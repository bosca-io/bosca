package bosca.security.session

import bosca.security.model.LoginResponse
import bosca.security.model.Token
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class SessionTest {

    @Test
    fun `Session stores token string directly`() {
        val session = Session("my-jwt-token")
        assertEquals("my-jwt-token", session.token)
    }

    @Test
    fun `Session from non-admin LoginResponse uses token directly`() {
        val token = Token(expiresAt = 9999, issuedAt = 1000, token = "jwt-value")
        val response = LoginResponse(principalId = Uuid.random(), refreshToken = null, token = token)
        val session = Session(response, admin = false)
        assertEquals("jwt-value", session.token)
    }

    @Test
    fun `Session from admin LoginResponse prefixes with admin`() {
        val token = Token(expiresAt = 9999, issuedAt = 1000, token = "jwt-value")
        val response = LoginResponse(principalId = Uuid.random(), refreshToken = null, token = token)
        val session = Session(response, admin = true)
        assertEquals("admin:::jwt-value", session.token)
    }

    @Test
    fun `Session admin prefix can be detected`() {
        val token = Token(expiresAt = 9999, issuedAt = 1000, token = "some-token")
        val response = LoginResponse(principalId = Uuid.random(), refreshToken = null, token = token)
        val session = Session(response, admin = true)
        assertTrue(session.token.startsWith("admin:::"))
    }

    @Test
    fun `Session non-admin does not have admin prefix`() {
        val token = Token(expiresAt = 9999, issuedAt = 1000, token = "some-token")
        val response = LoginResponse(principalId = Uuid.random(), refreshToken = null, token = token)
        val session = Session(response, admin = false)
        assertTrue(!session.token.startsWith("admin:::"))
    }

    @Test
    fun `Session equality is based on token value`() {
        val a = Session("token-abc")
        val b = Session("token-abc")
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }
}
