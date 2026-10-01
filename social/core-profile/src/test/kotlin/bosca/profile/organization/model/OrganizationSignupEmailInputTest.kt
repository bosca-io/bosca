package bosca.profile.organization.model

import bosca.serialization.UUID
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OrganizationSignupEmailInputTest {

    @Test
    fun `toOrganizationSignupEmail sets correct email`() {
        val input = OrganizationSignupEmailInput(email = "user@example.com", type = OrganizationSignupGroupType.USERS)
        val orgId = UUID.random()
        val groupId = UUID.random()
        val result = input.toOrganizationSignupEmail(orgId, groupId)
        assertEquals("user@example.com", result.email)
    }

    @Test
    fun `toOrganizationSignupEmail sets correct organizationId`() {
        val input = OrganizationSignupEmailInput(email = "test@test.com", type = OrganizationSignupGroupType.ADMINISTRATORS)
        val orgId = UUID.random()
        val groupId = UUID.random()
        val result = input.toOrganizationSignupEmail(orgId, groupId)
        assertEquals(orgId, result.organizationId)
    }

    @Test
    fun `toOrganizationSignupEmail sets correct groupId`() {
        val input = OrganizationSignupEmailInput(email = "test@test.com", type = OrganizationSignupGroupType.USERS)
        val orgId = UUID.random()
        val groupId = UUID.random()
        val result = input.toOrganizationSignupEmail(orgId, groupId)
        assertEquals(groupId, result.groupId)
    }

    @Test
    fun `toOrganizationSignupEmail created timestamp is near now`() {
        val input = OrganizationSignupEmailInput(email = "a@b.com", type = OrganizationSignupGroupType.UNKNOWN)
        val before = OffsetDateTime.now()
        val result = input.toOrganizationSignupEmail(UUID.random(), UUID.random())
        val after = OffsetDateTime.now()
        assertTrue(
            !result.created.isBefore(before.minusSeconds(1)) && !result.created.isAfter(after.plusSeconds(1)),
            "Created timestamp should be approximately now"
        )
    }

    @Test
    fun `toOrganizationSignupEmail expires is approximately 60 days after created`() {
        val input = OrganizationSignupEmailInput(email = "a@b.com", type = OrganizationSignupGroupType.USERS)
        val result = input.toOrganizationSignupEmail(UUID.random(), UUID.random())
        val daysBetween = ChronoUnit.DAYS.between(result.created, result.expires)
        assertTrue(
            daysBetween in 59..61,
            "Expires should be approximately 60 days after created, but was $daysBetween days"
        )
    }

    @Test
    fun `OrganizationSignupEmailInput data class equality`() {
        val a = OrganizationSignupEmailInput(email = "x@y.com", type = OrganizationSignupGroupType.ADMINISTRATORS)
        val b = OrganizationSignupEmailInput(email = "x@y.com", type = OrganizationSignupGroupType.ADMINISTRATORS)
        assertEquals(a, b)
    }

    @Test
    fun `OrganizationSignupGroupType enum values exist`() {
        val values = OrganizationSignupGroupType.entries
        assertTrue(values.contains(OrganizationSignupGroupType.ADMINISTRATORS))
        assertTrue(values.contains(OrganizationSignupGroupType.USERS))
        assertTrue(values.contains(OrganizationSignupGroupType.UNKNOWN))
        assertEquals(3, values.size)
    }

    @Test
    fun `OrganizationSignupEmailInput fields are accessible`() {
        val input = OrganizationSignupEmailInput(email = "admin@org.com", type = OrganizationSignupGroupType.ADMINISTRATORS)
        assertEquals("admin@org.com", input.email)
        assertEquals(OrganizationSignupGroupType.ADMINISTRATORS, input.type)
    }
}
