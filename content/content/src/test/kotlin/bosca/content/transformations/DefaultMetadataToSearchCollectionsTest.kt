package bosca.content.transformations

import bosca.content.collection.model.Collection
import bosca.content.collection.model.CollectionLanguageVariant
import bosca.content.collection.service.CollectionService
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataType
import bosca.content.metadata.service.MetadataService
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DefaultMetadataToSearchCollectionsTest {

    private val collectionService = mockk<CollectionService>()
    private val metadataService = mockk<MetadataService>()

    private val searchCollections = DefaultMetadataToSearchCollections(collectionService)

    private val metadataId = UUID.random()

    private fun createMetadata(languageTag: String = "es") = Metadata(
        id = metadataId,
        name = "Test Metadata",
        type = MetadataType.STANDARD,
        languageTag = languageTag,
        contentType = "text/plain",
        contentLength = 0,
        workflowStateId = "published"
    )

    private fun createParentCollection(
        id: UUID = UUID.random(),
        name: String = "Parent Collection",
        type: String? = null
    ): Collection {
        val attrs = if (type != null) {
            JsonObject(mapOf("type" to JsonPrimitive(type)))
        } else {
            null
        }
        return Collection(
            id = id,
            name = name,
            languageTag = "en",
            workflowStateId = "published",
            attributes = attrs
        )
    }

    private fun createVariant(
        id: UUID,
        languageTag: String,
        name: String,
        workflowStateId: String
    ) = CollectionLanguageVariant(
        id = id,
        languageTag = languageTag,
        name = name,
        workflowStateId = workflowStateId,
    )

    @Test
    fun `uses published variant name when matching language exists`() = runTest {
        val parentId = UUID.random()
        val parent = createParentCollection(id = parentId, name = "English Name")
        val metadata = createMetadata(languageTag = "es")

        coEvery { metadataService.getParents(metadataId) } returns listOf(parent)
        coEvery { collectionService.getLanguageVariants(parentId) } returns listOf(
            createVariant(parentId, "es", "Spanish Name", "published")
        )

        val result = searchCollections.toCollections(metadataService, metadata)

        assertEquals("Spanish Name", result["collections"]?.first()?.name)
    }

    @Test
    fun `uses advertised variant name when matching language exists`() = runTest {
        val parentId = UUID.random()
        val parent = createParentCollection(id = parentId, name = "English Name")
        val metadata = createMetadata(languageTag = "es")

        coEvery { metadataService.getParents(metadataId) } returns listOf(parent)
        coEvery { collectionService.getLanguageVariants(parentId) } returns listOf(
            createVariant(parentId, "es", "Spanish Advertised", "advertised")
        )

        val result = searchCollections.toCollections(metadataService, metadata)

        assertEquals("Spanish Advertised", result["collections"]?.first()?.name)
    }

    @Test
    fun `falls back to collection name when variant is draft`() = runTest {
        val parentId = UUID.random()
        val parent = createParentCollection(id = parentId, name = "English Name")
        val metadata = createMetadata(languageTag = "es")

        coEvery { metadataService.getParents(metadataId) } returns listOf(parent)
        coEvery { collectionService.getLanguageVariants(parentId) } returns listOf(
            createVariant(parentId, "es", "Draft Spanish", "draft")
        )

        val result = searchCollections.toCollections(metadataService, metadata)

        assertEquals("English Name", result["collections"]?.first()?.name)
    }

    @Test
    fun `falls back to collection name when variant is in review`() = runTest {
        val parentId = UUID.random()
        val parent = createParentCollection(id = parentId, name = "English Name")
        val metadata = createMetadata(languageTag = "es")

        coEvery { metadataService.getParents(metadataId) } returns listOf(parent)
        coEvery { collectionService.getLanguageVariants(parentId) } returns listOf(
            createVariant(parentId, "es", "Review Spanish", "review")
        )

        val result = searchCollections.toCollections(metadataService, metadata)

        assertEquals("English Name", result["collections"]?.first()?.name)
    }

    @Test
    fun `falls back to collection name when no variants exist`() = runTest {
        val parentId = UUID.random()
        val parent = createParentCollection(id = parentId, name = "English Name")
        val metadata = createMetadata(languageTag = "es")

        coEvery { metadataService.getParents(metadataId) } returns listOf(parent)
        coEvery { collectionService.getLanguageVariants(parentId) } returns emptyList()

        val result = searchCollections.toCollections(metadataService, metadata)

        assertEquals("English Name", result["collections"]?.first()?.name)
    }

    @Test
    fun `falls back to collection name when variant language does not match`() = runTest {
        val parentId = UUID.random()
        val parent = createParentCollection(id = parentId, name = "English Name")
        val metadata = createMetadata(languageTag = "es")

        coEvery { metadataService.getParents(metadataId) } returns listOf(parent)
        coEvery { collectionService.getLanguageVariants(parentId) } returns listOf(
            createVariant(parentId, "fr", "French Name", "published")
        )

        val result = searchCollections.toCollections(metadataService, metadata)

        assertEquals("English Name", result["collections"]?.first()?.name)
    }

    @Test
    fun `uses variant with case-insensitive language match`() = runTest {
        val parentId = UUID.random()
        val parent = createParentCollection(id = parentId, name = "English Name")
        val metadata = createMetadata(languageTag = "ES")

        coEvery { metadataService.getParents(metadataId) } returns listOf(parent)
        coEvery { collectionService.getLanguageVariants(parentId) } returns listOf(
            createVariant(parentId, "es", "Spanish Name", "published")
        )

        val result = searchCollections.toCollections(metadataService, metadata)

        assertEquals("Spanish Name", result["collections"]?.first()?.name)
    }

    @Test
    fun `groups parents by type attribute`() = runTest {
        val showId = UUID.random()
        val show = createParentCollection(id = showId, name = "My Show", type = "show")
        val seasonId = UUID.random()
        val season = createParentCollection(id = seasonId, name = "Season 1", type = "season")
        val metadata = createMetadata(languageTag = "en")

        coEvery { metadataService.getParents(metadataId) } returns listOf(show, season)
        coEvery { collectionService.getLanguageVariants(showId) } returns emptyList()
        coEvery { collectionService.getLanguageVariants(seasonId) } returns emptyList()

        val result = searchCollections.toCollections(metadataService, metadata)

        // "show" becomes "shows", "season" excluded but "characters" always present
        assertTrue(result.containsKey("shows"))
        assertEquals(1, result["shows"]?.size)
        // "episode" and "season" types are excluded from the result
        assertFalse(result.containsKey("episode"))
        assertFalse(result.containsKey("season"))
    }

    @Test
    fun `returns characters key when no parents exist`() = runTest {
        val metadata = createMetadata(languageTag = "en")

        coEvery { metadataService.getParents(metadataId) } returns emptyList()

        val result = searchCollections.toCollections(metadataService, metadata)

        // The "characters" key is always present (from character -> characters rename)
        assertTrue(result.containsKey("characters"))
        assertEquals(emptyList(), result["characters"])
    }

    @Test
    fun `multiple parents with same type are grouped`() = runTest {
        val parentId1 = UUID.random()
        val parentId2 = UUID.random()
        val parent1 = createParentCollection(id = parentId1, name = "Collection 1")
        val parent2 = createParentCollection(id = parentId2, name = "Collection 2")
        val metadata = createMetadata(languageTag = "en")

        coEvery { metadataService.getParents(metadataId) } returns listOf(parent1, parent2)
        coEvery { collectionService.getLanguageVariants(parentId1) } returns emptyList()
        coEvery { collectionService.getLanguageVariants(parentId2) } returns emptyList()

        val result = searchCollections.toCollections(metadataService, metadata)

        assertEquals(2, result["collections"]?.size)
    }
}
