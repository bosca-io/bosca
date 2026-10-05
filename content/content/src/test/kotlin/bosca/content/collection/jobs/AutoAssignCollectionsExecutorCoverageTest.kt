package bosca.content.collection.jobs

import bosca.content.collection.service.CollectionService
import bosca.configuration.service.ConfigurationService
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.service.MetadataService
import bosca.core.annotations.Internal
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.serialization.UUID
import bosca.sharedqueue.jobs.InternalJobConstructor
import bosca.sharedqueue.jobs.JobQueue
import bosca.sharedqueue.jobs.asCoroutineContext
import bosca.slug.model.Slug
import bosca.slug.service.SlugService
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * Complements [AutoAssignCollectionsExecutorTest], which covers the happy-path
 * assignment branches. This suite exercises the early-return, continue, and
 * non-matching branches that the sibling test leaves uncovered:
 *  - missing configuration (null autoConfig)
 *  - null metadata / null collection lookups
 *  - null attributes on the looked-up item
 *  - attributes that are not a [kotlinx.serialization.json.JsonObject]
 *  - attribute keys absent from the item attributes (continue)
 *  - element types that are neither array nor primitive (empty values)
 *  - attribute values that do not match the configured value
 *  - a matched slug that resolves to a null slug (no collection id)
 */
@OptIn(InternalDI::class)
class AutoAssignCollectionsExecutorCoverageTest {

    private val collectionService = mockk<CollectionService>()
    private val metadataService = mockk<MetadataService>()
    private val configurationService = mockk<ConfigurationService>()
    private val slugService = mockk<SlugService>()
    private val json = Json {
        ignoreUnknownKeys = true
        allowStructuredMapKeys = true
    }

    private val executor = AutoAssignCollectionsExecutor(collectionService, metadataService, configurationService, slugService, json)

    @BeforeTest
    fun setup() {
        provides<Json> { json }
    }

    @AfterTest
    fun tearDown() {
        ProviderRegistry.clear()
        clearAllMocks()
        unmockkAll()
    }

    private fun mockConfig(attributes: List<AttributeValue>, isMetadata: Boolean) {
        val configId = UUID.random()
        coEvery { configurationService.getByKey(if (isMetadata) "auto.assign.collection.metadata" else "auto.assign.collection.collection") } returns mockk {
            every { id } returns configId
        }
        coEvery { configurationService.getValue(configId) } returns json.encodeToJsonElement(
            AutoAssignCollectionsConfiguration(attributes = attributes)
        )
    }

    private fun mockSlug(slug: String, collectionId: UUID) {
        coEvery { slugService.get(slug) } returns Slug(slug = slug, collectionId = collectionId)
    }

    @OptIn(Internal::class)
    @Test
    fun `returns early when metadata configuration is missing`() = runTest {
        val metadataId = UUID.random()
        val jobQueue = mockk<JobQueue>()

        // getByKey null -> getValueAs returns null -> execute returns before any lookup
        coEvery { configurationService.getByKey("auto.assign.collection.metadata") } returns null

        val config = AutoAssignCollectionsJob(id = metadataId, version = 1)
        val job = InternalJobConstructor(
            definition = json.encodeToJsonElement(config),
            executor = AutoAssignCollectionsExecutor::class
        )

        withContext(jobQueue.asCoroutineContext(job)) {
            executor.execute()
        }

        coVerify(exactly = 0) { metadataService.getById(any<UUID>(), any<Int>()) }
        coVerify(exactly = 0) { collectionService.addMetadataItem(any(), any(), any()) }
    }

    @OptIn(Internal::class)
    @Test
    fun `returns early when collection configuration value is missing`() = runTest {
        val collectionId = UUID.random()
        val jobQueue = mockk<JobQueue>()

        // getByKey returns a configuration, but getValue is null -> getValueAs returns null
        val configId = UUID.random()
        coEvery { configurationService.getByKey("auto.assign.collection.collection") } returns mockk {
            every { id } returns configId
        }
        coEvery { configurationService.getValue(configId) } returns null

        val config = AutoAssignCollectionsJob(id = collectionId)
        val job = InternalJobConstructor(
            definition = json.encodeToJsonElement(config),
            executor = AutoAssignCollectionsExecutor::class
        )

        withContext(jobQueue.asCoroutineContext(job)) {
            executor.execute()
        }

        coVerify(exactly = 0) { collectionService.getById(any()) }
        coVerify(exactly = 0) { collectionService.addCollectionItem(any(), any(), any()) }
    }

