package bosca.content.timeevent.service

import bosca.content.timeevent.model.TimeEvent
import bosca.content.timeevent.model.TimeEventInput
import bosca.content.timeevent.events.TIME_EVENT_CHANGED_CHANNEL
import bosca.content.timeevent.events.TimeEventChanged
import bosca.content.timeevent.model.TimeEventMetadataRelationship
import bosca.content.timeevent.model.TimeEventMetadataRelationshipInput
import bosca.content.timeevent.repository.TimeEventMetadataRelationshipRepository
import bosca.content.timeevent.repository.TimeEventRepository
import bosca.content.timeevent.repository.TimeEventTypeAttributeRepository
import bosca.db.transaction
import bosca.pubsub.PubSubService
import bosca.serialization.UUID
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Coverage-focused tests for [TimeEventServiceImpl] targeting the branches and
 * methods not exercised by [TimeEventServiceImplTest]: require-failure arms on
 * add/edit/setTimeEvents, batch id operations, notify=false relationship paths,
 * merge attribute logic, related-metadata resolution, and the null time-event
 * short-circuit in publishTimeEventChanged.
 */
class TimeEventServiceImplCoverageTest {

    private val repository = mockk<TimeEventRepository>(relaxUnitFun = true)
    private val attributeRepository = mockk<TimeEventTypeAttributeRepository>(relaxUnitFun = true)
    private val relationshipRepository = mockk<TimeEventMetadataRelationshipRepository>(relaxUnitFun = true)
    private val pubSubService = mockk<PubSubService>(relaxUnitFun = true)
    private val json = Json { ignoreUnknownKeys = true }

    private val service = TimeEventServiceImpl(repository, attributeRepository, relationshipRepository, pubSubService, json)

    @BeforeTest
    fun setup() {
        mockkStatic("bosca.db.ConnectionManagerKt")
        coEvery { transaction<Any>(any()) } coAnswers {
            val block = firstArg<suspend () -> Any>()
            block()
        }
    }

    @AfterTest
    fun tearDown() {
        unmockkStatic("bosca.db.ConnectionManagerKt")
        clearAllMocks()
    }

    // --- addTimeEvent require branches ---

