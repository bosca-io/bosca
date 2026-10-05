package bosca.profile.organization.model

import bosca.serialization.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class OrganizationMemberTest {

    @Test
    fun `creation preserves field values`() {
        val orgId = UUID.random()
        val principalId = UUID.random()
        val member = OrganizationMember(
            organizationId = orgId,
            principalId = principalId
        )
        assertEquals(orgId, member.organizationId)
        assertEquals(principalId, member.principalId)
    }

    @Test
    fun `data class equality for same values`() {
        val orgId = UUID.random()
        val principalId = UUID.random()
        val a = OrganizationMember(organizationId = orgId, principalId = principalId)
        val b = OrganizationMember(organizationId = orgId, principalId = principalId)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `data class inequality for different organizationId`() {
        val principalId = UUID.random()
        val a = OrganizationMember(organizationId = UUID.random(), principalId = principalId)
        val b = OrganizationMember(organizationId = UUID.random(), principalId = principalId)
        assertNotEquals(a, b)
    }

    @Test
    fun `data class inequality for different principalId`() {
        val orgId = UUID.random()
        val a = OrganizationMember(organizationId = orgId, principalId = UUID.random())
        val b = OrganizationMember(organizationId = orgId, principalId = UUID.random())
        assertNotEquals(a, b)
    }

    @Test
    fun `copy changes specific fields`() {
        val original = OrganizationMember(
            organizationId = UUID.random(),
            principalId = UUID.random()
        )
        val newPrincipal = UUID.random()
        val copy = original.copy(principalId = newPrincipal)
        assertEquals(newPrincipal, copy.principalId)
        assertEquals(original.organizationId, copy.organizationId)
    }

    @Test
    fun `toString contains field values`() {
        val orgId = UUID.random()
        val member = OrganizationMember(
            organizationId = orgId,
            principalId = UUID.random()
        )
        val str = member.toString()
        assertTrue(str.contains(orgId.toString()))
    }
}