    @OptIn(Internal::class)
    @Test
    fun `returns early when metadata does not exist`() = runTest {
        val metadataId = UUID.random()
        val jobQueue = mockk<JobQueue>()

        mockConfig(listOf(AttributeValue("type", "blog", "some-slug")), isMetadata = true)
        coEvery { metadataService.getById(metadataId, 1) } returns null

        val config = AutoAssignCollectionsJob(id = metadataId, version = 1)
        val job = InternalJobConstructor(
            definition = json.encodeToJsonElement(config),
            executor = AutoAssignCollectionsExecutor::class
        )

        withContext(jobQueue.asCoroutineContext(job)) {
            executor.execute()
        }

        coVerify(exactly = 0) { slugService.get(any()) }
        coVerify(exactly = 0) { collectionService.addMetadataItem(any(), any(), any()) }
    }

    @OptIn(Internal::class)
    @Test
    fun `returns early when collection does not exist`() = runTest {
        val collectionId = UUID.random()
        val jobQueue = mockk<JobQueue>()

        mockConfig(listOf(AttributeValue("type", "folder", "some-slug")), isMetadata = false)
        coEvery { collectionService.getById(collectionId) } returns null

        val config = AutoAssignCollectionsJob(id = collectionId)
        val job = InternalJobConstructor(
            definition = json.encodeToJsonElement(config),
            executor = AutoAssignCollectionsExecutor::class
        )

        withContext(jobQueue.asCoroutineContext(job)) {
            executor.execute()
        }

        coVerify(exactly = 0) { slugService.get(any()) }
        coVerify(exactly = 0) { collectionService.addCollectionItem(any(), any(), any()) }
    }

    @OptIn(Internal::class)
    @Test
    fun `returns early when metadata attributes are null`() = runTest {
        val metadataId = UUID.random()
        val jobQueue = mockk<JobQueue>()

        mockConfig(listOf(AttributeValue("type", "blog", "some-slug")), isMetadata = true)

        val metadata = mockk<Metadata>()
        every { metadata.attributes } returns null
        coEvery { metadataService.getById(metadataId, 1) } returns metadata

        val config = AutoAssignCollectionsJob(id = metadataId, version = 1)
        val job = InternalJobConstructor(
            definition = json.encodeToJsonElement(config),
            executor = AutoAssignCollectionsExecutor::class
        )

        withContext(jobQueue.asCoroutineContext(job)) {
            executor.execute()
        }

        coVerify(exactly = 0) { slugService.get(any()) }
        coVerify(exactly = 0) { collectionService.addMetadataItem(any(), any(), any()) }
    }

    @OptIn(Internal::class)
    @Test
    fun `returns early when attributes are not a json object`() = runTest {
        val metadataId = UUID.random()
        val jobQueue = mockk<JobQueue>()

        mockConfig(listOf(AttributeValue("type", "blog", "some-slug")), isMetadata = true)

        val metadata = mockk<Metadata>()
        // A JsonArray is not a JsonObject -> the `attributes !is JsonObject` guard returns
        every { metadata.attributes } returns json.parseToJsonElement("""["blog","news"]""")
        coEvery { metadataService.getById(metadataId, 1) } returns metadata

        val config = AutoAssignCollectionsJob(id = metadataId, version = 1)
        val job = InternalJobConstructor(
            definition = json.encodeToJsonElement(config),
            executor = AutoAssignCollectionsExecutor::class
        )

        withContext(jobQueue.asCoroutineContext(job)) {
            executor.execute()
        }

        coVerify(exactly = 0) { slugService.get(any()) }
        coVerify(exactly = 0) { collectionService.addMetadataItem(any(), any(), any()) }
    }

    @OptIn(Internal::class)
    @Test
    fun `skips attribute keys that are absent from the item attributes`() = runTest {
        val metadataId = UUID.random()
        val jobQueue = mockk<JobQueue>()

        // Configured key "type" is not present in the item attributes -> `?: continue`
        mockConfig(listOf(AttributeValue("type", "blog", "some-slug")), isMetadata = true)

        val metadata = mockk<Metadata>()
        every { metadata.attributes } returns json.parseToJsonElement("""{"other": "value"}""")
        coEvery { metadataService.getById(metadataId, 1) } returns metadata

        val config = AutoAssignCollectionsJob(id = metadataId, version = 1)
        val job = InternalJobConstructor(
            definition = json.encodeToJsonElement(config),
            executor = AutoAssignCollectionsExecutor::class
        )

        withContext(jobQueue.asCoroutineContext(job)) {
            executor.execute()
        }

        coVerify(exactly = 0) { slugService.get(any()) }
        coVerify(exactly = 0) { collectionService.addMetadataItem(any(), any(), any()) }
    }

