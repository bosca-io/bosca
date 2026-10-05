package bosca.content.collection.graphql

import bosca.content.collection.model.Collection
import bosca.content.collection.model.CollectionSupplementary
import bosca.content.collection.model.CollectionSupplementaryContent
import bosca.content.collection.model.CollectionType
import bosca.serialization.UUID
import bosca.storage.service.ObjectPath
import bosca.storage.service.ObjectStorageService
import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.di.annotation.InternalDI
import kotlinx.serialization.json.Json
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.time.OffsetDateTime
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(InternalDI::class)
class CollectionSupplementaryContentControllerCoverageTest {

    private val storage = mockk<ObjectStorageService>()

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        provides<Json>(singleton = true) { Json { ignoreUnknownKeys = true } }
    }

    private val controller = CollectionSupplementaryContentController(storage)

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
        contentType: String? = "application/json",
        contentLength: Long? = 42L
    ) = CollectionSupplementary(
        id = id,
        collectionId = collectionId,
        key = "test-key",
        name = "Test Supplementary",
        created = OffsetDateTime.now(),
        modified = OffsetDateTime.now(),
        contentType = contentType,
        contentLength = contentLength,
        sourceId = null,
        sourceIdentifier = null
    )

    private fun createContent(
        collection: Collection = createCollection(),
        supplementary: CollectionSupplementary = createSupplementary()
    ) = CollectionSupplementaryContent(collection = collection, supplementary = supplementary)

    @AfterTest
    fun tearDown() {
        clearAllMocks()
        ProviderRegistry.clear()
    }

    // ---- length ----

    @Test
    fun `length returns supplementary content length`() {
        val content = createContent(supplementary = createSupplementary(contentLength = 123L))

        assertEquals(123L, controller.length(content))
    }

    @Test
    fun `length returns null when content length is null`() {
        val content = createContent(supplementary = createSupplementary(contentLength = null))

        assertNull(controller.length(content))
    }

    // ---- type ----

    @Test
    fun `type returns supplementary content type`() {
        val content = createContent(supplementary = createSupplementary(contentType = "text/plain"))

        assertEquals("text/plain", controller.type(content))
    }

    @Test
    fun `type returns null when content type is null`() {
        val content = createContent(supplementary = createSupplementary(contentType = null))

        assertNull(controller.type(content))
    }

    // ---- urls ----

    @Test
    fun `urls wraps collection and supplementary`() {
        val collection = createCollection()
        val supplementary = createSupplementary()
        val content = createContent(collection = collection, supplementary = supplementary)

        val urls = controller.urls(content)

        assertEquals(collection, urls.collection)
        assertEquals(supplementary, urls.supplementary)
    }

    // ---- json ----

    @Test
    fun `json parses stored content into a json element`() = runTest {
        val collection = createCollection()
        val supplementary = createSupplementary()
        val content = createContent(collection = collection, supplementary = supplementary)
        val path = mockk<ObjectPath>()
        val payload = """{"hello":"world"}"""

        coEvery { storage.getPath(collection, supplementary.id) } returns path
        coEvery { storage.getString(path) } returns payload

        val result = controller.json(content)

        assertEquals(
            JsonObject(mapOf("hello" to JsonPrimitive("world"))),
            result.jsonObject
        )
    }

    // ---- text ----

    @Test
    fun `text returns decoded stored content`() = runTest {
        val collection = createCollection()
        val supplementary = createSupplementary()
        val content = createContent(collection = collection, supplementary = supplementary)
        val path = mockk<ObjectPath>()
        val payload = "plain text body"

        coEvery { storage.getPath(collection, supplementary.id) } returns path
        coEvery { storage.getString(path) } returns payload

        assertEquals(payload, controller.text(content))
    }
}
