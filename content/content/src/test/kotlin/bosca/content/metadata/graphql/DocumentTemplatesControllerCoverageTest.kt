package bosca.content.metadata.graphql

import bosca.content.metadata.model.DocumentTemplate
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataType
import bosca.content.metadata.service.DocumentTemplateService
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

class DocumentTemplatesControllerCoverageTest {

    private val documentTemplateService = mockk<DocumentTemplateService>()
    private val metadataService = mockk<MetadataService>()
    private val permissionEvaluator = mockk<MetadataPermissionEvaluator>()

    private val controller =
        DocumentTemplatesController(documentTemplateService, metadataService, permissionEvaluator)
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
    fun `all returns empty list when no templates exist`() = runTest {
        coEvery { documentTemplateService.getAll() } returns emptyList()

        assertTrue(controller.all(authentication).isEmpty())

        coVerify(exactly = 0) { metadataService.getById(any<UUID>(), any<Int>()) }
    }

    @Test
    fun `all excludes template when metadata not found`() = runTest {
        val templateId = UUID.random()
        val template = DocumentTemplate(metadataId = templateId, version = 1)

        coEvery { documentTemplateService.getAll() } returns listOf(template)
        coEvery { metadataService.getById(templateId, 1) } returns null

        assertTrue(controller.all(authentication).isEmpty())

        coVerify(exactly = 0) {
            permissionEvaluator.isAllowed(any<AuthenticationContext>(), any<Metadata>(), any<PermissionAction>())
        }
    }

    @Test
    fun `all excludes template when permission denied`() = runTest {
        val templateId = UUID.random()
        val template = DocumentTemplate(metadataId = templateId, version = 1)
        val md = metadata(templateId)

        coEvery { documentTemplateService.getAll() } returns listOf(template)
        coEvery { metadataService.getById(templateId, 1) } returns md
        coEvery {
            permissionEvaluator.isAllowed(authentication, md, PermissionAction.VIEW)
        } returns false

        assertTrue(controller.all(authentication).isEmpty())
    }

    @Test
    fun `all includes template when permission allowed`() = runTest {
        val templateId = UUID.random()
        val template = DocumentTemplate(metadataId = templateId, version = 2)
        val md = metadata(templateId)

        coEvery { documentTemplateService.getAll() } returns listOf(template)
        coEvery { metadataService.getById(templateId, 2) } returns md
        coEvery {
            permissionEvaluator.isAllowed(authentication, md, PermissionAction.VIEW)
        } returns true

        val result = controller.all(authentication)

        assertEquals(1, result.size)
        assertEquals(template, result.single())
    }

    @Test
    fun `all filters mixed list keeping only allowed templates`() = runTest {
        val allowedId = UUID.random()
        val deniedId = UUID.random()
        val missingId = UUID.random()

        val allowed = DocumentTemplate(metadataId = allowedId, version = 1)
        val denied = DocumentTemplate(metadataId = deniedId, version = 1)
        val missing = DocumentTemplate(metadataId = missingId, version = 1)

        val allowedMd = metadata(allowedId)
        val deniedMd = metadata(deniedId)

        coEvery { documentTemplateService.getAll() } returns listOf(allowed, denied, missing)
        coEvery { metadataService.getById(allowedId, 1) } returns allowedMd
        coEvery { metadataService.getById(deniedId, 1) } returns deniedMd
        coEvery { metadataService.getById(missingId, 1) } returns null
        coEvery {
            permissionEvaluator.isAllowed(authentication, allowedMd, PermissionAction.VIEW)
        } returns true
        coEvery {
            permissionEvaluator.isAllowed(authentication, deniedMd, PermissionAction.VIEW)
        } returns false

        val result = controller.all(authentication)

        assertEquals(listOf(allowed), result)
    }
}
