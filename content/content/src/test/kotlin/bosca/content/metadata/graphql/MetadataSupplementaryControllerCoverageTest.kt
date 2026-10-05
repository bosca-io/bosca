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
import kotlin.test.assertNull

class MetadataSupplementaryControllerCoverageTest {

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
        planId: UUID? = null,
        attributes: kotlinx.serialization.json.JsonElement? = null,
        created: OffsetDateTime = OffsetDateTime.now(),
        modified: OffsetDateTime = OffsetDateTime.now(),
        uploaded: OffsetDateTime? = null,
        sourceId: UUID? = null,
        sourceIdentifier: String? = null
    ) = MetadataSupplementary(
        id = id,
        metadataId = metadataId,
        key = key,
        name = name,
        planId = planId,
        attributes = attributes,
        created = created,
        modified = modified,
        uploaded = uploaded,
        sourceId = sourceId,
        sourceIdentifier = sourceIdentifier
    )

    @Test
    fun `planId returns supplementary planId`() {
        val planId = UUID.random()
        val context = MetadataSupplementaryContext(
            metadata = createMetadata(),
            supplementary = createSupplementary(planId = planId)
        )

        assertEquals(planId, controller.planId(context))
    }

    @Test
    fun `planId returns null when absent`() {
        val context = MetadataSupplementaryContext(
            metadata = createMetadata(),
            supplementary = createSupplementary(planId = null)
        )

        assertNull(controller.planId(context))
    }

    @Test
    fun `created returns supplementary created`() {
        val created = OffsetDateTime.now().minusDays(2)
        val context = MetadataSupplementaryContext(
            metadata = createMetadata(),
            supplementary = createSupplementary(created = created)
        )

        assertEquals(created, controller.created(context))
    }

    @Test
    fun `modified returns supplementary modified`() {
        val modified = OffsetDateTime.now().minusHours(3)
        val context = MetadataSupplementaryContext(
            metadata = createMetadata(),
            supplementary = createSupplementary(modified = modified)
        )

        assertEquals(modified, controller.modified(context))
    }

    @Test
    fun `attributes returns supplementary attributes`() {
        val attributes = JsonObject(mapOf("k" to JsonPrimitive("v")))
        val context = MetadataSupplementaryContext(
            metadata = createMetadata(),
            supplementary = createSupplementary(attributes = attributes)
        )

        assertEquals(attributes, controller.attributes(context))
    }

    @Test
    fun `attributes returns null when absent`() {
        val context = MetadataSupplementaryContext(
            metadata = createMetadata(),
            supplementary = createSupplementary(attributes = null)
        )

        assertNull(controller.attributes(context))
    }

    @Test
    fun `uploaded returns supplementary uploaded`() {
        val uploaded = OffsetDateTime.now().minusMinutes(30)
        val context = MetadataSupplementaryContext(
            metadata = createMetadata(),
            supplementary = createSupplementary(uploaded = uploaded)
        )

        assertEquals(uploaded, controller.uploaded(context))
    }

    @Test
    fun `uploaded returns null when absent`() {
        val context = MetadataSupplementaryContext(
            metadata = createMetadata(),
            supplementary = createSupplementary(uploaded = null)
        )

        assertNull(controller.uploaded(context))
    }
}
