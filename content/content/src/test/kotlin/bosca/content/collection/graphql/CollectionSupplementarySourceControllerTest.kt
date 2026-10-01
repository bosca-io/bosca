package bosca.content.collection.graphql

import bosca.content.collection.model.Collection
import bosca.content.collection.model.CollectionSupplementary
import bosca.content.collection.model.CollectionSupplementarySource
import bosca.content.collection.model.CollectionType
import bosca.serialization.UUID
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CollectionSupplementarySourceControllerTest {

    private val controller = CollectionSupplementarySourceController()

    private fun createSource(
        sourceId: UUID? = null,
        sourceIdentifier: String? = null
    ): CollectionSupplementarySource {
        val collection = Collection(
            id = UUID.random(),
            name = "Test",
            languageTag = "en",
            type = CollectionType.STANDARD,
            workflowStateId = "published"
        )
        val supplementary = CollectionSupplementary(
            id = UUID.random(),
            collectionId = collection.id,
            key = "key",
            name = "name",
            created = OffsetDateTime.now(),
            modified = OffsetDateTime.now(),
            sourceId = sourceId,
            sourceIdentifier = sourceIdentifier
        )
        return CollectionSupplementarySource(collection, supplementary)
    }

    @Test
    fun `id returns sourceId from supplementary`() {
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
    fun `identifier returns sourceIdentifier from supplementary`() {
        val source = createSource(sourceIdentifier = "ext-456")

        assertEquals("ext-456", controller.identifier(source))
    }

    @Test
    fun `identifier returns null when sourceIdentifier is null`() {
        val source = createSource(sourceIdentifier = null)

        assertNull(controller.identifier(source))
    }
}
