@file:OptIn(ExperimentalUuidApi::class)

package bosca.storage.events

import bosca.serialization.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.uuid.ExperimentalUuidApi

class StorageSystemEventsTest {

    @Test
    fun `StorageSystemAdded stores id`() {
        val id = UUID.random()
        val event = StorageSystemAdded(id)
        assertEquals(id, event.id)
    }

    @Test
    fun `StorageSystemEdited stores id`() {
        val id = UUID.random()
        val event = StorageSystemEdited(id)
        assertEquals(id, event.id)
    }

    @Test
    fun `StorageSystemAdded implements StorageSystemEvent`() {
        val id = UUID.random()
        val event: StorageSystemEvent = StorageSystemAdded(id)
        assertEquals(id, event.id)
    }

    @Test
    fun `StorageSystemEdited implements StorageSystemEvent`() {
        val id = UUID.random()
        val event: StorageSystemEvent = StorageSystemEdited(id)
        assertEquals(id, event.id)
    }

    @Test
    fun `Different event types with same id are not equal`() {
        val id = UUID.random()
        val added = StorageSystemAdded(id)
        val edited = StorageSystemEdited(id)
        assertNotEquals<Any>(added, edited)
    }
}
