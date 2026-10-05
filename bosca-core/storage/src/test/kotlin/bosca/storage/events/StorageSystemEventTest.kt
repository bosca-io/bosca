package bosca.storage.events

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

class StorageSystemAddedTest {

    @Test
    fun idIsPreserved() {
        val id = Uuid.parse("550e8400-e29b-41d4-a716-446655440000")
        val event = StorageSystemAdded(id)
        assertEquals(id, event.id)
    }

    @Test
    fun implementsStorageSystemEvent() {
        val event: StorageSystemEvent = StorageSystemAdded(Uuid.parse("550e8400-e29b-41d4-a716-446655440000"))
        assertEquals(Uuid.parse("550e8400-e29b-41d4-a716-446655440000"), event.id)
    }
}

class StorageSystemEditedTest {

    @Test
    fun idIsPreserved() {
        val id = Uuid.parse("660e8400-e29b-41d4-a716-446655440001")
        val event = StorageSystemEdited(id)
        assertEquals(id, event.id)
    }

    @Test
    fun implementsStorageSystemEvent() {
        val event: StorageSystemEvent = StorageSystemEdited(Uuid.parse("660e8400-e29b-41d4-a716-446655440001"))
        assertEquals(Uuid.parse("660e8400-e29b-41d4-a716-446655440001"), event.id)
    }
}
