package bosca.content.metadata.graphql

import bosca.content.metadata.model.GuideTemplate
import bosca.content.metadata.model.GuideType
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataType
import bosca.content.metadata.service.GuideTemplateService
import bosca.content.metadata.service.MetadataService
import bosca.content.security.MetadataPermissionEvaluator
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GuideTemplatesControllerCoverageTest {

    private val guideTemplateService = mockk<GuideTemplateService>()
    private val metadataService = mockk<MetadataService>()
    private val permissionEvaluator = mockk<MetadataPermissionEvaluator>()

    private val controller = GuideTemplatesController(guideTemplateService, metadataService, permissionEvaluator)
    private val authentication = mockk<AuthenticationContext>()

    @AfterTest
    fun tearDown() {
        clearAllMocks()
    }

    private fun template(
        metadataId: UUID = UUID.random(),
        version: Int = 1,
        rrule: String = "FREQ=DAILY",
        type: GuideType = GuideType.LINEAR
    ) = GuideTemplate(
        metadataId = metadataId,
        version = version,
        rrule = rrule,
        type = type
    )

    private fun metadata(id: UUID = UUID.random(), version: Int = 1) = Metadata(
        id = id,
        name = "template",
        type = MetadataType.STANDARD,
        contentType = "text/plain",
        contentLength = null,
        languageTag = "en",
        workflowStateId = "published",
        version = version
    )

    // ---------- GuideTemplates object ----------

    @Test
    fun `GuideTemplates object is accessible`() {
        assertEquals(GuideTemplates, GuideTemplates)
    }

    // ---------- all ----------

    @Test
    fun `all returns empty list when service returns none`() = runTest {
        coEvery { guideTemplateService.getAll() } returns emptyList()

        val result = controller.all(authentication)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `all keeps template when metadata present and allowed`() = runTest {
        val metadataId = UUID.random()
        val tmpl = template(metadataId = metadataId, version = 2)
        val md = metadata(id = metadataId, version = 2)

        coEvery { guideTemplateService.getAll() } returns listOf(tmpl)
        coEvery { metadataService.getById(metadataId, 2) } returns md
        coEvery { permissionEvaluator.isAllowed(authentication, md, PermissionAction.VIEW) } returns true

        val result = controller.all(authentication)

        assertEquals(listOf(tmpl), result)
        coVerify { permissionEvaluator.isAllowed(authentication, md, PermissionAction.VIEW) }
    }

    @Test
    fun `all drops template when metadata is null`() = runTest {
        val metadataId = UUID.random()
        val tmpl = template(metadataId = metadataId, version = 5)

        coEvery { guideTemplateService.getAll() } returns listOf(tmpl)
        coEvery { metadataService.getById(metadataId, 5) } returns null

        val result = controller.all(authentication)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `all drops template when permission denied`() = runTest {
        val metadataId = UUID.random()
        val tmpl = template(metadataId = metadataId, version = 3)
        val md = metadata(id = metadataId, version = 3)

        coEvery { guideTemplateService.getAll() } returns listOf(tmpl)
        coEvery { metadataService.getById(metadataId, 3) } returns md
        coEvery { permissionEvaluator.isAllowed(authentication, md, PermissionAction.VIEW) } returns false

        val result = controller.all(authentication)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `all filters mixed list keeping only allowed with present metadata`() = runTest {
        val allowedId = UUID.random()
        val deniedId = UUID.random()
        val missingId = UUID.random()

        val allowed = template(metadataId = allowedId, version = 1)
        val denied = template(metadataId = deniedId, version = 1)
        val missing = template(metadataId = missingId, version = 1)

        val allowedMd = metadata(id = allowedId, version = 1)
        val deniedMd = metadata(id = deniedId, version = 1)

        coEvery { guideTemplateService.getAll() } returns listOf(allowed, denied, missing)
        coEvery { metadataService.getById(allowedId, 1) } returns allowedMd
        coEvery { metadataService.getById(deniedId, 1) } returns deniedMd
        coEvery { metadataService.getById(missingId, 1) } returns null
        coEvery { permissionEvaluator.isAllowed(authentication, allowedMd, PermissionAction.VIEW) } returns true
        coEvery { permissionEvaluator.isAllowed(authentication, deniedMd, PermissionAction.VIEW) } returns false

        val result = controller.all(authentication)

        assertEquals(listOf(allowed), result)
    }
}
