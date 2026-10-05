package bosca.content.collection.events

import bosca.content.collection.model.Collection
import bosca.content.collection.model.CollectionLanguageVariant
import bosca.serialization.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CollectionUpdatedEventTest {

    @Test
    fun `CollectionUpdated without languageTag defaults to null`() {
        val event = CollectionUpdated(id = UUID.random())
        assertNull(event.languageTag)
    }

    @Test
    fun `CollectionUpdated carries languageTag`() {
        val id = UUID.random()
        val event = CollectionUpdated(id = id, languageTag = "es")
        assertEquals(id, event.id)
        assertEquals("es", event.languageTag)
    }

    @Test
    fun `CollectionUpdated with null languageTag for collection updates`() {
        val event = CollectionUpdated(id = UUID.random(), languageTag = null)
        assertNull(event.languageTag)
    }

    @Test
    fun `CollectionUpdated from Collection has null languageTag`() {
        val collection = Collection(
            id = UUID.random(),
            name = "test",
            languageTag = "en",
            workflowStateId = "draft"
        )
        val event = CollectionUpdated(collection)
        assertEquals(collection.id, event.id)
        assertNull(event.languageTag)
    }

    @Test
    fun `CollectionUpdated from CollectionLanguageVariant carries languageTag`() {
        val variant = CollectionLanguageVariant(
            id = UUID.random(),
            languageTag = "fr",
            name = "French Variant"
        )
        val event = CollectionUpdated(variant)
        assertEquals(variant.id, event.id)
        assertEquals("fr", event.languageTag)
    }

    @Test
    fun `CollectionUpdated supplementaryId is always null`() {
        val event = CollectionUpdated(id = UUID.random(), languageTag = "es")
        assertNull(event.supplementaryId)
    }
}
