package bosca.content.timeevent.model

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.Uuid

class TimeEventTest {

    @Test
    fun `TimeEvent stores all required fields`() {
        val metadataId = Uuid.random()
        val event = TimeEvent(
            metadataId = metadataId,
            metadataVersion = 1,
            type = "highlight",
            startOffsetMs = 1000L
        )
        assertEquals(metadataId, event.metadataId)
        assertEquals(1, event.metadataVersion)
        assertEquals("highlight", event.type)
        assertEquals(1000L, event.startOffsetMs)
    }

    @Test
    fun `TimeEvent id defaults to NIL`() {
        val event = TimeEvent(
            metadataId = Uuid.random(),
            metadataVersion = 1,
            type = "note",
            startOffsetMs = 0L
        )
        assertEquals(Uuid.NIL, event.id)
    }

    @Test
    fun `TimeEvent optional fields default to null or zero`() {
        val event = TimeEvent(
            metadataId = Uuid.random(),
            metadataVersion = 1,
            type = "marker",
            startOffsetMs = 500L
        )
        assertNull(event.endOffsetMs)
        assertEquals(0, event.sort)
    }

    @Test
    fun `TimeEvent attributes defaults to empty JsonObject`() {
        val event = TimeEvent(
            metadataId = Uuid.random(),
            metadataVersion = 1,
            type = "marker",
            startOffsetMs = 0L
        )
        assertEquals(JsonObject(emptyMap()), event.attributes)
    }

    @Test
    fun `TimeEvent stores all optional fields`() {
        val metadataId = Uuid.random()
        val id = Uuid.random()
        val attrs = JsonObject(mapOf("color" to JsonPrimitive("red")))
        val event = TimeEvent(
            id = id,
            metadataId = metadataId,
            metadataVersion = 2,
            type = "bookmark",
            startOffsetMs = 1000L,
            endOffsetMs = 5000L,
            sort = 3,
            attributes = attrs
        )
        assertEquals(id, event.id)
        assertEquals(5000L, event.endOffsetMs)
        assertEquals(3, event.sort)
        assertEquals(attrs, event.attributes)
    }

    @Test
    fun `TimeEvent equality with same timestamps`() {
        val id = Uuid.random()
        val metadataId = Uuid.random()
        val now = OffsetDateTime.now()
        val event1 = TimeEvent(id = id, metadataId = metadataId, metadataVersion = 1, type = "t", startOffsetMs = 100L, created = now, modified = now)
        val event2 = TimeEvent(id = id, metadataId = metadataId, metadataVersion = 1, type = "t", startOffsetMs = 100L, created = now, modified = now)
        assertEquals(event1, event2)
    }

    @Test
    fun `TimeEvent copy`() {
        val event = TimeEvent(
            metadataId = Uuid.random(),
            metadataVersion = 1,
            type = "marker",
            startOffsetMs = 0L
        )
        val copied = event.copy(type = "highlight", startOffsetMs = 2000L)
        assertEquals("highlight", copied.type)
        assertEquals(2000L, copied.startOffsetMs)
        assertEquals(event.metadataId, copied.metadataId)
    }
}
