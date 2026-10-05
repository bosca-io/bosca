package bosca.profile.organization.model

import bosca.serialization.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OrganizationDomainTest {

    @Test
    fun `creation with required fields`() {
        val orgId = UUID.random()
        val domain = OrganizationDomain(
            organizationId = orgId,
            domain = "example.com",
            autoJoin = true
        )
        assertEquals(orgId, domain.organizationId)
        assertEquals("example.com", domain.domain)
        assertTrue(domain.autoJoin)
    }

    @Test
    fun `groupId defaults to null`() {
        val domain = OrganizationDomain(
            organizationId = UUID.random(),
            domain = "example.com",
            autoJoin = false
        )
        assertNull(domain.groupId)
    }

    @Test
    fun `creation with groupId`() {
        val groupId = UUID.random()
        val domain = OrganizationDomain(
            organizationId = UUID.random(),
            domain = "example.com",
            autoJoin = true,
            groupId = groupId
        )
        assertEquals(groupId, domain.groupId)
    }

    @Test
    fun `autoJoin false`() {
        val domain = OrganizationDomain(
            organizationId = UUID.random(),
            domain = "test.org",
            autoJoin = false
        )
        assertFalse(domain.autoJoin)
    }

    @Test
    fun `data class equality`() {
        val orgId = UUID.random()
        val a = OrganizationDomain(organizationId = orgId, domain = "test.com", autoJoin = true)
        val b = OrganizationDomain(organizationId = orgId, domain = "test.com", autoJoin = true)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `data class inequality for different domain`() {
        val orgId = UUID.random()
        val a = OrganizationDomain(organizationId = orgId, domain = "a.com", autoJoin = true)
        val b = OrganizationDomain(organizationId = orgId, domain = "b.com", autoJoin = true)
        assertNotEquals(a, b)
    }

    @Test
    fun `copy changes specific fields`() {
        val original = OrganizationDomain(
            organizationId = UUID.random(),
            domain = "old.com",
            autoJoin = false
        )
        val copy = original.copy(domain = "new.com", autoJoin = true)
        assertEquals("new.com", copy.domain)
        assertTrue(copy.autoJoin)
        assertEquals(original.organizationId, copy.organizationId)
    }

    @Test
    fun `toString contains field values`() {
        val domain = OrganizationDomain(
            organizationId = UUID.random(),
            domain = "example.com",
            autoJoin = true
        )
        val str = domain.toString()
        assertTrue(str.contains("example.com"))
    }
}
