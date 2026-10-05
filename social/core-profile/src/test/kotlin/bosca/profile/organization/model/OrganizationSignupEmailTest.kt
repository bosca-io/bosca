package bosca.profile.organization.model

import bosca.serialization.UUID
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class OrganizationSignupEmailTest {

    @Test
    fun `OrganizationSignupEmail creation preserves all fields`() {
        val orgId = UUID.random()
        val groupId = UUID.random()
        val created = OffsetDateTime.now()
        val expires = created.plusDays(30)
        val email = OrganizationSignupEmail(
            email = "user@example.com",
            organizationId = orgId,
            groupId = groupId,
            created = created,
            expires = expires
        )
        assertEquals("user@example.com", email.email)
        assertEquals(orgId, email.organizationId)
        assertEquals(groupId, email.groupId)
        assertEquals(created, email.created)
        assertEquals(expires, email.expires)
    }

    @Test
    fun `OrganizationSignupEmail with null groupId`() {
        val email = OrganizationSignupEmail(
            email = "test@test.com",
            organizationId = UUID.random(),
            groupId = null,
            created = OffsetDateTime.now(),
            expires = OffsetDateTime.now().plusDays(60)
        )
        assertEquals(null, email.groupId)
    }

    @Test
    fun `OrganizationSignupEmail data class equality`() {
        val orgId = UUID.random()
        val groupId = UUID.random()
        val created = OffsetDateTime.now()
        val expires = created.plusDays(30)
        val a = OrganizationSignupEmail(
            email = "same@test.com",
            organizationId = orgId,
            groupId = groupId,
            created = created,
            expires = expires
        )
        val b = OrganizationSignupEmail(
            email = "same@test.com",
            organizationId = orgId,
            groupId = groupId,
            created = created,
            expires = expires
        )
        assertEquals(a, b)
    }

    @Test
    fun `OrganizationSignupEmail with different emails are not equal`() {
        val orgId = UUID.random()
        val created = OffsetDateTime.now()
        val expires = created.plusDays(30)
        val a = OrganizationSignupEmail(
            email = "a@test.com",
            organizationId = orgId,
            groupId = null,
            created = created,
            expires = expires
        )
        val b = OrganizationSignupEmail(
            email = "b@test.com",
            organizationId = orgId,
            groupId = null,
            created = created,
            expires = expires
        )
        assertNotEquals(a, b)
    }

    @Test
    fun `OrganizationSignupEmail copy changes email`() {
        val original = OrganizationSignupEmail(
            email = "old@test.com",
            organizationId = UUID.random(),
            groupId = null,
            created = OffsetDateTime.now(),
            expires = OffsetDateTime.now().plusDays(60)
        )
        val copy = original.copy(email = "new@test.com")
        assertEquals("new@test.com", copy.email)
        assertEquals(original.organizationId, copy.organizationId)
    }

    @Test
    fun `OrganizationSignupEmail hashCode is consistent for equal instances`() {
        val orgId = UUID.random()
        val created = OffsetDateTime.now()
        val expires = created.plusDays(30)
        val a = OrganizationSignupEmail(
            email = "hash@test.com",
            organizationId = orgId,
            groupId = null,
            created = created,
            expires = expires
        )
        val b = OrganizationSignupEmail(
            email = "hash@test.com",
            organizationId = orgId,
            groupId = null,
            created = created,
            expires = expires
        )
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `OrganizationSignupEmail toString contains email address`() {
        val email = OrganizationSignupEmail(
            email = "visible@domain.com",
            organizationId = UUID.random(),
            groupId = null,
            created = OffsetDateTime.now(),
            expires = OffsetDateTime.now().plusDays(30)
        )
        assertTrue(email.toString().contains("visible@domain.com"))
    }
}
