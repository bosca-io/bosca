package bosca.content.metadata.graphql

import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataSupplementary
import bosca.content.metadata.model.MetadataSupplementarySource
import bosca.content.metadata.model.MetadataType
import bosca.serialization.UUID
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MetadataSupplementarySourceControllerTest {

    private val controller = MetadataSupplementarySourceController()

    private fun createSource(
        sourceId: UUID? = null,
        sourceIdentifier: String? = null
    ): MetadataSupplementarySource {
        val metadata = Metadata(
            id = UUID.random(),
            name = "Test",
            type = MetadataType.STANDARD,
            contentType = "text/plain",
            contentLength = null,
            languageTag = "en",
            workflowStateId = "published"
        )
        val supplementary = MetadataSupplementary(
            id = UUID.random(),
            metadataId = metadata.id,
            key = "key",
            name = "name",
            created = OffsetDateTime.now(),
            modified = OffsetDateTime.now(),
            sourceId = sourceId,
            sourceIdentifier = sourceIdentifier
        )
        return MetadataSupplementarySource(metadata, supplementary)
    }

    @Test
    fun `id returns sourceId`() {
        val sourceId = UUID.random()
        val source = createSource(sourceId = sourceId)

        assertEquals(sourceId, controller.id(source))
    }

    @Test
    fun `id returns null when sourceId is null`() {
        val source = createSource(sourceId = null)

        assertNull(controller.id(source))
    }

    @Test
    fun `identifier returns sourceIdentifier`() {
        val source = createSource(sourceIdentifier = "abc-123")

        assertEquals("abc-123", controller.identifier(source))
    }

    @Test
    fun `identifier returns null when sourceIdentifier is null`() {
        val source = createSource(sourceIdentifier = null)

        assertNull(controller.identifier(source))
    }
}
