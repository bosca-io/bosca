package bosca.content.collection.events

import bosca.serialization.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CollectionStateChangedEventTest {

    @Test
    fun `CollectionStateChanged without languageTag defaults to null`() {
        val event = CollectionStateChanged(id = UUID.random())
        assertNull(event.languageTag)
    }

    @Test
    fun `CollectionStateChanged carries languageTag`() {
        val id = UUID.random()
        val event = CollectionStateChanged(id = id, languageTag = "es")
        assertEquals(id, event.id)
        assertEquals("es", event.languageTag)
    }

    @Test
    fun `CollectionStateChangedComplete without languageTag defaults to null`() {
        val event = CollectionStateChangedComplete(id = UUID.random())
        assertNull(event.languageTag)
    }

    @Test
    fun `CollectionStateChangedComplete carries languageTag`() {
        val id = UUID.random()
        val event = CollectionStateChangedComplete(id = id, languageTag = "fr")
        assertEquals(id, event.id)
        assertEquals("fr", event.languageTag)
    }

    @Test
    fun `CollectionStateChanged with null languageTag for collection transitions`() {
        val event = CollectionStateChanged(id = UUID.random(), languageTag = null)
        assertNull(event.languageTag)
    }

    @Test
    fun `CollectionStateChangedComplete with null languageTag for collection transitions`() {
        val event = CollectionStateChangedComplete(id = UUID.random(), languageTag = null)
        assertNull(event.languageTag)
    }
}
