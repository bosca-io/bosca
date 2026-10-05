package bosca.content.collection.events

import bosca.serialization.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CollectionLanguageVariantDeletedEventTest {

    @Test
    fun `CollectionLanguageVariantDeleted carries id and languageTag`() {
        val id = UUID.random()
        val event = CollectionLanguageVariantDeleted(id = id, languageTag = "es")
        assertEquals(id, event.id)
        assertEquals("es", event.languageTag)
    }

    @Test
    fun `CollectionLanguageVariantDeleted supplementaryId is always null`() {
        val event = CollectionLanguageVariantDeleted(id = UUID.random(), languageTag = "fr")
        assertNull(event.supplementaryId)
    }
}
