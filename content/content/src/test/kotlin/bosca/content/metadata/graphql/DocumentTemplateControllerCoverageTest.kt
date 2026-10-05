package bosca.content.metadata.graphql

import bosca.content.metadata.model.DocumentTemplate
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataType
import bosca.content.metadata.service.DocumentTemplateService
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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DocumentTemplateControllerCoverageTest {

    private val service = mockk<DocumentTemplateService>()
    private val metadataService = mockk<MetadataService>()
    private val permissionEvaluator = mockk<MetadataPermissionEvaluator>()
    private val json = Json

    private val controller = DocumentTemplateController(service, metadataService, permissionEvaluator, json)
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
    fun `configuration returns template configuration`() {
        val config = JsonPrimitive("config-value")
        val template = DocumentTemplate(metadataId = UUID.random(), version = 1, configuration = config)

        assertEquals(config, controller.configuration(template))
    }

    @Test
    fun `configuration returns null when absent`() {
        val template = DocumentTemplate(metadataId = UUID.random(), version = 1)

        assertNull(controller.configuration(template))
    }

    @Test
    fun `schema returns template schema`() {
        val schema = JsonPrimitive("schema-value")
        val template = DocumentTemplate(metadataId = UUID.random(), version = 1, schema = schema)

        assertEquals(schema, controller.schema(template))
    }

    @Test
    fun `schema returns null when absent`() {
        val template = DocumentTemplate(metadataId = UUID.random(), version = 1)

        assertNull(controller.schema(template))
    }

    @Test
    fun `content returns encoded json when content present`() {
        val content = Content()
        val template = DocumentTemplate(metadataId = UUID.random(), version = 1, content = content)

        assertEquals(json.encodeToJsonElement(content), controller.content(template))
    }

    @Test
    fun `content returns null when content absent`() {
        val template = DocumentTemplate(metadataId = UUID.random(), version = 1, content = null)

        assertNull(controller.content(template))
    }

    @Test
    fun `defaultAttributes returns template default attributes`() {
        val defaults = JsonPrimitive("defaults-value")
        val template = DocumentTemplate(metadataId = UUID.random(), version = 1, defaultAttributes = defaults)

        assertEquals(defaults, controller.defaultAttributes(template))
    }

    @Test
    fun `defaultAttributes returns null when absent`() {
        val template = DocumentTemplate(metadataId = UUID.random(), version = 1)

        assertNull(controller.defaultAttributes(template))
    }

    @Test
    fun `attributes delegates to service`() = runTest {
        val id = UUID.random()
        val template = DocumentTemplate(metadataId = id, version = 3)

        coEvery { service.getTemplateAttributes(id, 3) } returns emptyList()

        assertEquals(emptyList(), controller.attributes(template))
    }

    @Test
    fun `containers delegates to service`() = runTest {
        val id = UUID.random()
        val template = DocumentTemplate(metadataId = id, version = 5)

        coEvery { service.getTemplateContainers(id, 5) } returns emptyList()

        assertEquals(emptyList(), controller.containers(template))
    }

    @Test
    fun `metadata returns null when metadata not found`() = runTest {
        val id = UUID.random()
        val template = DocumentTemplate(metadataId = id, version = 2)

        coEvery { metadataService.getById(id, 2) } returns null

        assertNull(controller.metadata(authentication, template))
    }

    @Test
    fun `metadata returns null when not allowed`() = runTest {
        val id = UUID.random()
        val template = DocumentTemplate(metadataId = id, version = 2)
        val md = metadata(id)

        coEvery { metadataService.getById(id, 2) } returns md
        coEvery { permissionEvaluator.isAllowed(authentication, md, PermissionAction.VIEW) } returns false

        assertNull(controller.metadata(authentication, template))
    }

    @Test
    fun `metadata returns metadata when allowed`() = runTest {
        val id = UUID.random()
        val template = DocumentTemplate(metadataId = id, version = 2)
        val md = metadata(id)

        coEvery { metadataService.getById(id, 2) } returns md
        coEvery { permissionEvaluator.isAllowed(authentication, md, PermissionAction.VIEW) } returns true

        assertEquals(md, controller.metadata(authentication, template))
    }

    @Test
    fun `metadata handles null authentication`() = runTest {
        val id = UUID.random()
        val template = DocumentTemplate(metadataId = id, version = 2)
        val md = metadata(id)

        coEvery { metadataService.getById(id, 2) } returns md
        coEvery { permissionEvaluator.isAllowed(null, md, PermissionAction.VIEW) } returns true

        assertEquals(md, controller.metadata(null, template))
    }
}
