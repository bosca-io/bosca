package bosca.content.timeevent.service

import bosca.attributes.AttributeType
import bosca.attributes.AttributeUiType
import bosca.attributes.TemplateAttributeInput
import bosca.attributes.TemplateToolInput
import bosca.content.timeevent.model.TimeEvent
import bosca.content.timeevent.model.TimeEventInput
import bosca.content.timeevent.events.TIME_EVENT_CHANGED_CHANNEL
import bosca.content.timeevent.events.TimeEventChanged
import bosca.content.timeevent.model.TimeEventMetadataRelationship
import bosca.content.timeevent.model.TimeEventMetadataRelationshipInput
import bosca.content.timeevent.model.TimeEventType
import bosca.content.timeevent.model.TimeEventTypeAttribute
import bosca.content.timeevent.model.TimeEventTypeInput
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class TimeEventServiceImplTest {

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

    // --- Time Event Types ---

    @Test
    fun `getTypes delegates to repository`() = runTest {
        val types = listOf(
            TimeEventType(id = "chapter", name = "Chapter", description = "Chapter marker"),
            TimeEventType(id = "verse", name = "Verse", description = "Verse marker")
        )
        coEvery { repository.getTypes() } returns types

        val result = service.getTypes()

        assertEquals(types, result)
        coVerify(exactly = 1) { repository.getTypes() }
    }

    @Test
    fun `getType delegates to repository`() = runTest {
        val type = TimeEventType(id = "chapter", name = "Chapter", description = "Chapter marker")
        coEvery { repository.getType("chapter") } returns type

        val result = service.getType("chapter")

        assertEquals(type, result)
    }

    @Test
    fun `getType returns null when not found`() = runTest {
        coEvery { repository.getType("missing") } returns null

        val result = service.getType("missing")

        assertNull(result)
    }

    @Test
    fun `addType passes input fields to repository`() = runTest {
        val input = TimeEventTypeInput(
            id = "chapter",
            name = "Chapter",
            description = "Chapter marker",
            schema = JsonObject(mapOf("type" to JsonPrimitive("object"))),
            configuration = JsonObject(mapOf("color" to JsonPrimitive("blue")))
        )
        val expected = TimeEventType(
            id = "chapter",
            name = "Chapter",
            description = "Chapter marker",
            schema = input.schema,
            configuration = input.configuration
        )
        coEvery {
            repository.addType(
                id = "chapter",
                name = "Chapter",
                description = "Chapter marker",
                schema = input.schema,
                configuration = input.configuration
            )
        } returns expected

        val result = service.addType(input)

        assertEquals(expected, result)
    }

    @Test
    fun `addType defaults configuration to empty JsonObject when null`() = runTest {
        val input = TimeEventTypeInput(
            id = "chapter",
            name = "Chapter",
            description = "Chapter marker",
            configuration = null
        )
        val expected = TimeEventType(id = "chapter", name = "Chapter", description = "Chapter marker")
        coEvery {
            repository.addType(
                id = "chapter",
                name = "Chapter",
                description = "Chapter marker",
                schema = null,
                configuration = JsonObject(emptyMap())
            )
        } returns expected

        val result = service.addType(input)

        assertEquals(expected, result)
    }

    @Test
    fun `editType passes id and input fields to repository`() = runTest {
        val input = TimeEventTypeInput(
            id = "chapter",
            name = "Updated Chapter",
            description = "Updated description",
            schema = null,
            configuration = JsonObject(mapOf("color" to JsonPrimitive("red")))
        )
        val expected = TimeEventType(
            id = "chapter",
            name = "Updated Chapter",
            description = "Updated description",
            configuration = input.configuration
        )
        coEvery {
            repository.updateType(
                id = "chapter",
                name = "Updated Chapter",
                description = "Updated description",
                schema = null,
                configuration = input.configuration
            )
        } returns expected

        val result = service.editType("chapter", input)

        assertEquals(expected, result)
    }

    @Test
    fun `editType returns null when type not found`() = runTest {
        val input = TimeEventTypeInput(id = "missing", name = "N", description = "D")
        coEvery {
            repository.updateType(
                id = "missing",
                name = "N",
                description = "D",
                schema = null,
                configuration = JsonObject(emptyMap())
            )
        } returns null

        val result = service.editType("missing", input)

        assertNull(result)
    }

    @Test
    fun `deleteType delegates to repository`() = runTest {
        service.deleteType("chapter")

        coVerify(exactly = 1) { repository.deleteType("chapter") }
    }

    // --- Time Event Type Attributes ---

    @Test
    fun `getTypeAttributes maps repository attributes to TemplateAttribute`() = runTest {
        val attr = TimeEventTypeAttribute(
            typeId = "chapter",
            key = "title",
            name = "Title",
            description = "Chapter title",
            type = AttributeType.STRING,
            ui = AttributeUiType.INPUT,
            sort = 0
        )
        coEvery { attributeRepository.getByTypeId("chapter") } returns listOf(attr)

        val result = service.getTypeAttributes("chapter")

        assertEquals(1, result.size)
        assertEquals("title", result[0].key)
        assertEquals("Title", result[0].name)
        assertEquals(AttributeType.STRING, result[0].type)
    }

    @Test
    fun `getTypeAttributes returns empty list when none exist`() = runTest {
        coEvery { attributeRepository.getByTypeId("empty") } returns emptyList()

        val result = service.getTypeAttributes("empty")

        assertEquals(emptyList(), result)
    }

    @Test
    fun `addTypeAttribute converts input and delegates to repository`() = runTest {
        val input = TemplateAttributeInput(
            key = "title",
            name = "Title",
            description = "Chapter title",
            type = AttributeType.STRING,
            ui = AttributeUiType.INPUT
        )
        val captured = slot<TimeEventTypeAttribute>()
        coEvery { attributeRepository.add(capture(captured)) } returns mockk()

        service.addTypeAttribute("chapter", input, 3)

        assertEquals("chapter", captured.captured.typeId)
        assertEquals("title", captured.captured.key)
        assertEquals("Title", captured.captured.name)
        assertEquals("Chapter title", captured.captured.description)
        assertEquals(AttributeType.STRING, captured.captured.type)
        assertEquals(AttributeUiType.INPUT, captured.captured.ui)
        assertEquals(3, captured.captured.sort)
        assertEquals(false, captured.captured.list)
        assertNull(captured.captured.supplementaryKey)
        assertNull(captured.captured.configuration)
        assertNull(captured.captured.tools)
    }

    @Test
    fun `addTypeAttribute converts tools from input`() = runTest {
        val toolId = UUID.random()
        val input = TemplateAttributeInput(
            key = "ref",
            name = "Reference",
            description = "Reference",
            type = AttributeType.STRING,
            ui = AttributeUiType.INPUT,
            tools = listOf(
                TemplateToolInput(id = toolId, name = "lookup", description = "Lookup tool", query = "q", resultPath = "r")
            )
        )
        val captured = slot<TimeEventTypeAttribute>()
        coEvery { attributeRepository.add(capture(captured)) } returns mockk()

        service.addTypeAttribute("chapter", input, 0)

        assertNotNull(captured.captured.tools)
    }

    @Test
    fun `addTypeAttribute preserves supplementaryKey and configuration`() = runTest {
        val config = JsonObject(mapOf("min" to JsonPrimitive(0)))
        val input = TemplateAttributeInput(
            key = "count",
            name = "Count",
            description = "Count",
            supplementaryKey = "total",
            configuration = config,
            type = AttributeType.INT,
            ui = AttributeUiType.INPUT,
            list = true
        )
        val captured = slot<TimeEventTypeAttribute>()
        coEvery { attributeRepository.add(capture(captured)) } returns mockk()

        service.addTypeAttribute("chapter", input, 1)

        assertEquals("total", captured.captured.supplementaryKey)
        assertEquals(config, captured.captured.configuration)
        assertEquals(true, captured.captured.list)
    }

    @Test
    fun `deleteTypeAttribute delegates to repository`() = runTest {
        service.deleteTypeAttribute("chapter", "title")

        coVerify(exactly = 1) { attributeRepository.deleteByTypeIdAndKey("chapter", "title") }
    }

    @Test
    fun `setTypeAttributes deletes existing and adds all new attributes in order`() = runTest {
        val inputs = listOf(
            TemplateAttributeInput(key = "a", name = "A", description = "A", type = AttributeType.STRING, ui = AttributeUiType.INPUT),
            TemplateAttributeInput(key = "b", name = "B", description = "B", type = AttributeType.INT, ui = AttributeUiType.INPUT)
        )
        val captured = mutableListOf<TimeEventTypeAttribute>()
        coEvery { attributeRepository.add(capture(captured)) } returns mockk()

        service.setTypeAttributes("chapter", inputs)

        coVerify(exactly = 1) { attributeRepository.deleteByTypeId("chapter") }
        assertEquals(2, captured.size)
        assertEquals("a", captured[0].key)
        assertEquals(0, captured[0].sort)
        assertEquals("b", captured[1].key)
        assertEquals(1, captured[1].sort)
    }

    @Test
    fun `setTypeAttributes with empty list only deletes`() = runTest {
        service.setTypeAttributes("chapter", emptyList())

        coVerify(exactly = 1) { attributeRepository.deleteByTypeId("chapter") }
        coVerify(exactly = 0) { attributeRepository.add(any()) }
    }

    // --- Time Events ---

    @Test
    fun `getTimeEvents delegates to repository`() = runTest {
        val metadataId = UUID.random()
        val events = listOf(createTimeEvent(metadataId = metadataId))
        coEvery { repository.getTimeEvents(metadataId, 1) } returns events

        val result = service.getTimeEvents(metadataId, 1)

        assertEquals(events, result)
    }

    @Test
    fun `getTimeEventsByType delegates to repository`() = runTest {
        val metadataId = UUID.random()
        val events = listOf(createTimeEvent(metadataId = metadataId, type = "verse"))
        coEvery { repository.getTimeEventsByType(metadataId, 1, "verse") } returns events

        val result = service.getTimeEventsByType(metadataId, 1, "verse")

        assertEquals(events, result)
    }

    @Test
    fun `getTimeEventsAtOffset delegates to repository`() = runTest {
        val metadataId = UUID.random()
        val events = listOf(createTimeEvent(metadataId = metadataId))
        coEvery { repository.getTimeEventsAtOffset(metadataId, 1, 5000L) } returns events

        val result = service.getTimeEventsAtOffset(metadataId, 1, 5000L)

        assertEquals(events, result)
    }

    @Test
    fun `getTimeEvent delegates to repository`() = runTest {
        val id = UUID.random()
        val event = createTimeEvent(id = id)
        coEvery { repository.getTimeEvent(id) } returns event

        val result = service.getTimeEvent(id)

        assertEquals(event, result)
    }

    @Test
    fun `getTimeEvent returns null when not found`() = runTest {
        val id = UUID.random()
        coEvery { repository.getTimeEvent(id) } returns null

        val result = service.getTimeEvent(id)

        assertNull(result)
    }

    @Test
    fun `addTimeEvent passes input fields to repository`() = runTest {
        val metadataId = UUID.random()
        val attrs = JsonObject(mapOf("verse" to JsonPrimitive("John 3:16")))
        val input = TimeEventInput(type = "verse", startOffsetMs = 1000, endOffsetMs = 2000, sort = 5, attributes = attrs)
        val expected = createTimeEvent(metadataId = metadataId, type = "verse", startOffsetMs = 1000, endOffsetMs = 2000, sort = 5, attributes = attrs)
        coEvery {
            repository.addTimeEvent(metadataId, 1, "verse", 1000, 2000, 5, attrs)
        } returns expected

        val result = service.addTimeEvent(metadataId, 1, input)

        assertEquals(expected, result)
    }

    @Test
    fun `addTimeEvent defaults sort to 0 and attributes to empty when null`() = runTest {
        val metadataId = UUID.random()
        val input = TimeEventInput(type = "verse", startOffsetMs = 1000, sort = null, attributes = null)
        val expected = createTimeEvent(metadataId = metadataId)
        coEvery {
            repository.addTimeEvent(metadataId, 1, "verse", 1000, null, 0, JsonObject(emptyMap()))
        } returns expected

        val result = service.addTimeEvent(metadataId, 1, input)

        assertEquals(expected, result)
    }

    @Test
    fun `editTimeEvent passes id and input fields to repository`() = runTest {
        val id = UUID.random()
        val attrs = JsonObject(mapOf("key" to JsonPrimitive("value")))
        val input = TimeEventInput(type = "chapter", startOffsetMs = 500, endOffsetMs = 1500, sort = 2, attributes = attrs)
        val expected = createTimeEvent(id = id, type = "chapter", startOffsetMs = 500, endOffsetMs = 1500, sort = 2, attributes = attrs)
        coEvery {
            repository.updateTimeEvent(id, "chapter", 500, 1500, 2, attrs)
        } returns expected

        val result = service.editTimeEvent(id, input)

        assertEquals(expected, result)
    }

    @Test
    fun `editTimeEvent returns null when not found`() = runTest {
        val id = UUID.random()
        val input = TimeEventInput(type = "chapter", startOffsetMs = 0)
        coEvery {
            repository.updateTimeEvent(id, "chapter", 0, null, 0, JsonObject(emptyMap()))
        } returns null

        val result = service.editTimeEvent(id, input)

        assertNull(result)
    }

    @Test
    fun `deleteTimeEvent delegates to repository`() = runTest {
        val id = UUID.random()
        service.deleteTimeEvent(id)

        coVerify(exactly = 1) { repository.deleteTimeEvent(id) }
    }

    @Test
    fun `setTimeEvents deletes all existing events and adds new ones`() = runTest {
        val metadataId = UUID.random()
        val inputs = listOf(
            TimeEventInput(type = "verse", startOffsetMs = 0, endOffsetMs = 1000, sort = 0),
            TimeEventInput(type = "verse", startOffsetMs = 1000, endOffsetMs = 2000, sort = 1)
        )
        val event1 = createTimeEvent(metadataId = metadataId, startOffsetMs = 0, endOffsetMs = 1000, sort = 0)
        val event2 = createTimeEvent(metadataId = metadataId, startOffsetMs = 1000, endOffsetMs = 2000, sort = 1)
        coEvery { repository.addTimeEvent(metadataId, 1, "verse", 0, 1000, 0, JsonObject(emptyMap())) } returns event1
        coEvery { repository.addTimeEvent(metadataId, 1, "verse", 1000, 2000, 1, JsonObject(emptyMap())) } returns event2

        val result = service.setTimeEvents(metadataId, 1, inputs)

        coVerify(exactly = 1) { repository.deleteAllTimeEvents(metadataId, 1) }
        assertEquals(2, result.size)
        assertEquals(event1, result[0])
        assertEquals(event2, result[1])
    }

    @Test
    fun `setTimeEvents with empty list deletes all and returns empty`() = runTest {
        val metadataId = UUID.random()

        val result = service.setTimeEvents(metadataId, 1, emptyList())

        coVerify(exactly = 1) { repository.deleteAllTimeEvents(metadataId, 1) }
        assertEquals(emptyList(), result)
    }

    @Test
    fun `deleteTimeEventsByType delegates to repository`() = runTest {
        val metadataId = UUID.random()
        service.deleteTimeEventsByType(metadataId, 1, "verse")

        coVerify(exactly = 1) { repository.deleteTimeEventsByType(metadataId, 1, "verse") }
    }

    // --- Time Event Metadata Relationships ---

    @Test
    fun `getMetadataRelationships delegates to repository`() = runTest {
        val timeEventId = UUID.random()
        val relationships = listOf(createRelationship(timeEventId = timeEventId))
        coEvery { relationshipRepository.getByTimeEventId(timeEventId) } returns relationships

        val result = service.getMetadataRelationships(timeEventId)

        assertEquals(relationships, result)
    }

    @Test
    fun `addMetadataRelationship passes input fields to repository and publishes event`() = runTest {
        val timeEventId = UUID.random()
        val parentMetadataId = UUID.random()
        val metadataId = UUID.random()
        val attrs = JsonObject(mapOf("page" to JsonPrimitive(3)))
        val input = TimeEventMetadataRelationshipInput(
            metadataId = metadataId,
            metadataVersion = 2,
            relationship = "slide",
            attributes = attrs
        )
        val expected = TimeEventMetadataRelationship(
            timeEventId = timeEventId,
            metadataId = metadataId,
            metadataVersion = 2,
            relationship = "slide",
            attributes = attrs
        )
        coEvery { relationshipRepository.add(expected) } returns expected
        coEvery { repository.getTimeEvent(timeEventId) } returns createTimeEvent(id = timeEventId, metadataId = parentMetadataId)

        val result = service.addMetadataRelationship(timeEventId, input)

        assertEquals(expected, result)
        coVerify(exactly = 1) {
            pubSubService.publish(
                TIME_EVENT_CHANGED_CHANNEL,
                TimeEventChanged.serializer(),
                TimeEventChanged(metadataId = parentMetadataId, metadataVersion = 1, eventCount = 1)
            )
        }
    }

    @Test
    fun `addMetadataRelationship handles null attributes and version`() = runTest {
        val timeEventId = UUID.random()
        val metadataId = UUID.random()
        val input = TimeEventMetadataRelationshipInput(
            metadataId = metadataId,
            relationship = "reference"
        )
        val expected = TimeEventMetadataRelationship(
            timeEventId = timeEventId,
            metadataId = metadataId,
            relationship = "reference"
        )
        coEvery { relationshipRepository.add(expected) } returns expected
        coEvery { repository.getTimeEvent(timeEventId) } returns createTimeEvent(id = timeEventId)

        val result = service.addMetadataRelationship(timeEventId, input)

        assertEquals(expected, result)
        assertNull(result.metadataVersion)
        assertNull(result.attributes)
    }

    @Test
    fun `deleteMetadataRelationship delegates to repository and publishes event`() = runTest {
        val timeEventId = UUID.random()
        val parentMetadataId = UUID.random()
        val metadataId = UUID.random()
        coEvery { repository.getTimeEvent(timeEventId) } returns createTimeEvent(id = timeEventId, metadataId = parentMetadataId)

        service.deleteMetadataRelationship(timeEventId, metadataId, "slide")

        coVerify(exactly = 1) { relationshipRepository.delete(timeEventId, metadataId, "slide") }
        coVerify(exactly = 1) {
            pubSubService.publish(
                TIME_EVENT_CHANGED_CHANNEL,
                TimeEventChanged.serializer(),
                TimeEventChanged(metadataId = parentMetadataId, metadataVersion = 1, eventCount = 1)
            )
        }
    }

    @Test
    fun `setMetadataRelationships deletes existing and adds all new relationships and publishes event`() = runTest {
        val timeEventId = UUID.random()
        val parentMetadataId = UUID.random()
        val metadataId1 = UUID.random()
        val metadataId2 = UUID.random()
        val inputs = listOf(
            TimeEventMetadataRelationshipInput(metadataId = metadataId1, relationship = "slide"),
            TimeEventMetadataRelationshipInput(metadataId = metadataId2, metadataVersion = 1, relationship = "resource")
        )
        val rel1 = TimeEventMetadataRelationship(timeEventId = timeEventId, metadataId = metadataId1, relationship = "slide")
        val rel2 = TimeEventMetadataRelationship(timeEventId = timeEventId, metadataId = metadataId2, metadataVersion = 1, relationship = "resource")
        coEvery { relationshipRepository.add(rel1) } returns rel1
        coEvery { relationshipRepository.add(rel2) } returns rel2
        coEvery { repository.getTimeEvent(timeEventId) } returns createTimeEvent(id = timeEventId, metadataId = parentMetadataId)

        val result = service.setMetadataRelationships(timeEventId, inputs)

        coVerify(exactly = 1) { relationshipRepository.deleteByTimeEventId(timeEventId) }
        assertEquals(2, result.size)
        assertEquals(rel1, result[0])
        assertEquals(rel2, result[1])
        coVerify(exactly = 1) {
            pubSubService.publish(
                TIME_EVENT_CHANGED_CHANNEL,
                TimeEventChanged.serializer(),
                TimeEventChanged(metadataId = parentMetadataId, metadataVersion = 1, eventCount = 1)
            )
        }
    }

    @Test
    fun `setMetadataRelationships with empty list deletes all and returns empty`() = runTest {
        val timeEventId = UUID.random()
        coEvery { repository.getTimeEvent(timeEventId) } returns createTimeEvent(id = timeEventId)

        val result = service.setMetadataRelationships(timeEventId, emptyList())

        coVerify(exactly = 1) { relationshipRepository.deleteByTimeEventId(timeEventId) }
        assertEquals(emptyList(), result)
    }

    @Test
    fun `editMetadataRelationshipAttributes delegates to repository and publishes event`() = runTest {
        val timeEventId = UUID.random()
        val parentMetadataId = UUID.random()
        val metadataId = UUID.random()
        val attrs = JsonObject(mapOf("page" to JsonPrimitive(5)))
        coEvery { repository.getTimeEvent(timeEventId) } returns createTimeEvent(id = timeEventId, metadataId = parentMetadataId)

        service.editMetadataRelationshipAttributes(timeEventId, metadataId, "slide", attrs)

        coVerify(exactly = 1) { relationshipRepository.setAttributes(timeEventId, metadataId, "slide", attrs) }
        coVerify(exactly = 1) {
            pubSubService.publish(
                TIME_EVENT_CHANGED_CHANNEL,
                TimeEventChanged.serializer(),
                TimeEventChanged(metadataId = parentMetadataId, metadataVersion = 1, eventCount = 1)
            )
        }
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

    private fun createRelationship(
        timeEventId: UUID = UUID.random(),
        metadataId: UUID = UUID.random(),
        metadataVersion: Int? = null,
        relationship: String = "slide",
        attributes: JsonObject? = null
    ) = TimeEventMetadataRelationship(
        timeEventId = timeEventId,
        metadataId = metadataId,
        metadataVersion = metadataVersion,
        relationship = relationship,
        attributes = attributes
    )
}
