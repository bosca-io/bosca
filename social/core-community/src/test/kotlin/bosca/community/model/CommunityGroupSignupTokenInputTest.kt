package bosca.community.model

import kotlin.uuid.Uuid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CommunityGroupSignupTokenInputTest {

    @Test
    fun `toCommunityGroupSignupToken generates non-empty token`() {
        val input = CommunityGroupSignupTokenInput()
        val groupId = Uuid.random()
        val token = input.toCommunityGroupSignupToken(groupId)
        assertNotNull(token.token)
        assertTrue(token.token.isNotEmpty())
    }

    @Test
    fun `toCommunityGroupSignupToken sets correct groupId`() {
        val input = CommunityGroupSignupTokenInput()
        val groupId = Uuid.random()
        val token = input.toCommunityGroupSignupToken(groupId)
        assertEquals(groupId, token.groupId)
    }

    @Test
    fun `toCommunityGroupSignupToken sets expiry 60 days from creation`() {
        val input = CommunityGroupSignupTokenInput()
        val token = input.toCommunityGroupSignupToken(Uuid.random())
        val daysBetween = java.time.Duration.between(token.created, token.expires).toDays()
        assertTrue(daysBetween in 59..61, "Expected ~60 days, got $daysBetween")
    }

    @Test
    fun `toCommunityGroupSignupToken generates unique tokens each call`() {
        val input = CommunityGroupSignupTokenInput()
        val groupId = Uuid.random()
        val token1 = input.toCommunityGroupSignupToken(groupId)
        val token2 = input.toCommunityGroupSignupToken(groupId)
        assertNotEquals(token1.token, token2.token)
    }

    @Test
    fun `generated token is base64 URL-safe without padding`() {
        val input = CommunityGroupSignupTokenInput()
        val token = input.toCommunityGroupSignupToken(Uuid.random())
        assertTrue(token.token.matches(Regex("[A-Za-z0-9_-]+")))
    }

    @Test
    fun `generated token has expected length for 32 bytes`() {
        val input = CommunityGroupSignupTokenInput()
        val token = input.toCommunityGroupSignupToken(Uuid.random())
        // 32 bytes Base64 without padding = 43 characters
        assertEquals(43, token.token.length)
    }
}
