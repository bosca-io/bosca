package bosca.content.collection.graphql

import bosca.content.collection.model.Collection
import bosca.content.collection.model.CollectionSupplementary
import bosca.content.collection.model.CollectionType
import bosca.serialization.UUID
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class CollectionSupplementaryControllerTest {

    private val controller = CollectionSupplementaryController()

    private fun createCollection(id: UUID = UUID.random()) = Collection(
        id = id,
        name = "Test Collection",
        languageTag = "en",
        type = CollectionType.STANDARD,
        workflowStateId = "published"
    )

    private fun createSupplementary(
        id: UUID = UUID.random(),
        collectionId: UUID = UUID.random(),
        key: String = "test-key",
        name: String = "Test Supplementary",
        sourceId: UUID? = null,
        sourceIdentifier: String? = null
    ) = CollectionSupplementary(
        id = id,
        collectionId = collectionId,
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
        val supplementary = createSupplementary(id = suppId)
        val collection = createCollection()
        val context = CollectionSupplementaryContext(collection, supplementary)

        assertEquals(suppId, controller.id(context))
    }

    @Test
    fun `name returns supplementary name`() {
        val supplementary = createSupplementary(name = "My Supplementary")
        val collection = createCollection()
        val context = CollectionSupplementaryContext(collection, supplementary)

        assertEquals("My Supplementary", controller.name(context))
    }

    @Test
    fun `key returns supplementary key`() {
        val supplementary = createSupplementary(key = "audio")
        val collection = createCollection()
        val context = CollectionSupplementaryContext(collection, supplementary)

        assertEquals("audio", controller.key(context))
    }

    @Test
    fun `collectionId returns collection id`() {
        val collectionId = UUID.random()
        val collection = createCollection(id = collectionId)
        val supplementary = createSupplementary()
        val context = CollectionSupplementaryContext(collection, supplementary)

        assertEquals(collectionId, controller.collectionId(context))
    }

    @Test
    fun `content returns CollectionSupplementaryContent`() {
        val collection = createCollection()
        val supplementary = createSupplementary()
        val context = CollectionSupplementaryContext(collection, supplementary)

        val content = controller.content(context)

        assertNotNull(content)
    }

    @Test
    fun `source returns null when both sourceId and sourceIdentifier are null`() {
        val supplementary = createSupplementary(sourceId = null, sourceIdentifier = null)
        val collection = createCollection()
        val context = CollectionSupplementaryContext(collection, supplementary)

        assertNull(controller.source(context))
    }

    @Test
    fun `source returns CollectionSupplementarySource when sourceId is present`() {
        val supplementary = createSupplementary(sourceId = UUID.random())
        val collection = createCollection()
        val context = CollectionSupplementaryContext(collection, supplementary)

        assertNotNull(controller.source(context))
    }

    @Test
    fun `source returns CollectionSupplementarySource when sourceIdentifier is present`() {
        val supplementary = createSupplementary(sourceIdentifier = "external-123")
        val collection = createCollection()
        val context = CollectionSupplementaryContext(collection, supplementary)

        assertNotNull(controller.source(context))
    }

    @Test
    fun `attributes returns supplementary attributes`() {
        val attrs = JsonObject(mapOf("format" to JsonPrimitive("mp3")))
        val supplementary = CollectionSupplementary(
            id = UUID.random(),
            collectionId = UUID.random(),
            key = "key",
            name = "name",
            created = OffsetDateTime.now(),
            modified = OffsetDateTime.now(),
            attributes = attrs
        )
        val collection = createCollection()
        val context = CollectionSupplementaryContext(collection, supplementary)

        assertEquals(attrs, controller.attributes(context))
    }
}
