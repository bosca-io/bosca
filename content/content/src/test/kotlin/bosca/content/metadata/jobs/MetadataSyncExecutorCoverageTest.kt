package bosca.content.metadata.jobs

import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataRelationship
import bosca.content.metadata.model.MetadataType
import bosca.content.metadata.service.MetadataService
import bosca.content.model.ContentRelationship
import bosca.core.annotations.Internal
import bosca.db.ConnectionManager
import bosca.db.asCoroutineContext
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.queue.annotations.IJobDefinition
import bosca.serialization.UUID
import bosca.serialization.UUIDSerializer
import bosca.sharedqueue.jobs.InternalJobConstructor
import bosca.sharedqueue.jobs.JobQueue
import bosca.sharedqueue.jobs.asCoroutineContext
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import kotlinx.serialization.modules.polymorphic
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Direct coverage for the abstract [MetadataSyncExecutor] base class, exercised through a minimal
 * concrete subclass ([TestSyncExecutor]) and its own concrete [MetadataSyncJob] definition
 * ([TestSyncJob]).
 *
 * The concrete relationship executors ([MetadataRelationshipAddedExecutor],
 * [MetadataRelationshipMergedExecutor], [MetadataRelationshipRemovedExecutor]) have their own
 * coverage tests for `syncVariantRelationship`; this test isolates and drives every branch of the
 * base-class machinery — [MetadataSyncExecutor.execute]'s missing-id and missing-metadata error
 * arms, the `syncVariantCollections` gate, the nullable-relationship `let`, and both sides of the
 * parent-vs-self resolution in [MetadataSyncExecutor.getVariants].
 */
@OptIn(InternalDI::class)
class MetadataSyncExecutorCoverageTest {

    /** Concrete [MetadataSyncJob] used only by this test to drive the abstract base directly. */
    @Serializable
    private data class TestSyncJob(
        override val id: UUID? = null,
        override val relationship: ContentRelationship? = null,
    ) : IJobDefinition, MetadataSyncJob

    /**
     * Concrete [MetadataSyncExecutor] that records the arguments handed to
     * [syncVariantRelationship] and exposes the protected [getVariants] helper so both can be
     * asserted from tests.
     */
    private class TestSyncExecutor(
        metadataService: MetadataService,
    ) : MetadataSyncExecutor<TestSyncJob>(metadataService, TestSyncJob.serializer()) {

        var syncCalls: Int = 0
            private set
        var lastMetadata: Metadata? = null
            private set
        var lastRelationship: ContentRelationship? = null
            private set

        override suspend fun syncVariantRelationship(metadata: Metadata, relationship: ContentRelationship) {
            syncCalls++
            lastMetadata = metadata
            lastRelationship = relationship
        }

        suspend fun variantsOf(metadata: Metadata): List<Metadata> = getVariants(metadata)
    }

