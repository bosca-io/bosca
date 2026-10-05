package bosca.content.timeevent.graphql

import bosca.attributes.AttributeType
import bosca.attributes.AttributeUiType
import bosca.attributes.TemplateAttributeInput
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataType
import bosca.content.metadata.service.MetadataService
import bosca.content.security.MetadataPermissionEvaluator
import bosca.content.timeevent.jobs.PdfTimelineImportJob
import bosca.content.timeevent.jobs.enqueue
import bosca.content.timeevent.model.TimeEvent
import bosca.content.timeevent.model.TimeEventInput
import bosca.content.timeevent.model.TimeEventMetadataRelationship
import bosca.content.timeevent.model.TimeEventMetadataRelationshipInput
import bosca.content.timeevent.model.TimeEventType
import bosca.content.timeevent.model.TimeEventTypeInput
import bosca.content.timeevent.service.TimeEventService
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TimeEventMutationControllerCoverageTest {

    private val timeEventService = mockk<TimeEventService>()
    private val metadataService = mockk<MetadataService>()
    private val permissionEvaluator = mockk<MetadataPermissionEvaluator>()
    private val groupEvaluator = mockk<GroupEvaluator>()

    private val controller = TimeEventMutationController(
        timeEventService = timeEventService,
        metadataService = metadataService,
        permissionEvaluator = permissionEvaluator,
        groupEvaluator = groupEvaluator
    )

    private val authentication = mockk<AuthenticationContext>()

    @BeforeTest
    fun setup() {
        mockkStatic("bosca.content.timeevent.jobs.PdfTimelineImportExecutorExecutorKt")
    }

    @AfterTest
    fun tearDown() {
        unmockkAll()
        clearAllMocks()
    }

    private fun metadata(
        id: UUID = UUID.random(),
        uploaded: OffsetDateTime? = null,
    ) = Metadata(
        id = id,
        name = "test",
        type = MetadataType.STANDARD,
        contentType = "application/pdf",
        contentLength = null,
        languageTag = "en",
        workflowStateId = "draft",
        uploaded = uploaded,
    )

    private fun timeEvent(
        id: UUID = UUID.random(),
        metadataId: UUID = UUID.random(),
        metadataVersion: Int = 1,
    ) = TimeEvent(
        id = id,
        metadataId = metadataId,
        metadataVersion = metadataVersion,
        type = "verse",
        startOffsetMs = 1000,
    )

    private fun typeInput() = TimeEventTypeInput(id = "verse", name = "Verse", description = "d")

    private fun type(id: String = "verse") = TimeEventType(id = id, name = "Verse", description = "d")

    private fun eventInput() = TimeEventInput(type = "verse", startOffsetMs = 1000)

    private fun relationshipInput(metadataId: UUID = UUID.random()) =
        TimeEventMetadataRelationshipInput(metadataId = metadataId, relationship = "slide")

    private fun relationship(timeEventId: UUID = UUID.random(), metadataId: UUID = UUID.random()) =
        TimeEventMetadataRelationship(timeEventId = timeEventId, metadataId = metadataId, relationship = "slide")

    private fun attributeInput() = TemplateAttributeInput(
        key = "k",
        name = "n",
        description = "d",
        type = AttributeType.STRING,
        ui = AttributeUiType.INPUT
    )

    // ---- addType ----

    @Test
    fun `addType verifies admin and delegates`() = runTest {
        val input = typeInput()
        val created = type()
        coEvery { groupEvaluator.verifyHasAdminGroup(authentication) } returns Unit
        coEvery { timeEventService.addType(input) } returns created

        assertEquals(created, controller.addType(authentication, input))
    }

    @Test
    fun `addType denied throws and does not call service`() = runTest {
        val input = typeInput()
        coEvery { groupEvaluator.verifyHasAdminGroup(authentication) } throws IllegalStateException("denied")

        assertFailsWith<IllegalStateException> { controller.addType(authentication, input) }
        coVerify(exactly = 0) { timeEventService.addType(any()) }
    }

    // ---- editType ----

    @Test
    fun `editType returns updated type`() = runTest {
        val input = typeInput()
        val updated = type()
        coEvery { groupEvaluator.verifyHasAdminGroup(authentication) } returns Unit
        coEvery { timeEventService.editType("verse", input) } returns updated

        assertEquals(updated, controller.editType(authentication, "verse", input))
    }

    @Test
    fun `editType errors when not found`() = runTest {
        val input = typeInput()
        coEvery { groupEvaluator.verifyHasAdminGroup(authentication) } returns Unit
        coEvery { timeEventService.editType("missing", input) } returns null

        val ex = assertFailsWith<IllegalStateException> { controller.editType(authentication, "missing", input) }
        assertTrue(ex.message?.contains("missing") == true)
    }

    // ---- deleteType ----

    @Test
    fun `deleteType verifies admin, deletes, returns true`() = runTest {
        coEvery { groupEvaluator.verifyHasAdminGroup(authentication) } returns Unit
        coEvery { timeEventService.deleteType("verse") } returns Unit

        assertTrue(controller.deleteType(authentication, "verse"))
        coVerify { timeEventService.deleteType("verse") }
    }

    // ---- addTypeAttribute ----

    @Test
    fun `addTypeAttribute returns type when found`() = runTest {
        val existing = type()
        val attribute = attributeInput()
        coEvery { groupEvaluator.verifyHasAdminGroup(authentication) } returns Unit
        coEvery { timeEventService.getType("verse") } returns existing
        coEvery { timeEventService.addTypeAttribute("verse", attribute, 3) } returns Unit

        assertEquals(existing, controller.addTypeAttribute(authentication, "verse", attribute, 3))
        coVerify { timeEventService.addTypeAttribute("verse", attribute, 3) }
    }

    @Test
    fun `addTypeAttribute errors when type not found`() = runTest {
        val attribute = attributeInput()
        coEvery { groupEvaluator.verifyHasAdminGroup(authentication) } returns Unit
        coEvery { timeEventService.getType("missing") } returns null

        assertFailsWith<IllegalStateException> {
            controller.addTypeAttribute(authentication, "missing", attribute, 0)
        }
        coVerify(exactly = 0) { timeEventService.addTypeAttribute(any(), any(), any()) }
    }

    // ---- deleteTypeAttribute ----

    @Test
    fun `deleteTypeAttribute returns type when found`() = runTest {
        val existing = type()
        coEvery { groupEvaluator.verifyHasAdminGroup(authentication) } returns Unit
        coEvery { timeEventService.getType("verse") } returns existing
        coEvery { timeEventService.deleteTypeAttribute("verse", "k") } returns Unit

        assertEquals(existing, controller.deleteTypeAttribute(authentication, "verse", "k"))
        coVerify { timeEventService.deleteTypeAttribute("verse", "k") }
    }

    @Test
    fun `deleteTypeAttribute errors when type not found`() = runTest {
        coEvery { groupEvaluator.verifyHasAdminGroup(authentication) } returns Unit
        coEvery { timeEventService.getType("missing") } returns null

        assertFailsWith<IllegalStateException> {
            controller.deleteTypeAttribute(authentication, "missing", "k")
        }
        coVerify(exactly = 0) { timeEventService.deleteTypeAttribute(any(), any()) }
    }

    // ---- setTypeAttributes ----

    @Test
    fun `setTypeAttributes returns type when found`() = runTest {
        val existing = type()
        val attributes = listOf(attributeInput())
        coEvery { groupEvaluator.verifyHasAdminGroup(authentication) } returns Unit
        coEvery { timeEventService.getType("verse") } returns existing
        coEvery { timeEventService.setTypeAttributes("verse", attributes) } returns Unit

        assertEquals(existing, controller.setTypeAttributes(authentication, "verse", attributes))
        coVerify { timeEventService.setTypeAttributes("verse", attributes) }
    }

    @Test
    fun `setTypeAttributes errors when type not found`() = runTest {
        coEvery { groupEvaluator.verifyHasAdminGroup(authentication) } returns Unit
        coEvery { timeEventService.getType("missing") } returns null

        assertFailsWith<IllegalStateException> {
            controller.setTypeAttributes(authentication, "missing", emptyList())
        }
        coVerify(exactly = 0) { timeEventService.setTypeAttributes(any(), any()) }
    }

    // ---- add ----

    @Test
    fun `add verifies metadata edit and delegates`() = runTest {
        val metadataId = UUID.random()
        val md = metadata(id = metadataId)
        val input = eventInput()
        val created = timeEvent(metadataId = metadataId)
        coEvery { metadataService.getById(metadataId) } returns md
        coEvery { permissionEvaluator.verifyAllowed(authentication, md, PermissionAction.EDIT) } returns Unit
        coEvery { timeEventService.addTimeEvent(metadataId, 2, input) } returns created

        assertEquals(created, controller.add(authentication, metadataId, 2, input))
    }

    @Test
    fun `add errors when metadata not found`() = runTest {
        val metadataId = UUID.random()
        coEvery { metadataService.getById(metadataId) } returns null

        assertFailsWith<IllegalStateException> {
            controller.add(authentication, metadataId, 1, eventInput())
        }
        coVerify(exactly = 0) { timeEventService.addTimeEvent(any(), any(), any()) }
    }

    @Test
    fun `add propagates permission denial`() = runTest {
        val metadataId = UUID.random()
        val md = metadata(id = metadataId)
        coEvery { metadataService.getById(metadataId) } returns md
        coEvery {
            permissionEvaluator.verifyAllowed(authentication, md, PermissionAction.EDIT)
        } throws IllegalStateException("denied")

        assertFailsWith<IllegalStateException> {
            controller.add(authentication, metadataId, 1, eventInput())
        }
        coVerify(exactly = 0) { timeEventService.addTimeEvent(any(), any(), any()) }
    }

    // ---- edit ----

    @Test
    fun `edit returns updated event`() = runTest {
        val id = UUID.random()
        val metadataId = UUID.random()
        val existing = timeEvent(id = id, metadataId = metadataId)
        val md = metadata(id = metadataId)
        val input = eventInput()
        val updated = timeEvent(id = id, metadataId = metadataId)
        coEvery { timeEventService.getTimeEvent(id) } returns existing
        coEvery { metadataService.getById(metadataId) } returns md
        coEvery { permissionEvaluator.verifyAllowed(authentication, md, PermissionAction.EDIT) } returns Unit
        coEvery { timeEventService.editTimeEvent(id, input) } returns updated

        assertEquals(updated, controller.edit(authentication, id, input))
    }

    @Test
    fun `edit errors when time event not found`() = runTest {
        val id = UUID.random()
        coEvery { timeEventService.getTimeEvent(id) } returns null

        assertFailsWith<IllegalStateException> { controller.edit(authentication, id, eventInput()) }
    }

    @Test
    fun `edit errors when service returns null after edit`() = runTest {
        val id = UUID.random()
        val metadataId = UUID.random()
        val existing = timeEvent(id = id, metadataId = metadataId)
        val md = metadata(id = metadataId)
        val input = eventInput()
        coEvery { timeEventService.getTimeEvent(id) } returns existing
        coEvery { metadataService.getById(metadataId) } returns md
        coEvery { permissionEvaluator.verifyAllowed(authentication, md, PermissionAction.EDIT) } returns Unit
        coEvery { timeEventService.editTimeEvent(id, input) } returns null

        assertFailsWith<IllegalStateException> { controller.edit(authentication, id, input) }
    }

    // ---- delete ----

    @Test
    fun `delete returns true after deleting`() = runTest {
        val id = UUID.random()
        val metadataId = UUID.random()
        val existing = timeEvent(id = id, metadataId = metadataId)
        val md = metadata(id = metadataId)
        coEvery { timeEventService.getTimeEvent(id) } returns existing
        coEvery { metadataService.getById(metadataId) } returns md
        coEvery { permissionEvaluator.verifyAllowed(authentication, md, PermissionAction.EDIT) } returns Unit
        coEvery { timeEventService.deleteTimeEvent(id) } returns Unit

        assertTrue(controller.delete(authentication, id))
        coVerify { timeEventService.deleteTimeEvent(id) }
    }

    @Test
    fun `delete errors when time event not found`() = runTest {
        val id = UUID.random()
        coEvery { timeEventService.getTimeEvent(id) } returns null

        assertFailsWith<IllegalStateException> { controller.delete(authentication, id) }
        coVerify(exactly = 0) { timeEventService.deleteTimeEvent(any()) }
    }

    // ---- deleteAll ----

    @Test
    fun `deleteAll verifies each parent and returns count`() = runTest {
        val id1 = UUID.random()
        val id2 = UUID.random()
        val metadataId1 = UUID.random()
        val metadataId2 = UUID.random()
        val event1 = timeEvent(id = id1, metadataId = metadataId1)
        val event2 = timeEvent(id = id2, metadataId = metadataId2)
        val md1 = metadata(id = metadataId1)
        val md2 = metadata(id = metadataId2)
        val ids = listOf(id1, id2)
        coEvery { timeEventService.getTimeEventsByIds(ids) } returns listOf(event1, event2)
        coEvery { metadataService.getById(metadataId1) } returns md1
        coEvery { metadataService.getById(metadataId2) } returns md2
        coEvery { permissionEvaluator.verifyAllowed(authentication, md1, PermissionAction.EDIT) } returns Unit
        coEvery { permissionEvaluator.verifyAllowed(authentication, md2, PermissionAction.EDIT) } returns Unit
        coEvery { timeEventService.deleteTimeEvents(listOf(id1, id2)) } returns 2

        assertEquals(2, controller.deleteAll(authentication, ids))
    }

    @Test
    fun `deleteAll with empty result deletes nothing`() = runTest {
        val ids = listOf(UUID.random())
        coEvery { timeEventService.getTimeEventsByIds(ids) } returns emptyList()
        coEvery { timeEventService.deleteTimeEvents(emptyList()) } returns 0

        assertEquals(0, controller.deleteAll(authentication, ids))
    }

    // ---- setTimeEvents ----

    @Test
    fun `setTimeEvents verifies edit and delegates`() = runTest {
        val metadataId = UUID.random()
        val md = metadata(id = metadataId)
        val inputs = listOf(eventInput())
        val result = listOf(timeEvent(metadataId = metadataId))
        coEvery { metadataService.getById(metadataId) } returns md
        coEvery { permissionEvaluator.verifyAllowed(authentication, md, PermissionAction.EDIT) } returns Unit
        coEvery { timeEventService.setTimeEvents(metadataId, 4, inputs) } returns result

        assertEquals(result, controller.setTimeEvents(authentication, metadataId, 4, inputs))
    }

    // ---- deleteTimeEventsByType ----

    @Test
    fun `deleteTimeEventsByType returns true`() = runTest {
        val metadataId = UUID.random()
        val md = metadata(id = metadataId)
        coEvery { metadataService.getById(metadataId) } returns md
        coEvery { permissionEvaluator.verifyAllowed(authentication, md, PermissionAction.EDIT) } returns Unit
        coEvery { timeEventService.deleteTimeEventsByType(metadataId, 1, "verse") } returns Unit

        assertTrue(controller.deleteTimeEventsByType(authentication, metadataId, 1, "verse"))
        coVerify { timeEventService.deleteTimeEventsByType(metadataId, 1, "verse") }
    }

    // ---- addMetadataRelationship ----

    @Test
    fun `addMetadataRelationship verifies event edit and metadata view`() = runTest {
        val timeEventId = UUID.random()
        val eventMetadataId = UUID.random()
        val relationMetadataId = UUID.random()
        val existing = timeEvent(id = timeEventId, metadataId = eventMetadataId)
        val eventMetadata = metadata(id = eventMetadataId)
        val relationMetadata = metadata(id = relationMetadataId)
        val input = relationshipInput(metadataId = relationMetadataId)
        val created = relationship(timeEventId = timeEventId, metadataId = relationMetadataId)
        coEvery { timeEventService.getTimeEvent(timeEventId) } returns existing
        coEvery { metadataService.getById(eventMetadataId) } returns eventMetadata
        coEvery { permissionEvaluator.verifyAllowed(authentication, eventMetadata, PermissionAction.EDIT) } returns Unit
        coEvery { metadataService.getById(relationMetadataId) } returns relationMetadata
        coEvery { permissionEvaluator.verifyAllowed(authentication, relationMetadata, PermissionAction.VIEW) } returns Unit
        coEvery { timeEventService.addMetadataRelationship(timeEventId, input) } returns created

        assertEquals(created, controller.addMetadataRelationship(authentication, timeEventId, input))
    }

    @Test
    fun `addMetadataRelationship errors when relationship metadata not found`() = runTest {
        val timeEventId = UUID.random()
        val eventMetadataId = UUID.random()
        val relationMetadataId = UUID.random()
        val existing = timeEvent(id = timeEventId, metadataId = eventMetadataId)
        val eventMetadata = metadata(id = eventMetadataId)
        val input = relationshipInput(metadataId = relationMetadataId)
        coEvery { timeEventService.getTimeEvent(timeEventId) } returns existing
        coEvery { metadataService.getById(eventMetadataId) } returns eventMetadata
        coEvery { permissionEvaluator.verifyAllowed(authentication, eventMetadata, PermissionAction.EDIT) } returns Unit
        coEvery { metadataService.getById(relationMetadataId) } returns null

        assertFailsWith<IllegalStateException> {
            controller.addMetadataRelationship(authentication, timeEventId, input)
        }
        coVerify(exactly = 0) { timeEventService.addMetadataRelationship(any(), any()) }
    }

    @Test
    fun `addMetadataRelationship errors when time event not found`() = runTest {
        val timeEventId = UUID.random()
        coEvery { timeEventService.getTimeEvent(timeEventId) } returns null

        assertFailsWith<IllegalStateException> {
            controller.addMetadataRelationship(authentication, timeEventId, relationshipInput())
        }
    }

    // ---- deleteMetadataRelationship ----

    @Test
    fun `deleteMetadataRelationship returns true`() = runTest {
        val timeEventId = UUID.random()
        val eventMetadataId = UUID.random()
        val metadataId = UUID.random()
        val existing = timeEvent(id = timeEventId, metadataId = eventMetadataId)
        val eventMetadata = metadata(id = eventMetadataId)
        coEvery { timeEventService.getTimeEvent(timeEventId) } returns existing
        coEvery { metadataService.getById(eventMetadataId) } returns eventMetadata
        coEvery { permissionEvaluator.verifyAllowed(authentication, eventMetadata, PermissionAction.EDIT) } returns Unit
        coEvery { timeEventService.deleteMetadataRelationship(timeEventId, metadataId, "slide") } returns Unit

        assertTrue(controller.deleteMetadataRelationship(authentication, timeEventId, metadataId, "slide"))
        coVerify { timeEventService.deleteMetadataRelationship(timeEventId, metadataId, "slide") }
    }

    // ---- setMetadataRelationships ----

    @Test
    fun `setMetadataRelationships verifies each relationship metadata`() = runTest {
        val timeEventId = UUID.random()
        val eventMetadataId = UUID.random()
        val rel1Metadata = UUID.random()
        val rel2Metadata = UUID.random()
        val existing = timeEvent(id = timeEventId, metadataId = eventMetadataId)
        val eventMetadata = metadata(id = eventMetadataId)
        val md1 = metadata(id = rel1Metadata)
        val md2 = metadata(id = rel2Metadata)
        val inputs = listOf(relationshipInput(metadataId = rel1Metadata), relationshipInput(metadataId = rel2Metadata))
        val result = listOf(relationship(timeEventId = timeEventId, metadataId = rel1Metadata))
        coEvery { timeEventService.getTimeEvent(timeEventId) } returns existing
        coEvery { metadataService.getById(eventMetadataId) } returns eventMetadata
        coEvery { permissionEvaluator.verifyAllowed(authentication, eventMetadata, PermissionAction.EDIT) } returns Unit
        coEvery { metadataService.getById(rel1Metadata) } returns md1
        coEvery { metadataService.getById(rel2Metadata) } returns md2
        coEvery { permissionEvaluator.verifyAllowed(authentication, md1, PermissionAction.VIEW) } returns Unit
        coEvery { permissionEvaluator.verifyAllowed(authentication, md2, PermissionAction.VIEW) } returns Unit
        coEvery { timeEventService.setMetadataRelationships(timeEventId, inputs) } returns result

        assertEquals(result, controller.setMetadataRelationships(authentication, timeEventId, inputs))
    }

    @Test
    fun `setMetadataRelationships with empty list skips view checks`() = runTest {
        val timeEventId = UUID.random()
        val eventMetadataId = UUID.random()
        val existing = timeEvent(id = timeEventId, metadataId = eventMetadataId)
        val eventMetadata = metadata(id = eventMetadataId)
        val inputs = emptyList<TimeEventMetadataRelationshipInput>()
        coEvery { timeEventService.getTimeEvent(timeEventId) } returns existing
        coEvery { metadataService.getById(eventMetadataId) } returns eventMetadata
        coEvery { permissionEvaluator.verifyAllowed(authentication, eventMetadata, PermissionAction.EDIT) } returns Unit
        coEvery { timeEventService.setMetadataRelationships(timeEventId, inputs) } returns emptyList()

        assertEquals(emptyList(), controller.setMetadataRelationships(authentication, timeEventId, inputs))
    }

    // ---- editMetadataRelationshipAttributes ----

    @Test
    fun `editMetadataRelationshipAttributes returns true`() = runTest {
        val timeEventId = UUID.random()
        val eventMetadataId = UUID.random()
        val metadataId = UUID.random()
        val existing = timeEvent(id = timeEventId, metadataId = eventMetadataId)
        val eventMetadata = metadata(id = eventMetadataId)
        val attributes: JsonElement = JsonObject(mapOf("k" to JsonPrimitive("v")))
        coEvery { timeEventService.getTimeEvent(timeEventId) } returns existing
        coEvery { metadataService.getById(eventMetadataId) } returns eventMetadata
        coEvery { permissionEvaluator.verifyAllowed(authentication, eventMetadata, PermissionAction.EDIT) } returns Unit
        coEvery {
            timeEventService.editMetadataRelationshipAttributes(timeEventId, metadataId, "slide", attributes)
        } returns Unit

        assertTrue(
            controller.editMetadataRelationshipAttributes(authentication, timeEventId, metadataId, "slide", attributes)
        )
        coVerify { timeEventService.editMetadataRelationshipAttributes(timeEventId, metadataId, "slide", attributes) }
    }

    // ---- importPdfAsTimelineEvents ----

    @Test
    fun `importPdfAsTimelineEvents happy path enqueues job`() = runTest {
        val pdfId = UUID.random()
        val targetId = UUID.random()
        val target = metadata(id = targetId)
        val pdf = metadata(id = pdfId, uploaded = OffsetDateTime.now())
        coEvery { metadataService.getById(targetId) } returns target
        coEvery { permissionEvaluator.verifyAllowed(authentication, target, PermissionAction.EDIT) } returns Unit
        coEvery { metadataService.getById(pdfId) } returns pdf
        coEvery { permissionEvaluator.verifyAllowed(authentication, pdf, PermissionAction.VIEW) } returns Unit
        coEvery { timeEventService.getType("verse") } returns type()
        coEvery { any<PdfTimelineImportJob>().enqueue() } returns mockk()

        val result = controller.importPdfAsTimelineEvents(
            authentication = authentication,
            pdfMetadataId = pdfId,
            targetMetadataId = targetId,
            targetMetadataVersion = 1,
            eventTypeId = "verse",
            relationship = "slide",
            durationMs = 60_000,
        )

        assertTrue(result)
        coVerify { any<PdfTimelineImportJob>().enqueue() }
    }

    @Test
    fun `importPdfAsTimelineEvents requires positive duration`() = runTest {
        assertFailsWith<IllegalArgumentException> {
            controller.importPdfAsTimelineEvents(
                authentication = authentication,
                pdfMetadataId = UUID.random(),
                targetMetadataId = UUID.random(),
                targetMetadataVersion = 1,
                eventTypeId = "verse",
                relationship = "slide",
                durationMs = 0,
            )
        }
    }

    @Test
    fun `importPdfAsTimelineEvents errors when pdf metadata not found`() = runTest {
        val pdfId = UUID.random()
        val targetId = UUID.random()
        val target = metadata(id = targetId)
        coEvery { metadataService.getById(targetId) } returns target
        coEvery { permissionEvaluator.verifyAllowed(authentication, target, PermissionAction.EDIT) } returns Unit
        coEvery { metadataService.getById(pdfId) } returns null

        assertFailsWith<IllegalStateException> {
            controller.importPdfAsTimelineEvents(
                authentication = authentication,
                pdfMetadataId = pdfId,
                targetMetadataId = targetId,
                targetMetadataVersion = 1,
                eventTypeId = "verse",
                relationship = "slide",
                durationMs = 1000,
            )
        }
    }

    @Test
    fun `importPdfAsTimelineEvents requires uploaded pdf`() = runTest {
        val pdfId = UUID.random()
        val targetId = UUID.random()
        val target = metadata(id = targetId)
        val pdf = metadata(id = pdfId, uploaded = null)
        coEvery { metadataService.getById(targetId) } returns target
        coEvery { permissionEvaluator.verifyAllowed(authentication, target, PermissionAction.EDIT) } returns Unit
        coEvery { metadataService.getById(pdfId) } returns pdf

        assertFailsWith<IllegalArgumentException> {
            controller.importPdfAsTimelineEvents(
                authentication = authentication,
                pdfMetadataId = pdfId,
                targetMetadataId = targetId,
                targetMetadataVersion = 1,
                eventTypeId = "verse",
                relationship = "slide",
                durationMs = 1000,
            )
        }
    }

    @Test
    fun `importPdfAsTimelineEvents errors when event type not found`() = runTest {
        val pdfId = UUID.random()
        val targetId = UUID.random()
        val target = metadata(id = targetId)
        val pdf = metadata(id = pdfId, uploaded = OffsetDateTime.now())
        coEvery { metadataService.getById(targetId) } returns target
        coEvery { permissionEvaluator.verifyAllowed(authentication, target, PermissionAction.EDIT) } returns Unit
        coEvery { metadataService.getById(pdfId) } returns pdf
        coEvery { permissionEvaluator.verifyAllowed(authentication, pdf, PermissionAction.VIEW) } returns Unit
        coEvery { timeEventService.getType("missing") } returns null

        assertFailsWith<IllegalStateException> {
            controller.importPdfAsTimelineEvents(
                authentication = authentication,
                pdfMetadataId = pdfId,
                targetMetadataId = targetId,
                targetMetadataVersion = 1,
                eventTypeId = "missing",
                relationship = "slide",
                durationMs = 1000,
            )
        }
    }

    @Test
    fun `importPdfAsTimelineEvents propagates pdf view denial`() = runTest {
        val pdfId = UUID.random()
        val targetId = UUID.random()
        val target = metadata(id = targetId)
        val pdf = metadata(id = pdfId, uploaded = OffsetDateTime.now())
        coEvery { metadataService.getById(targetId) } returns target
        coEvery { permissionEvaluator.verifyAllowed(authentication, target, PermissionAction.EDIT) } returns Unit
        coEvery { metadataService.getById(pdfId) } returns pdf
        coEvery {
            permissionEvaluator.verifyAllowed(authentication, pdf, PermissionAction.VIEW)
        } throws IllegalStateException("denied")

        assertFailsWith<IllegalStateException> {
            controller.importPdfAsTimelineEvents(
                authentication = authentication,
                pdfMetadataId = pdfId,
                targetMetadataId = targetId,
                targetMetadataVersion = 1,
                eventTypeId = "verse",
                relationship = "slide",
                durationMs = 1000,
            )
        }
        coVerify(exactly = 0) { timeEventService.getType(any()) }
    }
}
