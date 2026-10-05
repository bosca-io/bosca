@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.content.collection.jobs

import bosca.content.metadata.model.Metadata
import bosca.content.metadata.service.MetadataService
import bosca.core.annotations.Internal
import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.serialization.UUID
import bosca.sharedqueue.jobs.InternalJobConstructor
import bosca.sharedqueue.jobs.JobQueue
import bosca.sharedqueue.jobs.asCoroutineContext
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Covers the [CollectionMetadataItemSyncExecutor] base class' [CollectionMetadataItemSyncExecutor.execute]
 * dispatch logic and the [getVariants] helper. A tiny test-only subclass records calls to the abstract
 * [syncVariantCollections] hook and surfaces [getVariants] so both can be asserted without dragging in the
 * transaction / event / enqueue machinery of the real added/removed executors.
 */
class CollectionMetadataItemSyncExecutorCoverageTest {

    private val metadataService = mockk<MetadataService>()
    private val json = Json { ignoreUnknownKeys = true }
    private val queue = mockk<JobQueue>()

    /** Records each [syncVariantCollections] invocation and exposes [getVariants] for direct assertion. */
    private inner class RecordingExecutor : CollectionMetadataItemSyncExecutor<CollectionMetadataItemAddedJob>(
        metadataService,
        CollectionMetadataItemAddedJob.serializer()
    ) {
        val calls = mutableListOf<Pair<UUID, Metadata>>()

        override suspend fun syncVariantCollections(collectionId: UUID, metadata: Metadata) {
            calls += collectionId to metadata
        }

        suspend fun variantsOf(metadata: Metadata): List<Metadata> = getVariants(metadata)
    }

    @BeforeTest
    fun setUp() {
        ProviderRegistry.clear()
        provides<Json>(singleton = true) { json }
    }

    @AfterTest
    fun tearDown() {
        unmockkAll()
        ProviderRegistry.clear()
    }

    @OptIn(Internal::class)
    private fun jobWith(job: CollectionMetadataItemAddedJob) = InternalJobConstructor(
        definition = json.encodeToJsonElement(CollectionMetadataItemAddedJob.serializer(), job),
        executor = CollectionMetadataItemAddedExecutor::class,
    )

    @Test
    fun `execute invokes syncVariantCollections when sync flag is enabled`() = runTest {
        val collectionId = UUID.random()
        val metadataId = UUID.random()

        val metadata = mockk<Metadata>()
        every { metadata.syncVariantCollections } returns true
        coEvery { metadataService.getById(metadataId) } returns metadata

        val executor = RecordingExecutor()
        val job = jobWith(CollectionMetadataItemAddedJob(id = collectionId, metadataId = metadataId))

        withContext(queue.asCoroutineContext(job)) {
            executor.execute()
        }

        assertEquals(1, executor.calls.size)
        assertEquals(collectionId, executor.calls.first().first)
        assertEquals(metadata, executor.calls.first().second)
        coVerify(exactly = 1) { metadataService.getById(metadataId) }
    }

    @Test
    fun `execute skips syncVariantCollections when sync flag is disabled`() = runTest {
        val collectionId = UUID.random()
        val metadataId = UUID.random()

        val metadata = mockk<Metadata>()
        every { metadata.syncVariantCollections } returns false
        coEvery { metadataService.getById(metadataId) } returns metadata

        val executor = RecordingExecutor()
        val job = jobWith(CollectionMetadataItemAddedJob(id = collectionId, metadataId = metadataId))

        withContext(queue.asCoroutineContext(job)) {
            executor.execute()
        }

        assertTrue(executor.calls.isEmpty())
    }

    @Test
    fun `execute fails when collection id is missing`() = runTest {
        val executor = RecordingExecutor()
        val job = jobWith(CollectionMetadataItemAddedJob(id = null, metadataId = UUID.random()))

        val error = assertFailsWith<IllegalStateException> {
            withContext(queue.asCoroutineContext(job)) {
                executor.execute()
            }
        }
        assertEquals("Missing collection id", error.message)
        assertTrue(executor.calls.isEmpty())
    }

    @Test
    fun `execute fails when metadata id is missing`() = runTest {
        val executor = RecordingExecutor()
        val job = jobWith(CollectionMetadataItemAddedJob(id = UUID.random(), metadataId = null))

        val error = assertFailsWith<IllegalStateException> {
            withContext(queue.asCoroutineContext(job)) {
                executor.execute()
            }
        }
        assertEquals("Missing metadata id", error.message)
    }

    @Test
    fun `execute fails when metadata is not found`() = runTest {
        val metadataId = UUID.random()
        coEvery { metadataService.getById(metadataId) } returns null

        val executor = RecordingExecutor()
        val job = jobWith(CollectionMetadataItemAddedJob(id = UUID.random(), metadataId = metadataId))

        val error = assertFailsWith<IllegalStateException> {
            withContext(queue.asCoroutineContext(job)) {
                executor.execute()
            }
        }
        assertEquals("Missing metadata", error.message)
        coVerify(exactly = 1) { metadataService.getById(metadataId) }
    }

    @Test
    fun `getVariants queries by parent id when metadata has a parent and filters self out`() = runTest {
        val metadataId = UUID.random()
        val parentId = UUID.random()
        val siblingId = UUID.random()

        val metadata = mockk<Metadata>()
        every { metadata.id } returns metadataId
        every { metadata.parentId } returns parentId

        // The parent's children include the metadata itself (must be filtered) and a sibling (must remain).
        val self = mockk<Metadata>()
        every { self.id } returns metadataId
        val sibling = mockk<Metadata>()
        every { sibling.id } returns siblingId
        coEvery { metadataService.getByParentId(parentId) } returns listOf(self, sibling)

        val executor = RecordingExecutor()
        val variants = executor.variantsOf(metadata)

        assertEquals(listOf(sibling), variants)
        coVerify(exactly = 1) { metadataService.getByParentId(parentId) }
    }

    @Test
    fun `getVariants queries by own id when metadata has no parent`() = runTest {
        val metadataId = UUID.random()
        val childId = UUID.random()

        val metadata = mockk<Metadata>()
        every { metadata.id } returns metadataId
        every { metadata.parentId } returns null

        val child = mockk<Metadata>()
        every { child.id } returns childId
        coEvery { metadataService.getByParentId(metadataId) } returns listOf(child)

        val executor = RecordingExecutor()
        val variants = executor.variantsOf(metadata)

        assertEquals(listOf(child), variants)
        coVerify(exactly = 1) { metadataService.getByParentId(metadataId) }
    }

    @Test
    fun `getVariants returns empty when no children exist`() = runTest {
        val metadataId = UUID.random()

        val metadata = mockk<Metadata>()
        every { metadata.id } returns metadataId
        every { metadata.parentId } returns null
        coEvery { metadataService.getByParentId(metadataId) } returns emptyList()

        val executor = RecordingExecutor()
        val variants = executor.variantsOf(metadata)

        assertTrue(variants.isEmpty())
    }
}
