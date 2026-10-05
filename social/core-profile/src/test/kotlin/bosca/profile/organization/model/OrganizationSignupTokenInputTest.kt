package bosca.profile.organization.model

import bosca.serialization.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OrganizationSignupTokenInputTest {

    @Test
    fun `toOrganizationSignupToken generates a non-empty token`() {
        val input = OrganizationSignupTokenInput(type = OrganizationSignupGroupType.USERS)
        val orgId = UUID.random()
        val groupId = UUID.random()
        val token = input.toOrganizationSignupToken(orgId, groupId)
        assertNotNull(token.token)
        assertTrue(token.token.isNotEmpty())
    }

    @Test
    fun `toOrganizationSignupToken sets correct organizationId`() {
        val input = OrganizationSignupTokenInput(type = OrganizationSignupGroupType.ADMINISTRATORS)
        val orgId = UUID.random()
        val groupId = UUID.random()
        val token = input.toOrganizationSignupToken(orgId, groupId)
        assertEquals(orgId, token.organizationId)
    }

    @Test
    fun `toOrganizationSignupToken sets correct groupId`() {
        val input = OrganizationSignupTokenInput(type = OrganizationSignupGroupType.USERS)
        val orgId = UUID.random()
        val groupId = UUID.random()
        val token = input.toOrganizationSignupToken(orgId, groupId)
        assertEquals(groupId, token.groupId)
    }

    @Test
    fun `toOrganizationSignupToken sets expiry 60 days in future`() {
        val input = OrganizationSignupTokenInput(type = OrganizationSignupGroupType.USERS)
        val orgId = UUID.random()
        val groupId = UUID.random()
        val token = input.toOrganizationSignupToken(orgId, groupId)
        assertTrue(token.expires.isAfter(token.created))
        val daysBetween = java.time.Duration.between(token.created, token.expires).toDays()
        assertTrue(daysBetween in 59..61, "Expected ~60 days between created and expires, got $daysBetween")
    }

    @Test
    fun `toOrganizationSignupToken generates unique tokens`() {
        val input = OrganizationSignupTokenInput(type = OrganizationSignupGroupType.USERS)
        val orgId = UUID.random()
        val groupId = UUID.random()
        val token1 = input.toOrganizationSignupToken(orgId, groupId)
        val token2 = input.toOrganizationSignupToken(orgId, groupId)
        assertNotEquals(token1.token, token2.token)
    }

    @Test
    fun `generated token is base64 URL-encoded without padding`() {
        val input = OrganizationSignupTokenInput(type = OrganizationSignupGroupType.USERS)
        val token = input.toOrganizationSignupToken(UUID.random(), UUID.random())
        // Base64 URL-safe characters only, no padding '='
        assertTrue(token.token.matches(Regex("[A-Za-z0-9_-]+")))
    }

    @Test
    fun `generated token has expected length for 32 bytes`() {
        val input = OrganizationSignupTokenInput(type = OrganizationSignupGroupType.USERS)
        val token = input.toOrganizationSignupToken(UUID.random(), UUID.random())
        // 32 bytes in Base64 without padding = 43 characters
        assertEquals(43, token.token.length)
    }
}