    private val metadataService = mockk<MetadataService>()
    private val jobQueue = mockk<JobQueue>()
    private val connectionManager = mockk<ConnectionManager>(relaxed = true)

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = false
        explicitNulls = false
        serializersModule = SerializersModule {
            contextual(UUIDSerializer())
            polymorphic(ContentRelationship::class, MetadataRelationship::class, MetadataRelationship.serializer())
        }
    }

    private val executor = TestSyncExecutor(metadataService)

    @BeforeTest
    fun setup() {
        provides<Json> { json }
        provides<JobQueue>(name = "contentQueue") { jobQueue }
        coEvery { jobQueue.enqueue(any()) } returns UUID.random()
    }

    @AfterTest
    fun tearDown() {
        ProviderRegistry.clear()
        clearAllMocks()
        unmockkAll()
    }

    private fun metadata(
        id: UUID = UUID.random(),
        parentId: UUID? = null,
        languageTag: String = "en",
        syncVariantCollections: Boolean = true,
        syncVariantRelationships: Boolean = true,
    ): Metadata = Metadata(
        id = id,
        parentId = parentId,
        name = "test",
        type = MetadataType.STANDARD,
        contentType = "text/plain",
        contentLength = null,
        languageTag = languageTag,
        workflowStateId = "pending",
        syncVariantCollections = syncVariantCollections,
        syncVariantRelationships = syncVariantRelationships,
    )

    private fun relationship(id1: UUID, id2: UUID): MetadataRelationship =
        MetadataRelationship(metadataId1 = id1, metadataId2 = id2, relationship = "related")

    @OptIn(Internal::class)
    private suspend fun run(job: TestSyncJob) {
        val jobObject = InternalJobConstructor(
            definition = json.encodeToJsonElement(job),
            executor = TestSyncExecutor::class,
        )
        withContext(jobQueue.asCoroutineContext(jobObject) + connectionManager.asCoroutineContext()) {
            executor.execute()
        }
    }

    @Test
    fun `execute errors when job id is missing`() = runTest {
        val error = assertFailsWith<IllegalStateException> {
            run(TestSyncJob(id = null, relationship = relationship(UUID.random(), UUID.random())))
        }
        assertEquals("Missing metadata id", error.message)
        assertEquals(0, executor.syncCalls)
    }

    @Test
    fun `execute errors when metadata is not found`() = runTest {
        val id = UUID.random()
        coEvery { metadataService.getById(id) } returns null

        val error = assertFailsWith<IllegalStateException> {
            run(TestSyncJob(id = id, relationship = relationship(id, UUID.random())))
        }
        assertEquals("Missing metadata", error.message)
        assertEquals(0, executor.syncCalls)
    }

    @Test
    fun `execute skips sync when metadata does not sync variant collections`() = runTest {
        val id = UUID.random()
        val target = metadata(id = id, syncVariantCollections = false)
        coEvery { metadataService.getById(id) } returns target

        run(TestSyncJob(id = id, relationship = relationship(id, UUID.random())))

        assertEquals(0, executor.syncCalls)
        coVerify(exactly = 0) { metadataService.getByParentId(any()) }
    }

    @Test
    fun `execute skips sync when relationship is null despite syncing collections`() = runTest {
        val id = UUID.random()
        val target = metadata(id = id, syncVariantCollections = true)
        coEvery { metadataService.getById(id) } returns target

        run(TestSyncJob(id = id, relationship = null))

        assertEquals(0, executor.syncCalls)
    }

    @Test
    fun `execute invokes syncVariantRelationship when collections sync and relationship present`() = runTest {
        val id = UUID.random()
        val relatedId = UUID.random()
        val target = metadata(id = id, syncVariantCollections = true)
        val rel = relationship(id, relatedId)
        coEvery { metadataService.getById(id) } returns target

        run(TestSyncJob(id = id, relationship = rel))

        assertEquals(1, executor.syncCalls)
        assertEquals(id, executor.lastMetadata?.id)
        assertEquals(relatedId, executor.lastRelationship?.id2)
    }

    @Test
    fun `getVariants resolves via parent id and filters out self when metadata has a parent`() = runTest {
        val parentContentId = UUID.random()
        val childId = UUID.random()
        val siblingId = UUID.random()
        val child = metadata(id = childId, parentId = parentContentId)
        val sibling = metadata(id = siblingId, parentId = parentContentId, languageTag = "fr")

        // getByParentId returns the child itself plus a sibling; the child must be filtered out.
        coEvery { metadataService.getByParentId(parentContentId) } returns listOf(child, sibling)

        val variants = executor.variantsOf(child)

        coVerify(exactly = 1) { metadataService.getByParentId(parentContentId) }
        coVerify(exactly = 0) { metadataService.getByParentId(childId) }
        assertEquals(listOf(siblingId), variants.map { it.id })
        assertTrue(variants.none { it.id == childId })
    }

    @Test
    fun `getVariants resolves via self id and filters out self when metadata has no parent`() = runTest {
        val rootId = UUID.random()
        val variantId = UUID.random()
        val root = metadata(id = rootId, parentId = null)
        val variant = metadata(id = variantId, parentId = rootId, languageTag = "es")

        coEvery { metadataService.getByParentId(rootId) } returns listOf(root, variant)

        val variants = executor.variantsOf(root)

        coVerify(exactly = 1) { metadataService.getByParentId(rootId) }
        assertEquals(listOf(variantId), variants.map { it.id })
        assertTrue(variants.none { it.id == rootId })
    }

    @Test
    fun `getVariants returns empty when only self is returned`() = runTest {
        val rootId = UUID.random()
        val root = metadata(id = rootId, parentId = null)

        coEvery { metadataService.getByParentId(rootId) } returns listOf(root)

        val variants = executor.variantsOf(root)

        assertTrue(variants.isEmpty())
    }

    @Test
    fun `TestSyncJob round-trips through json preserving id and relationship`() = runTest {
        val id = UUID.random()
        val relatedId = UUID.random()
        val job = TestSyncJob(id = id, relationship = relationship(id, relatedId))

        val encoded = json.encodeToJsonElement(job)
        val decoded = json.decodeFromJsonElement(TestSyncJob.serializer(), encoded)

        assertEquals(id, decoded.id)
        assertEquals(relatedId, decoded.relationship?.id2)

        val empty = TestSyncJob()
        assertNull(empty.id)
        assertNull(empty.relationship)
    }
}
