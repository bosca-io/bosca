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

@OptIn(InternalDI::class)
class AutoAssignCollectionsExecutorTest {

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
    fun `adds metadata to collection by attribute value`() = runTest {
        val collectionId = UUID.random()
        val slug = "test-collection"
        val metadataId = UUID.random()
        val jobQueue = mockk<JobQueue>()

        val metadata = mockk<Metadata>()
        every { metadata.attributes } returns json.parseToJsonElement("""{"type": "blog"}""")
        coEvery { metadataService.getById(metadataId, 1) } returns metadata

        mockSlug(slug, collectionId)
        mockConfig(listOf(AttributeValue("type", "blog", slug)), isMetadata = true)

        val config = AutoAssignCollectionsJob(
            id = metadataId,
            version = 1
        )

        val job = InternalJobConstructor(
            definition = json.encodeToJsonElement(config),
            executor = AutoAssignCollectionsExecutor::class
        )

        coEvery { collectionService.addMetadataItem(collectionId, metadataId, null) } returns Unit

        withContext(jobQueue.asCoroutineContext(job)) {
            executor.execute()
        }

        coVerify {
            collectionService.addMetadataItem(collectionId, metadataId, null)
        }
    }

    @OptIn(Internal::class)
    @Test
    fun `adds metadata to multiple collections by different attributes`() = runTest {
        val collectionId1 = UUID.random()
        val collectionId2 = UUID.random()
        val slug1 = "slug-1"
        val slug2 = "slug-2"
        val metadataId = UUID.random()
        val jobQueue = mockk<JobQueue>()

        val metadata = mockk<Metadata>()
        every { metadata.attributes } returns json.parseToJsonElement("""{"type": "blog", "category": "tech"}""")
        coEvery { metadataService.getById(metadataId, 1) } returns metadata

        mockSlug(slug1, collectionId1)
        mockSlug(slug2, collectionId2)
        mockConfig(
            listOf(
                AttributeValue("type", "blog", slug1),
                AttributeValue("category", "tech", slug2)
            ),
            isMetadata = true
        )

        val config = AutoAssignCollectionsJob(
            id = metadataId,
            version = 1
        )

        val job = InternalJobConstructor(
            definition = json.encodeToJsonElement(config),
            executor = AutoAssignCollectionsExecutor::class
        )

        coEvery { collectionService.addMetadataItem(any(), any(), any()) } returns Unit

        withContext(jobQueue.asCoroutineContext(job)) {
            executor.execute()
        }

        coVerify {
            collectionService.addMetadataItem(collectionId1, metadataId, null)
            collectionService.addMetadataItem(collectionId2, metadataId, null)
        }
    }

    @OptIn(Internal::class)
    @Test
    fun `adds metadata to collection when attribute is in an array`() = runTest {
        val collectionId = UUID.random()
        val slug = "tag-collection"
        val metadataId = UUID.random()
        val jobQueue = mockk<JobQueue>()

        val metadata = mockk<Metadata>()
        every { metadata.attributes } returns json.parseToJsonElement("""{"tags": ["news", "hot"]}""")
        coEvery { metadataService.getById(metadataId, 1) } returns metadata

        mockSlug(slug, collectionId)
        mockConfig(listOf(AttributeValue("tags", "hot", slug)), isMetadata = true)

        val config = AutoAssignCollectionsJob(
            id = metadataId,
            version = 1
        )

        val job = InternalJobConstructor(
            definition = json.encodeToJsonElement(config),
            executor = AutoAssignCollectionsExecutor::class
        )

        coEvery { collectionService.addMetadataItem(collectionId, metadataId, null) } returns Unit

        withContext(jobQueue.asCoroutineContext(job)) {
            executor.execute()
        }

        coVerify {
            collectionService.addMetadataItem(collectionId, metadataId, null)
        }
    }

    @OptIn(Internal::class)
    @Test
    fun `adds collection to collection by attribute value`() = runTest {
        val collectionId = UUID.random()
        val slug = "parent-collection"
        val childCollectionId = UUID.random()
        val jobQueue = mockk<JobQueue>()

        val childCollection = mockk<bosca.content.collection.model.Collection>()
        every { childCollection.attributes } returns json.parseToJsonElement("""{"type": "folder"}""")
        coEvery { collectionService.getById(childCollectionId) } returns childCollection

        mockSlug(slug, collectionId)
        mockConfig(listOf(AttributeValue("type", "folder", slug)), isMetadata = false)

        val config = AutoAssignCollectionsJob(
            id = childCollectionId
        )

        val job = InternalJobConstructor(
            definition = json.encodeToJsonElement(config),
            executor = AutoAssignCollectionsExecutor::class
        )

        coEvery { collectionService.addCollectionItem(collectionId, childCollectionId, null) } returns Unit

        withContext(jobQueue.asCoroutineContext(job)) {
            executor.execute()
        }

        coVerify {
            collectionService.addCollectionItem(collectionId, childCollectionId, null)
        }
    }
}
