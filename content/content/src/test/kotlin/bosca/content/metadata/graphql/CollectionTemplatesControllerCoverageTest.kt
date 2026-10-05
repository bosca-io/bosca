package bosca.content.metadata.graphql

import bosca.content.collection.model.CollectionTemplate
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataType
import bosca.content.metadata.service.CollectionTemplateService
import bosca.content.metadata.service.MetadataService
import bosca.content.security.MetadataPermissionEvaluator
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CollectionTemplatesControllerCoverageTest {

    private val metadataService = mockk<MetadataService>()
    private val metadataPermissionEvaluator = mockk<MetadataPermissionEvaluator>()
    private val collectionTemplateService = mockk<CollectionTemplateService>()

    private val controller = CollectionTemplatesController(
        metadataService,
        metadataPermissionEvaluator,
        collectionTemplateService
    )
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

    private fun template(metadataId: UUID = UUID.random(), version: Int = 1) = CollectionTemplate(
        metadataId = metadataId,
        version = version,
        configuration = null,
        defaultAttributes = null,
        filters = JsonObject(emptyMap()),
        ordering = null,
    )

    @Test
    fun `all returns empty list when there are no templates`() = runTest {
        coEvery { collectionTemplateService.getAll() } returns emptyList()

        val result = controller.all(authentication)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `all keeps only templates whose metadata is found and allowed`() = runTest {
        val allowedId = UUID.random()
        val deniedId = UUID.random()
        val missingId = UUID.random()

        val allowedTemplate = template(metadataId = allowedId, version = 2)
        val deniedTemplate = template(metadataId = deniedId, version = 3)
        val missingTemplate = template(metadataId = missingId, version = 4)

        val allowedMetadata = metadata(allowedId)
        val deniedMetadata = metadata(deniedId)

        coEvery { collectionTemplateService.getAll() } returns
            listOf(allowedTemplate, deniedTemplate, missingTemplate)

        // Missing metadata -> filtered out via return@filter false
        coEvery { metadataService.getById(missingId, 4) } returns null
        // Found metadata, allowed -> kept
        coEvery { metadataService.getById(allowedId, 2) } returns allowedMetadata
        coEvery {
            metadataPermissionEvaluator.isAllowed(authentication, allowedMetadata, PermissionAction.VIEW)
        } returns true
        // Found metadata, denied -> filtered out
        coEvery { metadataService.getById(deniedId, 3) } returns deniedMetadata
        coEvery {
            metadataPermissionEvaluator.isAllowed(authentication, deniedMetadata, PermissionAction.VIEW)
        } returns false

        val result = controller.all(authentication)

        assertEquals(listOf(allowedTemplate), result)
    }

    @Test
    fun `all filters out every template when none are allowed`() = runTest {
        val id = UUID.random()
        val t = template(metadataId = id, version = 5)
        val md = metadata(id)

        coEvery { collectionTemplateService.getAll() } returns listOf(t)
        coEvery { metadataService.getById(id, 5) } returns md
        coEvery {
            metadataPermissionEvaluator.isAllowed(authentication, md, PermissionAction.VIEW)
        } returns false

        val result = controller.all(authentication)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `all keeps all templates when all are allowed`() = runTest {
        val firstId = UUID.random()
        val secondId = UUID.random()
        val first = template(metadataId = firstId, version = 6)
        val second = template(metadataId = secondId, version = 7)
        val firstMetadata = metadata(firstId)
        val secondMetadata = metadata(secondId)

        coEvery { collectionTemplateService.getAll() } returns listOf(first, second)
        coEvery { metadataService.getById(firstId, 6) } returns firstMetadata
        coEvery { metadataService.getById(secondId, 7) } returns secondMetadata
        coEvery {
            metadataPermissionEvaluator.isAllowed(authentication, firstMetadata, PermissionAction.VIEW)
        } returns true
        coEvery {
            metadataPermissionEvaluator.isAllowed(authentication, secondMetadata, PermissionAction.VIEW)
        } returns true

        val result = controller.all(authentication)

        assertEquals(listOf(first, second), result)
    }
}
