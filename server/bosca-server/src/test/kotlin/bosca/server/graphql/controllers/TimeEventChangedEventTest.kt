package bosca.server.graphql.controllers

import bosca.serialization.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.ExperimentalUuidApi

@OptIn(ExperimentalUuidApi::class)
class TimeEventChangedEventTest {

    @Test
    fun `data class properties return correct values`() {
        val id = UUID.random()
        val event = TimeEventChangedEvent(metadataId = id, metadataVersion = 3, eventCount = 10)

        assertEquals(id, event.metadataId)
        assertEquals(3, event.metadataVersion)
        assertEquals(10, event.eventCount)
    }

    @Test
    fun `controller fields delegate to event properties`() {
        val id = UUID.random()
        val event = TimeEventChangedEvent(metadataId = id, metadataVersion = 2, eventCount = 5)
        val controller = TimeEventChangedEventController()

        assertEquals(id, controller.metadataId(event))
        assertEquals(2, controller.metadataVersion(event))
        assertEquals(5, controller.eventCount(event))
    }
}
