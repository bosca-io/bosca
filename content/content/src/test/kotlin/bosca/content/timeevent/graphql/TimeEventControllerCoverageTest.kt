package bosca.content.timeevent.graphql

import bosca.content.timeevent.model.TimeEvent
import bosca.content.timeevent.model.TimeEventMetadataRelationship
import bosca.content.timeevent.model.TimeEventType
import bosca.content.timeevent.service.TimeEventService
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import io.mockk.*
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Coverage for resolvers on [TimeEventController] not exercised by
 * [TimeEventControllerTest]: the suspend `type` (success and error arms),
 * `attributes`, `created`, `modified`, and the suspend `metadataRelationships`
 * (null/empty filter returns all; non-empty filter filters by relationship).
 */
class TimeEventControllerCoverageTest {

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
        attributes: JsonElement = JsonObject(emptyMap()),
        created: OffsetDateTime = OffsetDateTime.now(),
        modified: OffsetDateTime = OffsetDateTime.now()
    ) = TimeEvent(
        id = id,
        metadataId = metadataId,
        metadataVersion = metadataVersion,
        type = type,
        startOffsetMs = startOffsetMs,
        endOffsetMs = endOffsetMs,
        sort = sort,
        attributes = attributes,
        created = created,
        modified = modified
    )

    private fun createRelationship(
        timeEventId: UUID,
        metadataId: UUID = UUID.random(),
        relationship: String
    ) = TimeEventMetadataRelationship(
        timeEventId = timeEventId,
        metadataId = metadataId,
        relationship = relationship
    )

    @Test
    fun `type returns resolved time event type`() = runTest {
        val event = createTimeEvent(type = "chapter")
        val expected = TimeEventType(id = "chapter", name = "Chapter", description = "A chapter marker")
        coEvery { timeEventService.getType("chapter") } returns expected

        assertEquals(expected, controller.type(event))
    }

    @Test
    fun `type throws when type is unknown`() = runTest {
        val event = createTimeEvent(type = "mystery")
        coEvery { timeEventService.getType("mystery") } returns null

        val error = assertFailsWith<IllegalStateException> { controller.type(event) }
        assertEquals("Unknown time event type: mystery", error.message)
    }

    @Test
    fun `attributes returns timeEvent attributes`() {
        val attributes = JsonObject(mapOf("color" to JsonPrimitive("red")))
        val event = createTimeEvent(attributes = attributes)

        assertSame(attributes, controller.attributes(event))
    }

    @Test
    fun `created returns timeEvent created`() {
        val created = OffsetDateTime.now()
        val event = createTimeEvent(created = created)

        assertEquals(created, controller.created(event))
    }

    @Test
    fun `modified returns timeEvent modified`() {
        val modified = OffsetDateTime.now()
        val event = createTimeEvent(modified = modified)

        assertEquals(modified, controller.modified(event))
    }

    @Test
    fun `metadataRelationships returns all when filter is null`() = runTest {
        val event = createTimeEvent()
        val all = listOf(
            createRelationship(timeEventId = event.id, relationship = "slide"),
            createRelationship(timeEventId = event.id, relationship = "resource")
        )
        coEvery { timeEventService.getMetadataRelationships(event.id) } returns all

        assertEquals(all, controller.metadataRelationships(event, null))
    }

    @Test
    fun `metadataRelationships returns all when filter is empty`() = runTest {
        val event = createTimeEvent()
        val all = listOf(
            createRelationship(timeEventId = event.id, relationship = "slide")
        )
        coEvery { timeEventService.getMetadataRelationships(event.id) } returns all

        assertEquals(all, controller.metadataRelationships(event, emptyList()))
    }

    @Test
    fun `metadataRelationships filters by relationship when filter provided`() = runTest {
        val event = createTimeEvent()
        val slide = createRelationship(timeEventId = event.id, relationship = "slide")
        val resource = createRelationship(timeEventId = event.id, relationship = "resource")
        val reference = createRelationship(timeEventId = event.id, relationship = "reference")
        coEvery { timeEventService.getMetadataRelationships(event.id) } returns listOf(slide, resource, reference)

        val filtered = controller.metadataRelationships(event, listOf("slide", "reference"))

        assertEquals(listOf(slide, reference), filtered)
    }

    @Test
    fun `metadataRelationships returns empty when nothing matches filter`() = runTest {
        val event = createTimeEvent()
        val slide = createRelationship(timeEventId = event.id, relationship = "slide")
        coEvery { timeEventService.getMetadataRelationships(event.id) } returns listOf(slide)

        val filtered = controller.metadataRelationships(event, listOf("resource"))

        assertTrue(filtered.isEmpty())
    }
}
