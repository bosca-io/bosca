package bosca.profile.organization.model

import bosca.security.model.PermissionAction
import bosca.serialization.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OrganizationModelTest {

    // -- OrganizationAddress --

    @Test
    fun `OrganizationAddress creation with all fields`() {
        val addr = OrganizationAddress(
            street1 = "123 Main St",
            street2 = "Suite 100",
            city = "Springfield",
            region = "IL",
            country = "US",
            postalCode = "62701"
        )
        assertEquals("123 Main St", addr.street1)
        assertEquals("Suite 100", addr.street2)
        assertEquals("Springfield", addr.city)
        assertEquals("IL", addr.region)
        assertEquals("US", addr.country)
        assertEquals("62701", addr.postalCode)
    }

    @Test
    fun `OrganizationAddress nullable fields can be null`() {
        val addr = OrganizationAddress(
            street1 = "456 Oak Ave",
            street2 = null,
            city = null,
            region = null,
            country = null,
            postalCode = null
        )
        assertEquals("456 Oak Ave", addr.street1)
        assertNull(addr.street2)
        assertNull(addr.city)
        assertNull(addr.region)
        assertNull(addr.country)
        assertNull(addr.postalCode)
    }

    @Test
    fun `OrganizationAddress data class equality`() {
        val a = OrganizationAddress("s1", null, "c", null, null, null)
        val b = OrganizationAddress("s1", null, "c", null, null, null)
        assertEquals(a, b)
    }

    // -- OrganizationContact --

    @Test
    fun `OrganizationContact creation with all fields`() {
        val contact = OrganizationContact(
            firstName = "John",
            lastName = "Doe",
            email = "john@example.com",
            title = "CTO",
            phoneNumber = "+1234567890",
            type = OrganizationContactType.PRIMARY
        )
        assertEquals("John", contact.firstName)
        assertEquals("Doe", contact.lastName)
        assertEquals("john@example.com", contact.email)
        assertEquals("CTO", contact.title)
        assertEquals("+1234567890", contact.phoneNumber)
        assertEquals(OrganizationContactType.PRIMARY, contact.type)
    }

    @Test
    fun `OrganizationContact phoneNumber defaults to null`() {
        val contact = OrganizationContact(
            firstName = "Jane",
            lastName = "Smith",
            email = "jane@test.com",
            title = "Dev",
            type = OrganizationContactType.TECHNICAL
        )
        assertNull(contact.phoneNumber)
    }

    @Test
    fun `OrganizationContactType enum values`() {
        val values = OrganizationContactType.entries
        assertTrue(values.contains(OrganizationContactType.PRIMARY))
        assertTrue(values.contains(OrganizationContactType.SECONDARY))
        assertTrue(values.contains(OrganizationContactType.TECHNICAL))
        assertEquals(3, values.size)
    }

    // -- OrganizationDetails --

    @Test
    fun `OrganizationDetails creation with all fields`() {
        val addr = OrganizationAddress("s1", null, "c", null, null, null)
        val contact = OrganizationContact("F", "L", "e@e.com", "T", type = OrganizationContactType.PRIMARY)
        val details = OrganizationDetails(
            tradition = "Baptist",
            size = "1000",
            country = "US",
            addresses = listOf(addr),
            contacts = listOf(contact)
        )
        assertEquals("Baptist", details.tradition)
        assertEquals("1000", details.size)
        assertEquals("US", details.country)
        assertEquals(1, details.addresses.size)
        assertEquals(1, details.contacts.size)
    }

    @Test
    fun `OrganizationDetails optional fields default to null`() {
        val details = OrganizationDetails(
            addresses = emptyList(),
            contacts = emptyList()
        )
        assertNull(details.tradition)
        assertNull(details.size)
        assertNull(details.country)
        assertTrue(details.addresses.isEmpty())
        assertTrue(details.contacts.isEmpty())
    }

    // -- OrganizationDomain --

    @Test
    fun `OrganizationDomain creation with all fields`() {
        val orgId = UUID.random()
        val groupId = UUID.random()
        val domain = OrganizationDomain(
            organizationId = orgId,
            domain = "example.com",
            autoJoin = true,
            groupId = groupId
        )
        assertEquals(orgId, domain.organizationId)
        assertEquals("example.com", domain.domain)
        assertTrue(domain.autoJoin)
        assertEquals(groupId, domain.groupId)
    }

    @Test
    fun `OrganizationDomain groupId defaults to null`() {
        val domain = OrganizationDomain(
            organizationId = UUID.random(),
            domain = "test.com",
            autoJoin = false
        )
        assertNull(domain.groupId)
    }

    // -- OrganizationMember --

    @Test
    fun `OrganizationMember creation`() {
        val orgId = UUID.random()
        val principalId = UUID.random()
        val member = OrganizationMember(organizationId = orgId, principalId = principalId)
        assertEquals(orgId, member.organizationId)
        assertEquals(principalId, member.principalId)
    }

    @Test
    fun `OrganizationMember data class equality`() {
        val orgId = UUID.random()
        val principalId = UUID.random()
        val a = OrganizationMember(organizationId = orgId, principalId = principalId)
        val b = OrganizationMember(organizationId = orgId, principalId = principalId)
        assertEquals(a, b)
    }

    // -- OrganizationPermission --

    @Test
    fun `OrganizationPermission creation and computed entityId`() {
        val orgId = UUID.random()
        val groupId = UUID.random()
        val perm = OrganizationPermission(
            organizationId = orgId,
            groupId = groupId,
            action = PermissionAction.VIEW
        )
        assertEquals(orgId, perm.organizationId)
        assertEquals(groupId, perm.groupId)
        assertEquals(PermissionAction.VIEW, perm.action)
        assertEquals(orgId, perm.entityId, "entityId should delegate to organizationId")
    }

    @Test
    fun `OrganizationPermission with different actions`() {
        val orgId = UUID.random()
        val groupId = UUID.random()
        val editPerm = OrganizationPermission(organizationId = orgId, groupId = groupId, action = PermissionAction.EDIT)
        val deletePerm = OrganizationPermission(organizationId = orgId, groupId = groupId, action = PermissionAction.DELETE)
        assertEquals(PermissionAction.EDIT, editPerm.action)
        assertEquals(PermissionAction.DELETE, deletePerm.action)
    }
}