    @Test
    fun `addTimeEvent rejects negative startOffsetMs`() = runTest {
        val metadataId = UUID.random()
        val input = TimeEventInput(type = "verse", startOffsetMs = -1)

        val ex = assertFailsWith<IllegalArgumentException> {
            service.addTimeEvent(metadataId, 1, input)
        }
        assertEquals("startOffsetMs must be non-negative", ex.message)
        coVerify(exactly = 0) { repository.addTimeEvent(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `addTimeEvent rejects endOffsetMs less than startOffsetMs`() = runTest {
        val metadataId = UUID.random()
        val input = TimeEventInput(type = "verse", startOffsetMs = 1000, endOffsetMs = 500)

        val ex = assertFailsWith<IllegalArgumentException> {
            service.addTimeEvent(metadataId, 1, input)
        }
        assertEquals("endOffsetMs must be >= startOffsetMs", ex.message)
        coVerify(exactly = 0) { repository.addTimeEvent(any(), any(), any(), any(), any(), any(), any()) }
    }

    // --- editTimeEvent require branches ---

    @Test
    fun `editTimeEvent rejects negative startOffsetMs`() = runTest {
        val id = UUID.random()
        val input = TimeEventInput(type = "verse", startOffsetMs = -5)

        val ex = assertFailsWith<IllegalArgumentException> {
            service.editTimeEvent(id, input)
        }
        assertEquals("startOffsetMs must be non-negative", ex.message)
        coVerify(exactly = 0) { repository.updateTimeEvent(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `editTimeEvent rejects endOffsetMs less than startOffsetMs`() = runTest {
        val id = UUID.random()
        val input = TimeEventInput(type = "verse", startOffsetMs = 2000, endOffsetMs = 1000)

        val ex = assertFailsWith<IllegalArgumentException> {
            service.editTimeEvent(id, input)
        }
        assertEquals("endOffsetMs must be >= startOffsetMs", ex.message)
        coVerify(exactly = 0) { repository.updateTimeEvent(any(), any(), any(), any(), any(), any()) }
    }

    // --- setTimeEvents require branches + publish ---

    @Test
    fun `setTimeEvents rejects negative startOffsetMs before mutating`() = runTest {
        val metadataId = UUID.random()
        val inputs = listOf(TimeEventInput(type = "verse", startOffsetMs = -1))

        val ex = assertFailsWith<IllegalArgumentException> {
            service.setTimeEvents(metadataId, 1, inputs)
        }
        assertEquals("startOffsetMs must be non-negative", ex.message)
        coVerify(exactly = 0) { repository.deleteAllTimeEvents(any(), any()) }
        coVerify(exactly = 0) { pubSubService.publish(any(), TimeEventChanged.serializer(), any<TimeEventChanged>()) }
    }

    @Test
    fun `setTimeEvents rejects endOffsetMs less than startOffsetMs before mutating`() = runTest {
        val metadataId = UUID.random()
        val inputs = listOf(TimeEventInput(type = "verse", startOffsetMs = 1000, endOffsetMs = 100))

        val ex = assertFailsWith<IllegalArgumentException> {
            service.setTimeEvents(metadataId, 1, inputs)
        }
        assertEquals("endOffsetMs must be >= startOffsetMs", ex.message)
        coVerify(exactly = 0) { repository.deleteAllTimeEvents(any(), any()) }
    }

    @Test
    fun `setTimeEvents publishes TimeEventChanged with the input count`() = runTest {
        val metadataId = UUID.random()
        val inputs = listOf(
            TimeEventInput(type = "verse", startOffsetMs = 0, endOffsetMs = 1000, sort = 0),
            TimeEventInput(type = "verse", startOffsetMs = 1000, sort = null, attributes = null)
        )
        val event1 = createTimeEvent(metadataId = metadataId, startOffsetMs = 0, endOffsetMs = 1000, sort = 0)
        val event2 = createTimeEvent(metadataId = metadataId, startOffsetMs = 1000, endOffsetMs = null, sort = 0)
        coEvery { repository.addTimeEvent(metadataId, 2, "verse", 0, 1000, 0, JsonObject(emptyMap())) } returns event1
        coEvery { repository.addTimeEvent(metadataId, 2, "verse", 1000, null, 0, JsonObject(emptyMap())) } returns event2

        val result = service.setTimeEvents(metadataId, 2, inputs)

        assertEquals(listOf(event1, event2), result)
        coVerify(exactly = 1) { repository.deleteAllTimeEvents(metadataId, 2) }
        coVerify(exactly = 1) {
            pubSubService.publish(
                TIME_EVENT_CHANGED_CHANNEL,
                TimeEventChanged.serializer(),
                TimeEventChanged(metadataId = metadataId, metadataVersion = 2, eventCount = 2)
            )
        }
    }

    // --- getTimeEventsByIds ---

    @Test
    fun `getTimeEventsByIds delegates to repository`() = runTest {
        val ids = listOf(UUID.random(), UUID.random())
        val events = listOf(createTimeEvent(id = ids[0]), createTimeEvent(id = ids[1]))
        coEvery { repository.getTimeEventsByIds(ids) } returns events

        val result = service.getTimeEventsByIds(ids)

        assertEquals(events, result)
    }

    // --- deleteTimeEvents ---

    @Test
    fun `deleteTimeEvents returns zero and skips repository for empty ids`() = runTest {
        val result = service.deleteTimeEvents(emptyList())

        assertEquals(0, result)
        coVerify(exactly = 0) { repository.deleteTimeEventsByIds(any()) }
    }

    @Test
    fun `deleteTimeEvents deletes non-empty ids and returns count`() = runTest {
        val ids = listOf(UUID.random(), UUID.random(), UUID.random())

        val result = service.deleteTimeEvents(ids)

        assertEquals(3, result)
        coVerify(exactly = 1) { repository.deleteTimeEventsByIds(ids) }
    }

    // --- addMetadataRelationship notify=false ---

    @Test
    fun `addMetadataRelationship with notify false does not publish`() = runTest {
        val timeEventId = UUID.random()
        val metadataId = UUID.random()
        val input = TimeEventMetadataRelationshipInput(metadataId = metadataId, relationship = "slide")
        val expected = TimeEventMetadataRelationship(timeEventId = timeEventId, metadataId = metadataId, relationship = "slide")
        coEvery { relationshipRepository.add(expected) } returns expected

        val result = service.addMetadataRelationship(timeEventId, input, notify = false)

        assertEquals(expected, result)
        coVerify(exactly = 0) { repository.getTimeEvent(any()) }
        coVerify(exactly = 0) { pubSubService.publish(any(), TimeEventChanged.serializer(), any<TimeEventChanged>()) }
    }

    // --- deleteMetadataRelationship notify=false ---

    @Test
    fun `deleteMetadataRelationship with notify false does not publish`() = runTest {
        val timeEventId = UUID.random()
        val metadataId = UUID.random()

        service.deleteMetadataRelationship(timeEventId, metadataId, "slide", notify = false)

        coVerify(exactly = 1) { relationshipRepository.delete(timeEventId, metadataId, "slide") }
        coVerify(exactly = 0) { repository.getTimeEvent(any()) }
        coVerify(exactly = 0) { pubSubService.publish(any(), TimeEventChanged.serializer(), any<TimeEventChanged>()) }
    }

    // --- setMetadataRelationships notify=false ---

    @Test
    fun `setMetadataRelationships with notify false does not publish`() = runTest {
        val timeEventId = UUID.random()
        val metadataId = UUID.random()
        val inputs = listOf(TimeEventMetadataRelationshipInput(metadataId = metadataId, relationship = "slide"))
        val rel = TimeEventMetadataRelationship(timeEventId = timeEventId, metadataId = metadataId, relationship = "slide")
        coEvery { relationshipRepository.add(rel) } returns rel

        val result = service.setMetadataRelationships(timeEventId, inputs, notify = false)

        assertEquals(listOf(rel), result)
        coVerify(exactly = 1) { relationshipRepository.deleteByTimeEventId(timeEventId) }
        coVerify(exactly = 0) { repository.getTimeEvent(any()) }
        coVerify(exactly = 0) { pubSubService.publish(any(), TimeEventChanged.serializer(), any<TimeEventChanged>()) }
    }

    // --- mergeMetadataRelationshipAttributes ---

    @Test
    fun `mergeMetadataRelationshipAttributes rejects non-object attributes`() = runTest {
        val timeEventId = UUID.random()
        val metadataId = UUID.random()

        val ex = assertFailsWith<IllegalStateException> {
            service.mergeMetadataRelationshipAttributes(timeEventId, metadataId, "slide", JsonArray(emptyList()))
        }
        assertEquals("must be an object", ex.message)
        coVerify(exactly = 0) { relationshipRepository.setAttributes(any(), any(), any(), any()) }
    }

    @Test
    fun `mergeMetadataRelationshipAttributes merges over existing object attributes`() = runTest {
        val timeEventId = UUID.random()
        val metadataId = UUID.random()
        val existing = JsonObject(mapOf("a" to JsonPrimitive(1)))
        val incoming = JsonObject(mapOf("b" to JsonPrimitive(2)))
        coEvery { relationshipRepository.getAttributes(timeEventId, metadataId, "slide") } returns existing
        val captured = slot<JsonElement>()
        coEvery { relationshipRepository.setAttributes(timeEventId, metadataId, "slide", capture(captured)) } returns Unit

        service.mergeMetadataRelationshipAttributes(timeEventId, metadataId, "slide", incoming)

        val merged = captured.captured as JsonObject
        assertEquals(JsonPrimitive(1), merged["a"])
        assertEquals(JsonPrimitive(2), merged["b"])
    }

    @Test
    fun `mergeMetadataRelationshipAttributes uses empty object when existing is null`() = runTest {
        val timeEventId = UUID.random()
        val metadataId = UUID.random()
        val incoming = JsonObject(mapOf("x" to JsonPrimitive("y")))
        coEvery { relationshipRepository.getAttributes(timeEventId, metadataId, "slide") } returns null
        val captured = slot<JsonElement>()
        coEvery { relationshipRepository.setAttributes(timeEventId, metadataId, "slide", capture(captured)) } returns Unit

        service.mergeMetadataRelationshipAttributes(timeEventId, metadataId, "slide", incoming)

        assertEquals(incoming, captured.captured)
    }

    @Test
    fun `mergeMetadataRelationshipAttributes uses empty object when existing is not an object`() = runTest {
        val timeEventId = UUID.random()
        val metadataId = UUID.random()
        val incoming = JsonObject(mapOf("x" to JsonPrimitive("y")))
        // getAttributes returns a non-object JSON element -> takeIf {it is JsonObject} yields null
        coEvery { relationshipRepository.getAttributes(timeEventId, metadataId, "slide") } returns JsonPrimitive("scalar")
        val captured = slot<JsonElement>()
        coEvery { relationshipRepository.setAttributes(timeEventId, metadataId, "slide", capture(captured)) } returns Unit

        service.mergeMetadataRelationshipAttributes(timeEventId, metadataId, "slide", incoming)

        assertEquals(incoming, captured.captured)
    }

    @Test
    fun `mergeMetadataRelationshipAttributes is a no-op when merge does not change existing`() = runTest {
        val timeEventId = UUID.random()
        val metadataId = UUID.random()
        val existing = JsonObject(mapOf("a" to JsonPrimitive(1)))
        // incoming duplicates existing -> merged == existing -> early return, no write
        val incoming = JsonObject(mapOf("a" to JsonPrimitive(1)))
        coEvery { relationshipRepository.getAttributes(timeEventId, metadataId, "slide") } returns existing

        service.mergeMetadataRelationshipAttributes(timeEventId, metadataId, "slide", incoming)

        coVerify(exactly = 0) { relationshipRepository.setAttributes(any(), any(), any(), any()) }
    }

    // --- getRelatedMetadataIds ---

    @Test
    fun `getRelatedMetadataIds returns empty when there are no time events`() = runTest {
        val metadataId = UUID.random()
        coEvery { repository.getTimeEvents(metadataId, 1) } returns emptyList()

        val result = service.getRelatedMetadataIds(metadataId, 1)

        assertEquals(emptyList(), result)
        coVerify(exactly = 0) { relationshipRepository.getByTimeEventIds(any()) }
    }

    @Test
    fun `getRelatedMetadataIds returns distinct related metadata ids`() = runTest {
        val metadataId = UUID.random()
        val te1 = createTimeEvent()
        val te2 = createTimeEvent()
        val related1 = UUID.random()
        val related2 = UUID.random()
        coEvery { repository.getTimeEvents(metadataId, 1) } returns listOf(te1, te2)
        coEvery { relationshipRepository.getByTimeEventIds(listOf(te1.id, te2.id)) } returns listOf(
            TimeEventMetadataRelationship(timeEventId = te1.id, metadataId = related1, relationship = "slide"),
            TimeEventMetadataRelationship(timeEventId = te1.id, metadataId = related2, relationship = "resource"),
            // duplicate metadataId under a different time event -> deduped by distinct()
            TimeEventMetadataRelationship(timeEventId = te2.id, metadataId = related1, relationship = "reference")
        )

        val result = service.getRelatedMetadataIds(metadataId, 1)

        assertEquals(2, result.size)
        assertTrue(result.contains(related1))
        assertTrue(result.contains(related2))
    }

    // --- publishTimeEventChanged null short-circuit ---

    @Test
    fun `editMetadataRelationshipAttributes skips publish when time event is missing`() = runTest {
        val timeEventId = UUID.random()
        val metadataId = UUID.random()
        val attrs = JsonObject(mapOf("page" to JsonPrimitive(5)))
        // getTimeEvent returns null -> publishTimeEventChanged returns early, no publish
        coEvery { repository.getTimeEvent(timeEventId) } returns null

        service.editMetadataRelationshipAttributes(timeEventId, metadataId, "slide", attrs)

        coVerify(exactly = 1) { relationshipRepository.setAttributes(timeEventId, metadataId, "slide", attrs) }
        coVerify(exactly = 0) { pubSubService.publish(any(), TimeEventChanged.serializer(), any<TimeEventChanged>()) }
    }

    // --- Helpers ---

    private fun createTimeEvent(
        id: UUID = UUID.random(),
        metadataId: UUID = UUID.random(),
        metadataVersion: Int = 1,
        type: String = "verse",
        startOffsetMs: Long = 1000,
        endOffsetMs: Long? = 2000,
        sort: Int = 0,
        attributes: JsonObject = JsonObject(emptyMap())
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
}
