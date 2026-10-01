package bosca.profile.profile.events

import bosca.profile.model.Profile
import bosca.profile.model.ProfileType
import bosca.profile.model.ProfileVisibility
import bosca.serialization.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class ProfileEventsTest {

    private fun createProfile(id: UUID = UUID.random(), principalId: UUID? = null): Profile {
        return Profile(
            id = id,
            type = ProfileType.GENERIC,
            name = "Test User",
            visibility = ProfileVisibility.PUBLIC,
            principal = principalId,
        )
    }

    // -- ProfileCreatedEvent --

    @Test
    fun `ProfileCreatedEvent stores id from constructor`() {
        val id = UUID.random()
        val event = ProfileCreatedEvent(id = id)
        assertEquals(id, event.id)
    }

    @Test
    fun `ProfileCreatedEvent secondary constructor extracts id from Profile`() {
        val profile = createProfile()
        val event = ProfileCreatedEvent(profile)
        assertEquals(profile.id, event.id)
    }

    // -- ProfileUpdatedEvent --

    @Test
    fun `ProfileUpdatedEvent stores id from constructor`() {
        val id = UUID.random()
        val event = ProfileUpdatedEvent(id = id)
        assertEquals(id, event.id)
    }

    @Test
    fun `ProfileUpdatedEvent secondary constructor extracts id from Profile`() {
        val profile = createProfile()
        val event = ProfileUpdatedEvent(profile)
        assertEquals(profile.id, event.id)
    }

    // -- ProfileDeletedEvent --

    @Test
    fun `ProfileDeletedEvent stores id from constructor`() {
        val id = UUID.random()
        val event = ProfileDeletedEvent(id = id)
        assertEquals(id, event.id)
    }

    @Test
    fun `ProfileDeletedEvent secondary constructor extracts id from Profile`() {
        val principalId = UUID.random()
        val profile = createProfile(principalId = principalId)
        val event = ProfileDeletedEvent(profile)
        assertEquals(profile.id, event.id)
        assertEquals(principalId, event.principalId)
    }

    @Test
    fun `ProfileUnlinkedEvent retains the former principal for cleanup`() {
        val id = UUID.random()
        val principalId = UUID.random()

        val event = ProfileUnlinkedEvent(id, principalId)

        assertEquals(id, event.id)
        assertEquals(principalId, event.principalId)
    }

    // -- Interface conformance --

    @Test
    fun `ProfileCreatedEvent implements ProfileEvent`() {
        val event: ProfileEvent = ProfileCreatedEvent(id = UUID.random())
        assertEquals(event.id, event.id)
    }

    @Test
    fun `ProfileUpdatedEvent implements ProfileEvent`() {
        val event: ProfileEvent = ProfileUpdatedEvent(id = UUID.random())
        assertEquals(event.id, event.id)
    }

    @Test
    fun `ProfileDeletedEvent implements ProfileEvent`() {
        val event: ProfileEvent = ProfileDeletedEvent(id = UUID.random())
        assertEquals(event.id, event.id)
    }

    @Test
    fun `ProfileUnlinkedEvent implements ProfileEvent`() {
        val event: ProfileEvent = ProfileUnlinkedEvent(UUID.random(), UUID.random())
        assertEquals(event.id, event.id)
    }

    @Test
    fun `All event types preserve distinct ids`() {
        val id1 = UUID.random()
        val id2 = UUID.random()
        val id3 = UUID.random()
        val created = ProfileCreatedEvent(id = id1)
        val updated = ProfileUpdatedEvent(id = id2)
        val deleted = ProfileDeletedEvent(id = id3)
        assertEquals(id1, created.id)
        assertEquals(id2, updated.id)
        assertEquals(id3, deleted.id)
    }
}
