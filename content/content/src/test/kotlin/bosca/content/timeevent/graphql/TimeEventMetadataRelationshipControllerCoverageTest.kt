package bosca.content.timeevent.graphql

import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataType
import bosca.content.metadata.service.MetadataService
import bosca.content.security.MetadataPermissionEvaluator
import bosca.content.timeevent.model.TimeEventMetadataRelationship
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

class TimeEventMetadataRelationshipControllerCoverageTest {

    private val metadataService = mockk<MetadataService>()
    private val permissionEvaluator = mockk<MetadataPermissionEvaluator>()

    private val controller = TimeEventMetadataRelationshipController(metadataService, permissionEvaluator)
    private val authentication = mockk<AuthenticationContext>()

    @AfterTest
    fun tearDown() {
        clearAllMocks()
    }

    private fun relationship(
        timeEventId: UUID = UUID.random(),
        metadataId: UUID = UUID.random(),
        metadataVersion: Int? = 3,
        relationship: String = "slide",
        attributes: kotlinx.serialization.json.JsonElement? = JsonPrimitive("value")
    ) = TimeEventMetadataRelationship(
        timeEventId = timeEventId,
        metadataId = metadataId,
        metadataVersion = metadataVersion,
        relationship = relationship,
        attributes = attributes
    )

    @Test
    fun `timeEventId returns the time event id`() {
        val id = UUID.random()
        val rel = relationship(timeEventId = id)

        assertEquals(id, controller.timeEventId(rel))
    }

    @Test
    fun `metadataId returns the metadata id`() {
        val id = UUID.random()
        val rel = relationship(metadataId = id)

        assertEquals(id, controller.metadataId(rel))
    }

    @Test
    fun `metadataVersion returns the metadata version`() {
        val rel = relationship(metadataVersion = 7)

        assertEquals(7, controller.metadataVersion(rel))
    }

    @Test
    fun `metadataVersion returns null when absent`() {
        val rel = relationship(metadataVersion = null)

        assertNull(controller.metadataVersion(rel))
    }

    @Test
    fun `relationship returns the relationship type`() {
        val rel = relationship(relationship = "resource")

        assertEquals("resource", controller.relationship(rel))
    }

    @Test
    fun `attributes returns the attributes element`() {
        val attrs = JsonPrimitive("crop")
        val rel = relationship(attributes = attrs)

        assertEquals(attrs, controller.attributes(rel))
    }

    @Test
    fun `attributes returns null when absent`() {
        val rel = relationship(attributes = null)

        assertNull(controller.attributes(rel))
    }

    @Test
    fun `metadata returns the linked metadata when allowed`() = runTest {
        val metadataId = UUID.random()
        val rel = relationship(metadataId = metadataId, metadataVersion = 3)
        val metadata = Metadata(
            id = metadataId,
            name = "Linked",
            type = MetadataType.STANDARD,
            contentType = "text/plain",
            contentLength = null,
            languageTag = "en",
            workflowStateId = "published"
        )

        coEvery { metadataService.getById(metadataId, 3) } returns metadata
        coEvery { permissionEvaluator.verifyAllowed(authentication, metadata, PermissionAction.VIEW) } returns Unit

        val result = controller.metadata(authentication, rel)

        assertSame(metadata, result)
        coVerify(exactly = 1) { permissionEvaluator.verifyAllowed(authentication, metadata, PermissionAction.VIEW) }
    }

    @Test
    fun `metadata passes null version through to service`() = runTest {
        val metadataId = UUID.random()
        val rel = relationship(metadataId = metadataId, metadataVersion = null)
        val metadata = Metadata(
            id = metadataId,
            name = "Linked",
            type = MetadataType.STANDARD,
            contentType = "text/plain",
            contentLength = null,
            languageTag = "en",
            workflowStateId = "published"
        )

        coEvery { metadataService.getById(metadataId, null) } returns metadata
        coEvery { permissionEvaluator.verifyAllowed(authentication, metadata, PermissionAction.VIEW) } returns Unit

        assertSame(metadata, controller.metadata(authentication, rel))
    }

    @Test
    fun `metadata throws when metadata not found`() = runTest {
        val metadataId = UUID.random()
        val rel = relationship(metadataId = metadataId, metadataVersion = 3)

        coEvery { metadataService.getById(metadataId, 3) } returns null

        val error = assertFailsWith<IllegalStateException> {
            controller.metadata(authentication, rel)
        }
        assertEquals("Metadata not found: $metadataId", error.message)
    }

    @Test
    fun `metadata propagates permission denial`() = runTest {
        val metadataId = UUID.random()
        val rel = relationship(metadataId = metadataId, metadataVersion = 3)
        val metadata = Metadata(
            id = metadataId,
            name = "Linked",
            type = MetadataType.STANDARD,
            contentType = "text/plain",
            contentLength = null,
            languageTag = "en",
            workflowStateId = "published"
        )

        coEvery { metadataService.getById(metadataId, 3) } returns metadata
        coEvery {
            permissionEvaluator.verifyAllowed(authentication, metadata, PermissionAction.VIEW)
        } throws SecurityException("denied")

        assertFailsWith<SecurityException> {
            controller.metadata(authentication, rel)
        }
    }
}
