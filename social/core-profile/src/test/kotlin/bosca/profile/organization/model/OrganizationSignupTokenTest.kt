package bosca.profile.organization.model

import bosca.serialization.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OrganizationSignupTokenTest {

    @Test
    fun `OrganizationSignupToken creation with required fields`() {
        val orgId = UUID.random()
        val token = OrganizationSignupToken(
            token = "abc123",
            organizationId = orgId,
            groupId = null
        )
        assertEquals("abc123", token.token)
        assertEquals(orgId, token.organizationId)
        assertNull(token.groupId)
    }

    @Test
    fun `OrganizationSignupToken creation with groupId`() {
        val orgId = UUID.random()
        val groupId = UUID.random()
        val token = OrganizationSignupToken(
            token = "token-value",
            organizationId = orgId,
            groupId = groupId
        )
        assertEquals(groupId, token.groupId)
    }

    @Test
    fun `OrganizationSignupToken created has default value`() {
        val token = OrganizationSignupToken(
            token = "test",
            organizationId = UUID.random(),
            groupId = null
        )
        assertNotNull(token.created)
    }

    @Test
    fun `OrganizationSignupToken expires has default value`() {
        val token = OrganizationSignupToken(
            token = "test",
            organizationId = UUID.random(),
            groupId = null
        )
        assertNotNull(token.expires)
    }

    @Test
    fun `OrganizationSignupToken expires is after created by default`() {
        val token = OrganizationSignupToken(
            token = "test",
            organizationId = UUID.random(),
            groupId = null
        )
        assertTrue(token.expires.isAfter(token.created))
    }

    @Test
    fun `OrganizationSignupToken data class equality`() {
        val orgId = UUID.random()
        val created = java.time.OffsetDateTime.now()
        val expires = created.plusDays(60)
        val a = OrganizationSignupToken(
            token = "t1",
            organizationId = orgId,
            groupId = null,
            created = created,
            expires = expires
        )
        val b = OrganizationSignupToken(
            token = "t1",
            organizationId = orgId,
            groupId = null,
            created = created,
            expires = expires
        )
        assertEquals(a, b)
    }

    @Test
    fun `OrganizationSignupToken hashCode is consistent for equal instances`() {
        val orgId = UUID.random()
        val created = java.time.OffsetDateTime.now()
        val expires = created.plusDays(30)
        val a = OrganizationSignupToken(
            token = "hash-test",
            organizationId = orgId,
            groupId = null,
            created = created,
            expires = expires
        )
        val b = OrganizationSignupToken(
            token = "hash-test",
            organizationId = orgId,
            groupId = null,
            created = created,
            expires = expires
        )
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `OrganizationSignupToken copy changes token value`() {
        val original = OrganizationSignupToken(
            token = "original-token",
            organizationId = UUID.random(),
            groupId = null
        )
        val copy = original.copy(token = "new-token")
        assertEquals("new-token", copy.token)
        assertEquals(original.organizationId, copy.organizationId)
    }

    @Test
    fun `OrganizationSignupToken toString contains token value`() {
        val token = OrganizationSignupToken(
            token = "visible-token",
            organizationId = UUID.random(),
            groupId = null
        )
        assertTrue(token.toString().contains("visible-token"))
    }
}
