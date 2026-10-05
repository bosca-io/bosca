package bosca.content.metadata.graphql

import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataType
import bosca.content.metadata.model.SourceStatus
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID
import bosca.source.service.SourceService
import io.mockk.clearAllMocks
import io.mockk.mockk
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Covers the [MetadataSourceController.status] resolver, which is the only member
 * not exercised by [MetadataSourceControllerTest].
 */
class MetadataSourceControllerCoverageTest {

    private val sourceService = mockk<SourceService>()
    private val groupEvaluator = mockk<GroupEvaluator>()

    private val controller = MetadataSourceController(sourceService, groupEvaluator)

    @Suppress("unused")
    private val authentication = mockk<AuthenticationContext>()

    @AfterTest
    fun tearDown() {
        clearAllMocks()
    }

    private fun createMetadata(sourceStatus: SourceStatus? = null) = Metadata(
        id = UUID.random(),
        name = "Test",
        type = MetadataType.STANDARD,
        contentType = "text/plain",
        contentLength = null,
        languageTag = "en",
        workflowStateId = "published",
        sourceStatus = sourceStatus
    )

    @Test
    fun `status returns sourceStatus from metadata`() {
        val metadata = createMetadata(sourceStatus = SourceStatus.IMPORTED)
        val source = MetadataSource(metadata)

        assertEquals(SourceStatus.IMPORTED, controller.status(source))
    }

    @Test
    fun `status returns EXTERNAL when metadata source is external`() {
        val metadata = createMetadata(sourceStatus = SourceStatus.EXTERNAL)
        val source = MetadataSource(metadata)

        assertEquals(SourceStatus.EXTERNAL, controller.status(source))
    }

    @Test
    fun `status returns null when sourceStatus is null`() {
        val metadata = createMetadata(sourceStatus = null)
        val source = MetadataSource(metadata)

        assertNull(controller.status(source))
    }
}
