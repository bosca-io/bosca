package bosca.community.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class CommunityGroupSignupTokenTest {

    private val groupId = Uuid.random()

    @Test
    fun `CommunityGroupSignupToken stores explicit properties`() {
        val created = java.time.OffsetDateTime.now()
        val expires = created.plusDays(60)
        val token = CommunityGroupSignupToken(
            token = "abc123",
            groupId = groupId,
            created = created,
            expires = expires
        )
        assertEquals("abc123", token.token)
        assertEquals(groupId, token.groupId)
        assertEquals(created, token.created)
        assertEquals(expires, token.expires)
    }

    @Test
    fun `CommunityGroupSignupToken defaults create sensible timestamps`() {
        val before = java.time.OffsetDateTime.now()
        val token = CommunityGroupSignupToken(token = "tok", groupId = groupId)
        val after = java.time.OffsetDateTime.now()
        assertTrue(token.created >= before.minusSeconds(1))
        assertTrue(token.created <= after.plusSeconds(1))
        assertTrue(token.expires.isAfter(token.created))
    }

    @Test
    fun `CommunityGroupSignupToken equality`() {
        val ts = java.time.OffsetDateTime.now()
        val exp = ts.plusDays(60)
        val a = CommunityGroupSignupToken("t", groupId, ts, exp)
        val b = CommunityGroupSignupToken("t", groupId, ts, exp)
        assertEquals(a, b)
    }
}
