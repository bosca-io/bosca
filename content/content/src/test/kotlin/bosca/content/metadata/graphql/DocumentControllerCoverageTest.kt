package bosca.content.metadata.graphql

import bosca.content.metadata.model.Document
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataType
import bosca.content.metadata.service.MetadataService
import bosca.content.security.MetadataPermissionEvaluator
import bosca.documents.Content
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DocumentControllerCoverageTest {

    private val service = mockk<MetadataService>()
    private val permissionEvaluator = mockk<MetadataPermissionEvaluator>()
    private val json = Json

    private val controller = DocumentController(service, permissionEvaluator, json)
    private val authentication = mockk<AuthenticationContext>()

    @AfterTest
    fun tearDown() {
        clearAllMocks()
    }

    private fun metadata(id: UUID) = Metadata(
        id = id,
        name = "Template",
        type = MetadataType.STANDARD,
        contentType = "text/plain",
        contentLength = null,
        languageTag = "en",
        workflowStateId = "published"
    )

    @Test
    fun `title returns document title`() {
        val document = Document(metadataId = UUID.random(), version = 1, title = "My Doc")

        assertEquals("My Doc", controller.title(document))
    }

    @Test
    fun `content returns encoded json when content present`() {
        val content = Content()
        val document = Document(metadataId = UUID.random(), version = 1, title = "Doc", content = content)

        assertEquals(json.encodeToJsonElement(content), controller.content(document))
    }

    @Test
    fun `content returns encoded json null when content absent`() {
        val document = Document(metadataId = UUID.random(), version = 1, title = "Doc", content = null)

        assertEquals(json.encodeToJsonElement<Content?>(null), controller.content(document))
    }

    @Test
    fun `template returns null when templateMetadataId is null`() = runTest {
        val document = Document(
            metadataId = UUID.random(),
            version = 1,
            title = "Doc",
            templateMetadataId = null
        )

        assertNull(controller.template(authentication, document))
    }

    @Test
    fun `template resolves using explicit version when present`() = runTest {
        val templateId = UUID.random()
        val md = metadata(templateId)
        val document = Document(
            metadataId = UUID.random(),
            version = 1,
            title = "Doc",
            templateMetadataId = templateId,
            templateMetadataVersion = 7
        )

        coEvery { service.getById(templateId, 7) } returns md
        coEvery { permissionEvaluator.isAllowed(authentication, md, PermissionAction.VIEW) } returns true

        assertEquals(md, controller.template(authentication, document))
    }

    @Test
    fun `template defaults to version 1 when templateMetadataVersion is null`() = runTest {
        val templateId = UUID.random()
        val md = metadata(templateId)
        val document = Document(
            metadataId = UUID.random(),
            version = 1,
            title = "Doc",
            templateMetadataId = templateId,
            templateMetadataVersion = null
        )

        coEvery { service.getById(templateId, 1) } returns md
        coEvery { permissionEvaluator.isAllowed(authentication, md, PermissionAction.VIEW) } returns true

        assertEquals(md, controller.template(authentication, document))
    }

    @Test
    fun `template returns null when metadata not found`() = runTest {
        val templateId = UUID.random()
        val document = Document(
            metadataId = UUID.random(),
            version = 1,
            title = "Doc",
            templateMetadataId = templateId,
            templateMetadataVersion = 2
        )

        coEvery { service.getById(templateId, 2) } returns null

        assertNull(controller.template(authentication, document))
    }

    @Test
    fun `template returns null when not allowed`() = runTest {
        val templateId = UUID.random()
        val md = metadata(templateId)
        val document = Document(
            metadataId = UUID.random(),
            version = 1,
            title = "Doc",
            templateMetadataId = templateId,
            templateMetadataVersion = 3
        )

        coEvery { service.getById(templateId, 3) } returns md
        coEvery { permissionEvaluator.isAllowed(authentication, md, PermissionAction.VIEW) } returns false

        assertNull(controller.template(authentication, document))
    }

    @Test
    fun `template handles null authentication`() = runTest {
        val templateId = UUID.random()
        val md = metadata(templateId)
        val document = Document(
            metadataId = UUID.random(),
            version = 1,
            title = "Doc",
            templateMetadataId = templateId,
            templateMetadataVersion = 4
        )

        coEvery { service.getById(templateId, 4) } returns md
        coEvery { permissionEvaluator.isAllowed(null, md, PermissionAction.VIEW) } returns true

        assertEquals(md, controller.template(null, document))
    }
}
