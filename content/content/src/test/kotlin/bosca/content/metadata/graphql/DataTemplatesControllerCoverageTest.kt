package bosca.content.metadata.graphql

import bosca.content.metadata.model.DataTemplate
import bosca.content.metadata.model.DataType
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataType
import bosca.content.metadata.service.DataTemplateService
import bosca.content.metadata.service.MetadataService
import bosca.content.security.MetadataPermissionEvaluator
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DataTemplatesControllerCoverageTest {

    private val dataTemplateService = mockk<DataTemplateService>()
    private val metadataService = mockk<MetadataService>()
    private val permissionEvaluator = mockk<MetadataPermissionEvaluator>()

    private val controller = DataTemplatesController(dataTemplateService, metadataService, permissionEvaluator)
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
    fun `DataTemplates object exists`() {
        assertEquals(DataTemplates, DataTemplates)
    }

    @Test
    fun `all returns empty list when no templates`() = runTest {
        coEvery { dataTemplateService.getAll() } returns emptyList()

        assertTrue(controller.all(authentication).isEmpty())

        coVerify(exactly = 0) { metadataService.getById(any(), any<Int>()) }
    }

    @Test
    fun `all includes template when metadata found and permission allowed`() = runTest {
        val metadataId = UUID.random()
        val template = DataTemplate(metadataId = metadataId, version = 3, type = DataType.ATTRIBUTES)
        val md = metadata(metadataId)

        coEvery { dataTemplateService.getAll() } returns listOf(template)
        coEvery { metadataService.getById(metadataId, 3) } returns md
        coEvery { permissionEvaluator.isAllowed(authentication, md, PermissionAction.VIEW) } returns true

        val result = controller.all(authentication)

        assertEquals(listOf(template), result)
    }

    @Test
    fun `all excludes template when metadata not found`() = runTest {
        val metadataId = UUID.random()
        val template = DataTemplate(metadataId = metadataId, version = 5)

        coEvery { dataTemplateService.getAll() } returns listOf(template)
        coEvery { metadataService.getById(metadataId, 5) } returns null

        val result = controller.all(authentication)

        assertTrue(result.isEmpty())
        coVerify(exactly = 0) { permissionEvaluator.isAllowed(any<AuthenticationContext>(), any<Metadata>(), any<PermissionAction>()) }
    }

    @Test
    fun `all excludes template when permission denied`() = runTest {
        val metadataId = UUID.random()
        val template = DataTemplate(metadataId = metadataId, version = 2)
        val md = metadata(metadataId)

        coEvery { dataTemplateService.getAll() } returns listOf(template)
        coEvery { metadataService.getById(metadataId, 2) } returns md
        coEvery { permissionEvaluator.isAllowed(authentication, md, PermissionAction.VIEW) } returns false

        val result = controller.all(authentication)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `all filters mixed set retaining only allowed and found templates`() = runTest {
        val allowedId = UUID.random()
        val deniedId = UUID.random()
        val missingId = UUID.random()

        val allowedTemplate = DataTemplate(metadataId = allowedId, version = 1)
        val deniedTemplate = DataTemplate(metadataId = deniedId, version = 1)
        val missingTemplate = DataTemplate(metadataId = missingId, version = 1)

        val allowedMetadata = metadata(allowedId)
        val deniedMetadata = metadata(deniedId)

        coEvery { dataTemplateService.getAll() } returns listOf(allowedTemplate, deniedTemplate, missingTemplate)
        coEvery { metadataService.getById(allowedId, 1) } returns allowedMetadata
        coEvery { metadataService.getById(deniedId, 1) } returns deniedMetadata
        coEvery { metadataService.getById(missingId, 1) } returns null
        coEvery { permissionEvaluator.isAllowed(authentication, allowedMetadata, PermissionAction.VIEW) } returns true
        coEvery { permissionEvaluator.isAllowed(authentication, deniedMetadata, PermissionAction.VIEW) } returns false

        val result = controller.all(authentication)

        assertEquals(listOf(allowedTemplate), result)
    }
}
