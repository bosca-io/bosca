package bosca.profile.organization.events

import bosca.profile.model.ProfileVisibility
import bosca.profile.organization.model.Organization
import bosca.serialization.UUID
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class OrganizationEventsTest {

    private val attrs = JsonObject(mapOf("key" to JsonPrimitive("value")))
    private val sysAttrs = JsonObject(mapOf("sys" to JsonPrimitive("data")))

    private fun createOrganization(id: UUID = UUID.random()): Organization {
        return Organization(
            id = id,
            name = "Test Org",
            attributes = attrs,
            systemAttributes = sysAttrs,
            visibility = ProfileVisibility.PUBLIC,
            profileId = UUID.random()
        )
    }

    // -- OrganizationCreated --

    @Test
    fun `OrganizationCreated stores id from constructor`() {
        val id = UUID.random()
        val event = OrganizationCreated(id = id)
        assertEquals(id, event.id)
    }

    @Test
    fun `OrganizationCreated secondary constructor extracts id from Organization`() {
        val org = createOrganization()
        val event = OrganizationCreated(org)
        assertEquals(org.id, event.id)
    }

    // -- OrganizationUpdated --

    @Test
    fun `OrganizationUpdated stores id from constructor`() {
        val id = UUID.random()
        val event = OrganizationUpdated(id = id)
        assertEquals(id, event.id)
    }

    @Test
    fun `OrganizationUpdated secondary constructor extracts id from Organization`() {
        val org = createOrganization()
        val event = OrganizationUpdated(org)
        assertEquals(org.id, event.id)
    }

    // -- OrganizationDeleted --

    @Test
    fun `OrganizationDeleted stores id from constructor`() {
        val id = UUID.random()
        val event = OrganizationDeleted(id = id)
        assertEquals(id, event.id)
    }

    @Test
    fun `OrganizationDeleted secondary constructor extracts id from Organization`() {
        val org = createOrganization()
        val event = OrganizationDeleted(org)
        assertEquals(org.id, event.id)
    }

    // -- OrganizationDomainAdded --

    @Test
    fun `OrganizationDomainAdded stores id from constructor`() {
        val id = UUID.random()
        val event = OrganizationDomainAdded(id = id)
        assertEquals(id, event.id)
    }

    @Test
    fun `OrganizationDomainAdded secondary constructor extracts id from Organization`() {
        val org = createOrganization()
        val event = OrganizationDomainAdded(org)
        assertEquals(org.id, event.id)
    }

    // -- OrganizationMemberAdded --

    @Test
    fun `OrganizationMemberAdded stores id and memberId`() {
        val id = UUID.random()
        val memberId = UUID.random()
        val event = OrganizationMemberAdded(id = id, memberId = memberId)
        assertEquals(id, event.id)
        assertEquals(memberId, event.memberId)
    }

    @Test
    fun `OrganizationMemberAdded secondary constructor extracts id from Organization`() {
        val org = createOrganization()
        val memberId = UUID.random()
        val event = OrganizationMemberAdded(org, memberId)
        assertEquals(org.id, event.id)
        assertEquals(memberId, event.memberId)
    }

    // -- OrganizationMemberRemoved --

    @Test
    fun `OrganizationMemberRemoved stores id and memberId`() {
        val id = UUID.random()
        val memberId = UUID.random()
        val event = OrganizationMemberRemoved(id = id, memberId = memberId)
        assertEquals(id, event.id)
        assertEquals(memberId, event.memberId)
    }

    @Test
    fun `OrganizationMemberRemoved secondary constructor extracts id from Organization`() {
        val org = createOrganization()
        val memberId = UUID.random()
        val event = OrganizationMemberRemoved(org, memberId)
        assertEquals(org.id, event.id)
        assertEquals(memberId, event.memberId)
    }

    // -- Interface conformance --

    @Test
    fun `OrganizationCreated implements OrganizationEvent`() {
        val event: OrganizationEvent = OrganizationCreated(id = UUID.random())
        assertEquals(event.id, event.id)
    }

    @Test
    fun `OrganizationUpdated implements OrganizationEvent`() {
        val event: OrganizationEvent = OrganizationUpdated(id = UUID.random())
        assertEquals(event.id, event.id)
    }

    @Test
    fun `OrganizationDeleted implements OrganizationEvent`() {
        val event: OrganizationEvent = OrganizationDeleted(id = UUID.random())
        assertEquals(event.id, event.id)
    }
}
