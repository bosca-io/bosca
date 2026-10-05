package bosca.content.timeevent.graphql

import bosca.content.timeevent.model.TimeEvent
import bosca.content.timeevent.service.TimeEventService
import bosca.serialization.UUID
import io.mockk.*
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TimeEventControllerTest {

    private val timeEventService = mockk<TimeEventService>()

    private val controller = TimeEventController(timeEventService)

    @AfterTest
    fun tearDown() {
        clearAllMocks()
    }

    private fun createTimeEvent(
        id: UUID = UUID.random(),
        metadataId: UUID = UUID.random(),
        metadataVersion: Int = 1,
        type: String = "verse",
        startOffsetMs: Long = 1000,
        endOffsetMs: Long? = 2000,
        sort: Int = 0,
        attributes: JsonElement = JsonObject(emptyMap())
    ) = TimeEvent(
        id = id,
        metadataId = metadataId,
        metadataVersion = metadataVersion,
        type = type,
        startOffsetMs = startOffsetMs,
        endOffsetMs = endOffsetMs,
        sort = sort,
        attributes = attributes
    )

    @Test
    fun `id returns timeEvent id`() {
        val id = UUID.random()
        assertEquals(id, controller.id(createTimeEvent(id = id)))
    }

    @Test
    fun `metadataId returns timeEvent metadataId`() {
        val metadataId = UUID.random()
        assertEquals(metadataId, controller.metadataId(createTimeEvent(metadataId = metadataId)))
    }

    @Test
    fun `metadataVersion returns timeEvent metadataVersion`() {
        assertEquals(3, controller.metadataVersion(createTimeEvent(metadataVersion = 3)))
    }

    @Test
    fun `startOffsetMs returns timeEvent startOffsetMs`() {
        assertEquals(5000L, controller.startOffsetMs(createTimeEvent(startOffsetMs = 5000)))
    }

    @Test
    fun `endOffsetMs returns timeEvent endOffsetMs`() {
        assertEquals(8000L, controller.endOffsetMs(createTimeEvent(endOffsetMs = 8000)))
    }

    @Test
    fun `endOffsetMs returns null when not set`() {
        assertNull(controller.endOffsetMs(createTimeEvent(endOffsetMs = null)))
    }

    @Test
    fun `sort returns timeEvent sort`() {
        assertEquals(5, controller.sort(createTimeEvent(sort = 5)))
    }

    @Test
    fun `durationMs returns difference between end and start`() {
        val event = createTimeEvent(startOffsetMs = 1000, endOffsetMs = 3500)

        assertEquals(2500L, controller.durationMs(event))
    }

    @Test
    fun `durationMs returns null when endOffsetMs is null`() {
        val event = createTimeEvent(startOffsetMs = 1000, endOffsetMs = null)

        assertNull(controller.durationMs(event))
    }
}
