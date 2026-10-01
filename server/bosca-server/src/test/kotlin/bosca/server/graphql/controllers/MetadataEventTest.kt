package bosca.server.graphql.controllers

import bosca.serialization.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.ExperimentalUuidApi

@OptIn(ExperimentalUuidApi::class)
class MetadataEventTest {

    @Test
    fun `data class properties return correct values`() {
        val id = UUID.random()
        val event = MetadataEvent(type = "state.change", id = id, version = 5)

        assertEquals("state.change", event.type)
        assertEquals(id, event.id)
        assertEquals(5, event.version)
    }

    @Test
    fun `controller fields delegate to event properties`() {
        val id = UUID.random()
        val event = MetadataEvent(type = "updated", id = id, version = 3)
        val controller = MetadataEventController()

        assertEquals("updated", controller.type(event))
        assertEquals(id, controller.id(event))
        assertEquals(3, controller.version(event))
    }
}
