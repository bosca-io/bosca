package bosca.server.graphql.controllers

import bosca.serialization.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.ExperimentalUuidApi

@OptIn(ExperimentalUuidApi::class)
class CollectionEventTest {

    @Test
    fun `data class properties return correct values`() {
        val id = UUID.random()
        val event = CollectionEvent(type = "state.change", id = id, languageTag = "en")

        assertEquals("state.change", event.type)
        assertEquals(id, event.id)
        assertEquals("en", event.languageTag)
    }

    @Test
    fun `languageTag defaults to null`() {
        val id = UUID.random()
        val event = CollectionEvent(type = "updated", id = id)

        assertNull(event.languageTag)
    }

    @Test
    fun `controller fields delegate to event properties`() {
        val id = UUID.random()
        val event = CollectionEvent(type = "test", id = id, languageTag = "fr")
        val controller = CollectionEventController()

        assertEquals("test", controller.type(event))
        assertEquals(id, controller.id(event))
        assertEquals("fr", controller.languageTag(event))
    }
}