    @OptIn(Internal::class)
    @Test
    fun `yields no values when element is neither array nor primitive`() = runTest {
        val metadataId = UUID.random()
        val jobQueue = mockk<JobQueue>()

        // The "type" attribute is a nested object -> `else -> emptyList()` branch,
        // so `values.contains(value)` is false and nothing is assigned.
        mockConfig(listOf(AttributeValue("type", "blog", "some-slug")), isMetadata = true)

        val metadata = mockk<Metadata>()
        every { metadata.attributes } returns json.parseToJsonElement("""{"type": {"nested": "blog"}}""")
        coEvery { metadataService.getById(metadataId, 1) } returns metadata

        val config = AutoAssignCollectionsJob(id = metadataId, version = 1)
        val job = InternalJobConstructor(
            definition = json.encodeToJsonElement(config),
            executor = AutoAssignCollectionsExecutor::class
        )

        withContext(jobQueue.asCoroutineContext(job)) {
            executor.execute()
        }

        coVerify(exactly = 0) { slugService.get(any()) }
        coVerify(exactly = 0) { collectionService.addMetadataItem(any(), any(), any()) }
    }

    @OptIn(Internal::class)
    @Test
    fun `does not assign when attribute value does not match`() = runTest {
        val metadataId = UUID.random()
        val jobQueue = mockk<JobQueue>()

        // Configured value "blog" but the item value is "news" -> `values.contains(value)` is false
        mockConfig(listOf(AttributeValue("type", "blog", "some-slug")), isMetadata = true)

        val metadata = mockk<Metadata>()
        every { metadata.attributes } returns json.parseToJsonElement("""{"type": "news"}""")
        coEvery { metadataService.getById(metadataId, 1) } returns metadata

        val config = AutoAssignCollectionsJob(id = metadataId, version = 1)
        val job = InternalJobConstructor(
            definition = json.encodeToJsonElement(config),
            executor = AutoAssignCollectionsExecutor::class
        )

        withContext(jobQueue.asCoroutineContext(job)) {
            executor.execute()
        }

        coVerify(exactly = 0) { slugService.get(any()) }
        coVerify(exactly = 0) { collectionService.addMetadataItem(any(), any(), any()) }
    }

    @OptIn(Internal::class)
    @Test
    fun `does not assign when matched slug resolves to null`() = runTest {
        val metadataId = UUID.random()
        val slug = "missing-slug"
        val jobQueue = mockk<JobQueue>()

        // Value matches, but slugService.get returns null -> `?.collectionId?.let` is skipped
        mockConfig(listOf(AttributeValue("type", "blog", slug)), isMetadata = true)
        coEvery { slugService.get(slug) } returns null

        val metadata = mockk<Metadata>()
        every { metadata.attributes } returns json.parseToJsonElement("""{"type": "blog"}""")
        coEvery { metadataService.getById(metadataId, 1) } returns metadata

        val config = AutoAssignCollectionsJob(id = metadataId, version = 1)
        val job = InternalJobConstructor(
            definition = json.encodeToJsonElement(config),
            executor = AutoAssignCollectionsExecutor::class
        )

        withContext(jobQueue.asCoroutineContext(job)) {
            executor.execute()
        }

        val captured = slug
        coVerify(exactly = 1) { slugService.get(captured) }
        coVerify(exactly = 0) { collectionService.addMetadataItem(any(), any(), any()) }
    }

    @OptIn(Internal::class)
    @Test
    fun `does not assign when matched slug has null collection id`() = runTest {
        val metadataId = UUID.random()
        val slug = "no-collection-slug"
        val jobQueue = mockk<JobQueue>()

        // Value matches and slug resolves, but its collectionId is null -> nothing added
        mockConfig(listOf(AttributeValue("type", "blog", slug)), isMetadata = true)
        coEvery { slugService.get(slug) } returns Slug(slug = slug, collectionId = null)

        val metadata = mockk<Metadata>()
        every { metadata.attributes } returns json.parseToJsonElement("""{"type": "blog"}""")
        coEvery { metadataService.getById(metadataId, 1) } returns metadata

        val config = AutoAssignCollectionsJob(id = metadataId, version = 1)
        val job = InternalJobConstructor(
            definition = json.encodeToJsonElement(config),
            executor = AutoAssignCollectionsExecutor::class
        )

        withContext(jobQueue.asCoroutineContext(job)) {
            executor.execute()
        }

        coVerify(exactly = 0) { collectionService.addMetadataItem(any(), any(), any()) }
    }
}
