package bosca.content.transformations

import bosca.content.collection.model.Collection
import bosca.content.collection.model.CollectionLanguageVariant
import bosca.content.collection.service.CollectionService
import bosca.serialization.UUID
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DefaultCollectionToSearchCollectionsCoverageTest {

    private val collectionService = mockk<CollectionService>()

    private val transform = DefaultCollectionToSearchCollections()

    private val collectionId = UUID.random()

    @AfterTest
    fun tearDown() {
        clearAllMocks()
        unmockkAll()
    }

    private fun createCollection(
        id: UUID = collectionId,
        name: String = "Child Collection"
    ) = Collection(
        id = id,
        name = name,
        languageTag = "en",
        workflowStateId = "published"
    )

    private fun createParent(
        id: UUID = UUID.random(),
        name: String = "Parent Collection",
        attributes: kotlinx.serialization.json.JsonElement? = null
    ) = Collection(
        id = id,
        name = name,
        languageTag = "en",
        workflowStateId = "published",
        attributes = attributes
    )

    @Test
    fun `returns empty map when no parents exist`() = runTest {
        coEvery { collectionService.getCollectionParents(collectionId) } returns emptyList()

        val result = transform.toCollections(collectionService, createCollection(), null)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `null attributes default to collections group`() = runTest {
        val parentId = UUID.random()
        val parent = createParent(id = parentId, name = "No Attrs", attributes = null)
        coEvery { collectionService.getCollectionParents(collectionId) } returns listOf(parent)

        val result = transform.toCollections(collectionService, createCollection(), null)

        assertTrue(result.containsKey("collections"))
        assertEquals(1, result["collections"]?.size)
        assertEquals(parentId.toString(), result["collections"]?.first()?.id)
        assertEquals("No Attrs", result["collections"]?.first()?.name)
    }

    @Test
    fun `JsonNull attributes default to collections group`() = runTest {
        val parent = createParent(name = "Null Attrs", attributes = JsonNull)
        coEvery { collectionService.getCollectionParents(collectionId) } returns listOf(parent)

        val result = transform.toCollections(collectionService, createCollection(), null)

        assertTrue(result.containsKey("collections"))
        assertEquals(1, result["collections"]?.size)
    }

    @Test
    fun `attributes object without type key defaults to collections group`() = runTest {
        val parent = createParent(
            name = "No Type Key",
            attributes = JsonObject(mapOf("other" to JsonPrimitive("value")))
        )
        coEvery { collectionService.getCollectionParents(collectionId) } returns listOf(parent)

        val result = transform.toCollections(collectionService, createCollection(), null)

        assertTrue(result.containsKey("collections"))
        assertEquals(1, result["collections"]?.size)
    }

    @Test
    fun `type not ending in s is pluralized`() = runTest {
        val showId = UUID.random()
        val parent = createParent(
            id = showId,
            name = "My Show",
            attributes = JsonObject(mapOf("type" to JsonPrimitive("show")))
        )
        coEvery { collectionService.getCollectionParents(collectionId) } returns listOf(parent)

        val result = transform.toCollections(collectionService, createCollection(), null)

        assertTrue(result.containsKey("shows"))
        assertFalse(result.containsKey("show"))
        assertEquals(showId.toString(), result["shows"]?.first()?.id)
    }

    @Test
    fun `type already ending in s is kept unchanged`() = runTest {
        val parent = createParent(
            name = "A Series",
            attributes = JsonObject(mapOf("type" to JsonPrimitive("series")))
        )
        coEvery { collectionService.getCollectionParents(collectionId) } returns listOf(parent)

        val result = transform.toCollections(collectionService, createCollection(), null)

        assertTrue(result.containsKey("series"))
        assertEquals(1, result["series"]?.size)
    }

    @Test
    fun `type is lowercased before grouping`() = runTest {
        val parent = createParent(
            name = "Uppercase Type",
            attributes = JsonObject(mapOf("type" to JsonPrimitive("SHOW")))
        )
        coEvery { collectionService.getCollectionParents(collectionId) } returns listOf(parent)

        val result = transform.toCollections(collectionService, createCollection(), null)

        assertTrue(result.containsKey("shows"))
        assertFalse(result.containsKey("SHOWS"))
    }

    @Test
    fun `multiple parents with same type are grouped together`() = runTest {
        val id1 = UUID.random()
        val id2 = UUID.random()
        val parent1 = createParent(
            id = id1,
            name = "Show One",
            attributes = JsonObject(mapOf("type" to JsonPrimitive("show")))
        )
        val parent2 = createParent(
            id = id2,
            name = "Show Two",
            attributes = JsonObject(mapOf("type" to JsonPrimitive("show")))
        )
        coEvery { collectionService.getCollectionParents(collectionId) } returns listOf(parent1, parent2)

        val result = transform.toCollections(collectionService, createCollection(), null)

        assertEquals(2, result["shows"]?.size)
        assertEquals(setOf(id1.toString(), id2.toString()), result["shows"]?.map { it.id }?.toSet())
    }

    @Test
    fun `parents with different types produce separate groups`() = runTest {
        val showParent = createParent(
            name = "The Show",
            attributes = JsonObject(mapOf("type" to JsonPrimitive("show")))
        )
        val plainParent = createParent(name = "Plain", attributes = null)
        coEvery { collectionService.getCollectionParents(collectionId) } returns listOf(showParent, plainParent)

        val result = transform.toCollections(collectionService, createCollection(), null)

        assertEquals(2, result.size)
        assertTrue(result.containsKey("shows"))
        assertTrue(result.containsKey("collections"))
        assertEquals(1, result["shows"]?.size)
        assertEquals(1, result["collections"]?.size)
    }

    @Test
    fun `variant argument is ignored and does not affect grouping`() = runTest {
        val parent = createParent(name = "Parent", attributes = null)
        val variant = CollectionLanguageVariant(
            id = UUID.random(),
            languageTag = "es",
            name = "Variant Name"
        )
        coEvery { collectionService.getCollectionParents(collectionId) } returns listOf(parent)

        val result = transform.toCollections(collectionService, createCollection(), variant)

        assertTrue(result.containsKey("collections"))
        assertEquals("Parent", result["collections"]?.first()?.name)
    }
}
