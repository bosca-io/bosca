package bosca.security.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.uuid.Uuid

class RefreshTokenTest {

    private val principalId = Uuid.parse("550e8400-e29b-41d4-a716-446655440000")

    @Test
    fun fieldsArePreserved() {
        val rt = RefreshToken(principalId = principalId, token = "refresh-abc-123")
        assertEquals(principalId, rt.principalId)
        assertEquals("refresh-abc-123", rt.token)
    }

    @Test
    fun defaultTimestamps() {
        val rt = RefreshToken(principalId = principalId, token = "t")
        assertNotNull(rt.created)
        assertNotNull(rt.expires)
    }

    @Test
    fun expiresAfterCreated() {
        val rt = RefreshToken(principalId = principalId, token = "t")
        assert(rt.expires.isAfter(rt.created))
    }
}
