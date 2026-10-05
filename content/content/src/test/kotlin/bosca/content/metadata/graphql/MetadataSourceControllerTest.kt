package bosca.content.metadata.graphql

import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataType
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID
import bosca.source.model.Source
import bosca.source.service.SourceService
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MetadataSourceControllerTest {

    private val sourceService = mockk<SourceService>()
    private val groupEvaluator = mockk<GroupEvaluator>()

    private val controller = MetadataSourceController(sourceService, groupEvaluator)
    private val authentication = mockk<AuthenticationContext>()

    @AfterTest
    fun tearDown() {
        clearAllMocks()
    }

    private fun createMetadata(
        sourceId: UUID? = null,
        sourceIdentifier: String? = null,
        sourceUrl: String? = null
    ) = Metadata(
        id = UUID.random(),
        name = "Test",
        type = MetadataType.STANDARD,
        contentType = "text/plain",
        contentLength = null,
        languageTag = "en",
        workflowStateId = "published",
        sourceId = sourceId,
        sourceIdentifier = sourceIdentifier,
        sourceUrl = sourceUrl
    )

    @Test
    fun `id returns sourceId from metadata`() {
        val sourceId = UUID.random()
        val metadata = createMetadata(sourceId = sourceId)
        val source = MetadataSource(metadata)

        assertEquals(sourceId, controller.id(source))
    }

    @Test
    fun `id returns null when sourceId is null`() {
        val metadata = createMetadata(sourceId = null)
        val source = MetadataSource(metadata)

        assertNull(controller.id(source))
    }

    @Test
    fun `identifier returns sourceIdentifier from metadata`() {
        val metadata = createMetadata(sourceIdentifier = "ext-123")
        val source = MetadataSource(metadata)

        assertEquals("ext-123", controller.identifier(source))
    }

    @Test
    fun `url returns sourceUrl from metadata`() {
        val metadata = createMetadata(sourceUrl = "https://example.com/resource")
        val source = MetadataSource(metadata)

        assertEquals("https://example.com/resource", controller.url(source))
    }

    @Test
    fun `source returns Source when sourceId is present and authorized`() = runTest {
        val sourceId = UUID.random()
        val metadata = createMetadata(sourceId = sourceId)
        val metadataSource = MetadataSource(metadata)
        val expectedSource = Source(
            id = sourceId,
            name = "External Source",
            description = "An external data source",
            configuration = JsonObject(emptyMap())
        )

        coEvery { groupEvaluator.verifyHasSaGroup(authentication) } returns Unit
        coEvery { sourceService.getById(sourceId) } returns expectedSource

        val result = controller.source(authentication, metadataSource)

        assertEquals(expectedSource, result)
    }

    @Test
    fun `source returns null when sourceId is null`() = runTest {
        val metadata = createMetadata(sourceId = null)
        val metadataSource = MetadataSource(metadata)

        coEvery { groupEvaluator.verifyHasSaGroup(authentication) } returns Unit

        val result = controller.source(authentication, metadataSource)

        assertNull(result)
    }
}
