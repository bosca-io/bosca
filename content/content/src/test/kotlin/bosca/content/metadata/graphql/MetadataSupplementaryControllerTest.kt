package bosca.content.metadata.graphql

import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataSupplementary
import bosca.content.metadata.model.MetadataType
import bosca.serialization.UUID
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class MetadataSupplementaryControllerTest {

    private val controller = MetadataSupplementaryController()

    private fun createMetadata(id: UUID = UUID.random()) = Metadata(
        id = id,
        name = "Test",
        type = MetadataType.STANDARD,
        contentType = "text/plain",
        contentLength = null,
        languageTag = "en",
        workflowStateId = "published"
    )

    private fun createSupplementary(
        id: UUID = UUID.random(),
        metadataId: UUID = UUID.random(),
        key: String = "audio",
        name: String = "Audio File",
        sourceId: UUID? = null,
        sourceIdentifier: String? = null
    ) = MetadataSupplementary(
        id = id,
        metadataId = metadataId,
        key = key,
        name = name,
        created = OffsetDateTime.now(),
        modified = OffsetDateTime.now(),
        sourceId = sourceId,
        sourceIdentifier = sourceIdentifier
    )

    @Test
    fun `id returns supplementary id`() {
        val suppId = UUID.random()
        val context = MetadataSupplementaryContext(
            metadata = createMetadata(),
            supplementary = createSupplementary(id = suppId)
        )

        assertEquals(suppId, controller.id(context))
    }

    @Test
    fun `name returns supplementary name`() {
        val context = MetadataSupplementaryContext(
            metadata = createMetadata(),
            supplementary = createSupplementary(name = "Transcript")
        )

        assertEquals("Transcript", controller.name(context))
    }

    @Test
    fun `key returns supplementary key`() {
        val context = MetadataSupplementaryContext(
            metadata = createMetadata(),
            supplementary = createSupplementary(key = "transcript")
        )

        assertEquals("transcript", controller.key(context))
    }

    @Test
    fun `metadataId returns metadata id`() {
        val metadataId = UUID.random()
        val context = MetadataSupplementaryContext(
            metadata = createMetadata(id = metadataId),
            supplementary = createSupplementary()
        )

        assertEquals(metadataId, controller.metadataId(context))
    }

    @Test
    fun `content returns MetadataSupplementaryContent`() {
        val context = MetadataSupplementaryContext(
            metadata = createMetadata(),
            supplementary = createSupplementary()
        )

        val content = controller.content(context)

        assertNotNull(content)
    }

    @Test
    fun `source returns null when both sourceId and sourceIdentifier are null`() {
        val context = MetadataSupplementaryContext(
            metadata = createMetadata(),
            supplementary = createSupplementary(sourceId = null, sourceIdentifier = null)
        )

        assertNull(controller.source(context))
    }

    @Test
    fun `source returns MetadataSupplementarySource when sourceId is present`() {
        val context = MetadataSupplementaryContext(
            metadata = createMetadata(),
            supplementary = createSupplementary(sourceId = UUID.random())
        )

        assertNotNull(controller.source(context))
    }

    @Test
    fun `source returns MetadataSupplementarySource when sourceIdentifier is present`() {
        val context = MetadataSupplementaryContext(
            metadata = createMetadata(),
            supplementary = createSupplementary(sourceIdentifier = "ext-id")
        )

        assertNotNull(controller.source(context))
    }
}
