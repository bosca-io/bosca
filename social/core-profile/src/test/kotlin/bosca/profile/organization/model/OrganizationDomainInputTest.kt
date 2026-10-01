package bosca.profile.organization.model

import bosca.serialization.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OrganizationDomainInputTest {

    @Test
    fun `toDomain converts domain to lowercase`() {
        val input = OrganizationDomainInput(domain = "Example.COM", autoJoin = false)
        val orgId = UUID.random()
        val domain = input.toDomain(orgId)
        assertEquals("example.com", domain.domain)
    }

    @Test
    fun `toDomain sets organizationId correctly`() {
        val input = OrganizationDomainInput(domain = "test.com", autoJoin = true)
        val orgId = UUID.random()
        val domain = input.toDomain(orgId)
        assertEquals(orgId, domain.organizationId)
    }

    @Test
    fun `toDomain preserves autoJoin flag`() {
        val inputTrue = OrganizationDomainInput(domain = "test.com", autoJoin = true)
        assertTrue(inputTrue.toDomain(UUID.random()).autoJoin)

        val inputFalse = OrganizationDomainInput(domain = "test.com", autoJoin = false)
        assertFalse(inputFalse.toDomain(UUID.random()).autoJoin)
    }

    @Test
    fun `toDomain sets groupId from defaultGroupId`() {
        val groupId = UUID.random()
        val input = OrganizationDomainInput(domain = "test.com", autoJoin = true, defaultGroupId = groupId)
        val domain = input.toDomain(UUID.random())
        assertEquals(groupId, domain.groupId)
    }

    @Test
    fun `toDomain sets null groupId when defaultGroupId is null`() {
        val input = OrganizationDomainInput(domain = "test.com", autoJoin = true, defaultGroupId = null)
        val domain = input.toDomain(UUID.random())
        assertNull(domain.groupId)
    }

    @Test
    fun `toDomain handles already-lowercase domain`() {
        val input = OrganizationDomainInput(domain = "already.lowercase.com", autoJoin = false)
        val domain = input.toDomain(UUID.random())
        assertEquals("already.lowercase.com", domain.domain)
    }
}
