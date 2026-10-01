package bosca.content.transformations

import bosca.content.collection.model.Collection
import bosca.content.collection.model.CollectionLanguageVariant
import bosca.content.collection.service.CollectionService
import bosca.serialization.UUID
import io.mockk.clearAllMocks
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

class DefaultCollectionToSearchAttributesCoverageTest {

    private val collectionService = mockk<CollectionService>()

    private val transformer = DefaultCollectionToSearchAttributes()

    @AfterTest
    fun tearDown() {
        clearAllMocks()
        unmockkAll()
    }

    private fun createCollection(attributes: kotlinx.serialization.json.JsonElement? = null) = Collection(
        id = UUID.random(),
        name = "Test Collection",
        languageTag = "en",
        workflowStateId = "published",
        attributes = attributes,
    )

    private fun createVariant(attributes: kotlinx.serialization.json.JsonElement? = null) = CollectionLanguageVariant(
        id = UUID.random(),
        languageTag = "es",
        name = "Variant Name",
        workflowStateId = "published",
        attributes = attributes,
    )

    @Test
    fun `null collection attributes and null variant yields empty object`() = runTest {
        val collection = createCollection(attributes = null)

        val result = transformer.toAttributes(collectionService, collection, null)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `JsonNull collection attributes are treated as empty`() = runTest {
        val collection = createCollection(attributes = JsonNull)

        val result = transformer.toAttributes(collectionService, collection, null)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `collection attributes object is copied through`() = runTest {
        val collection = createCollection(
            attributes = JsonObject(mapOf("title" to JsonPrimitive("Hello")))
        )

        val result = transformer.toAttributes(collectionService, collection, null)

        assertEquals(1, result.size)
        assertEquals("Hello", (result["title"] as JsonPrimitive).content)
    }

    @Test
    fun `variant with null attributes contributes nothing`() = runTest {
        val collection = createCollection(
            attributes = JsonObject(mapOf("title" to JsonPrimitive("Base")))
        )
        val variant = createVariant(attributes = null)

        val result = transformer.toAttributes(collectionService, collection, variant)

        assertEquals(1, result.size)
        assertEquals("Base", (result["title"] as JsonPrimitive).content)
    }

    @Test
    fun `variant with JsonNull attributes contributes nothing`() = runTest {
        val collection = createCollection(
            attributes = JsonObject(mapOf("title" to JsonPrimitive("Base")))
        )
        val variant = createVariant(attributes = JsonNull)

        val result = transformer.toAttributes(collectionService, collection, variant)

        assertEquals(1, result.size)
        assertEquals("Base", (result["title"] as JsonPrimitive).content)
    }

    @Test
    fun `variant attributes override and augment base attributes`() = runTest {
        val collection = createCollection(
            attributes = JsonObject(
                mapOf(
                    "title" to JsonPrimitive("Base Title"),
                    "shared" to JsonPrimitive("base"),
                )
            )
        )
        val variant = createVariant(
            attributes = JsonObject(
                mapOf(
                    "shared" to JsonPrimitive("variant"),
                    "extra" to JsonPrimitive("only-variant"),
                )
            )
        )

        val result = transformer.toAttributes(collectionService, collection, variant)

        assertEquals(3, result.size)
        assertEquals("Base Title", (result["title"] as JsonPrimitive).content)
        // variant wins on the shared key
        assertEquals("variant", (result["shared"] as JsonPrimitive).content)
        assertEquals("only-variant", (result["extra"] as JsonPrimitive).content)
    }

    @Test
    fun `episode key is stringified`() = runTest {
        val collection = createCollection(
            attributes = JsonObject(mapOf("episode" to JsonPrimitive(5)))
        )

        val result = transformer.toAttributes(collectionService, collection, null)

        // JsonPrimitive(5).toString() renders "5" (unquoted numeric), rewrapped as a string primitive.
        val episode = result["episode"] as JsonPrimitive
        assertTrue(episode.isString)
        assertEquals("5", episode.content)
    }

    @Test
    fun `episode string value keeps embedded quotes from toString`() = runTest {
        val collection = createCollection(
            attributes = JsonObject(mapOf("episode" to JsonPrimitive("pilot")))
        )

        val result = transformer.toAttributes(collectionService, collection, null)

        // A string primitive's toString() includes surrounding quotes; episode does NOT strip them.
        val episode = result["episode"] as JsonPrimitive
        assertTrue(episode.isString)
        assertEquals("\"pilot\"", episode.content)
    }

    @Test
    fun `season key is stringified with quotes stripped`() = runTest {
        val collection = createCollection(
            attributes = JsonObject(mapOf("season" to JsonPrimitive("2")))
        )

        val result = transformer.toAttributes(collectionService, collection, null)

        // season strips quotes from the toString() rendering of the string primitive.
        val season = result["season"] as JsonPrimitive
        assertTrue(season.isString)
        assertEquals("2", season.content)
    }

    @Test
    fun `numeric season is stringified with quote stripping noop`() = runTest {
        val collection = createCollection(
            attributes = JsonObject(mapOf("season" to JsonPrimitive(3)))
        )

        val result = transformer.toAttributes(collectionService, collection, null)

        val season = result["season"] as JsonPrimitive
        assertTrue(season.isString)
        assertEquals("3", season.content)
    }

    @Test
    fun `both episode and season transformed together`() = runTest {
        val collection = createCollection(
            attributes = JsonObject(
                mapOf(
                    "episode" to JsonPrimitive(1),
                    "season" to JsonPrimitive("4"),
                    "other" to JsonPrimitive("untouched"),
                )
            )
        )

        val result = transformer.toAttributes(collectionService, collection, null)

        assertEquals("1", (result["episode"] as JsonPrimitive).content)
        assertEquals("4", (result["season"] as JsonPrimitive).content)
        assertEquals("untouched", (result["other"] as JsonPrimitive).content)
    }

    @Test
    fun `no episode or season keys leaves data untouched`() = runTest {
        val collection = createCollection(
            attributes = JsonObject(mapOf("name" to JsonPrimitive("Show")))
        )

        val result = transformer.toAttributes(collectionService, collection, null)

        assertFalse(result.containsKey("episode"))
        assertFalse(result.containsKey("season"))
        assertEquals("Show", (result["name"] as JsonPrimitive).content)
    }

    @Test
    fun `episode supplied only via variant is stringified`() = runTest {
        val collection = createCollection(attributes = null)
        val variant = createVariant(
            attributes = JsonObject(mapOf("episode" to JsonPrimitive(7)))
        )

        val result = transformer.toAttributes(collectionService, collection, variant)

        val episode = result["episode"] as JsonPrimitive
        assertTrue(episode.isString)
        assertEquals("7", episode.content)
    }
}
