package bosca.content.metadata.jobs

import bosca.cache.CacheManager
import bosca.cache.RequestCache
import bosca.cache.RequestCacheSerializer
import bosca.cache.RequestCacheSerializerImpl
import bosca.cache.asCoroutineContext
import bosca.category.service.CategoryService
import bosca.content.collection.repository.CollectionCategoryRepositoryImpl
import bosca.content.collection.repository.CollectionCollaborationRepositoryImpl
import bosca.content.collection.repository.CollectionFindRepository
import bosca.content.collection.repository.CollectionItemRepositoryImpl
import bosca.content.collection.repository.CollectionJobHistoryRepositoryImpl
import bosca.content.collection.repository.CollectionLanguageVariantRepositoryImpl
import bosca.content.collection.repository.CollectionMetadataRelationshipRepositoryImpl
import bosca.content.collection.repository.CollectionPermissionRepositoryImpl
import bosca.content.collection.repository.CollectionRepositoryImpl
import bosca.content.collection.repository.CollectionSupplementaryRepositoryImpl
import bosca.content.collection.repository.CollectionTraitRepositoryImpl
import bosca.content.collection.repository.CollectionWorkflowPlanRepositoryImpl
import bosca.content.collection.service.CollectionJobHistoryServiceImpl
import bosca.content.collection.service.CollectionService
import bosca.content.collection.service.CollectionServiceImpl
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataRelationship
import bosca.content.metadata.model.MetadataType
import bosca.content.metadata.repository.CollectionTemplateRepositoryImpl
import bosca.content.metadata.repository.CollectionTemplateAttributeRepositoryImpl
import bosca.content.metadata.repository.MetadataRepositoryImpl
import bosca.content.metadata.repository.MetadataCategoryRepositoryImpl
import bosca.content.metadata.repository.MetadataFindRepository
import bosca.content.metadata.repository.MetadataJobHistoryRepositoryImpl
import bosca.content.metadata.repository.MetadataPermissionRepositoryImpl
import bosca.content.metadata.repository.MetadataProfileRepositoryImpl
import bosca.content.metadata.repository.MetadataRelationshipRepositoryImpl
import bosca.content.metadata.repository.MetadataSupplementaryRepositoryImpl
import bosca.content.metadata.repository.MetadataTraitRepositoryImpl
import bosca.content.metadata.repository.MetadataWorkflowPlanRepositoryImpl
import bosca.content.metadata.service.BibleService
import bosca.content.metadata.service.CollectionTemplateService
import bosca.content.metadata.service.DataService
import bosca.content.metadata.service.DataTemplateService
import bosca.content.metadata.service.DocumentService
import bosca.content.metadata.service.DocumentTemplateService
import bosca.content.metadata.service.GuideService
import bosca.content.metadata.service.GuideTemplateService
import bosca.content.metadata.service.MetadataJobHistoryServiceImpl
import bosca.content.metadata.service.MetadataService
import bosca.content.metadata.service.MetadataServiceImpl
import bosca.content.security.CollectionPermissionEvaluator
import bosca.content.security.MetadataPermissionEvaluator
import bosca.content.timeevent.service.TimeEventService
import bosca.content.state.repository.StateRepositoryImpl
import bosca.content.state.service.StateServiceImpl
import bosca.content.video.service.VideoService
import bosca.content.collection.jobs.AutoAssignCollectionsExecutorConfigurationEnqueuer
import bosca.content.collection.jobs.MetadataParentItemCacheInvalidationExecutorConfigurationEnqueuer
import bosca.content.metadata.jobs.MetadataIndexExecutorConfigurationEnqueuer
import bosca.content.metadata.jobs.MetadataProcessContentExecutorConfigurationEnqueuer
import bosca.content.transition.jobs.CollectionTransitionExecutor
import bosca.content.transition.jobs.CollectionTransitionExecutorConfigurationEnqueuer
import bosca.content.transition.jobs.MetadataTransitionExecutor
import bosca.content.transition.jobs.MetadataTransitionExecutorConfigurationEnqueuer
import bosca.content.transition.jobs.UpdateContentJobHistoryOnCompleteListener
import bosca.content.transition.model.BeginTransitionInput
import bosca.content.transition.repository.TransitionRepositoryImpl
import bosca.content.transition.service.TransitionServiceImpl
import bosca.content.transition.service.Transitioner
import bosca.db.ConnectionConfig
import bosca.db.ConnectionFactoryImpl
import bosca.db.ConnectionManager
import bosca.db.ConnectionPool
import bosca.db.asCoroutineContext
import bosca.db.migrations.CoreMigration
import bosca.db.migrations.FlywayMigration
import bosca.di.ObjectProvider
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.events.DisabledEventManagerFilter
import bosca.events.EnabledEventManagerFilter
import bosca.events.EventManager
import bosca.events.asCoroutineContext
import bosca.lock.DistributedLockFactory
import bosca.pubsub.PubSubService
import bosca.security.model.Group
import bosca.security.model.GroupType
import bosca.security.model.Principal
import bosca.security.service.GroupEvaluator
import bosca.security.service.ImpersonatedAuthenticationContext
import bosca.security.service.SecurityService
import bosca.serialization.OffsetDateTimeSerializer
import bosca.serialization.UUID
import bosca.serialization.UUIDSerializer
import bosca.sharedqueue.jobs.JobConfigurationEnqueuer
import bosca.sharedqueue.jobs.JobQueue
import bosca.sharedqueue.jobs.JobQueueFactory
import bosca.sharedqueue.jobs.JobRunner
import bosca.sharedqueue.jobs.configuration.RegisterJobsConfiguration
import bosca.slug.service.SlugService
import bosca.storage.service.ObjectStorageService
import bosca.trait.service.TraitService
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import bosca.test.resources.SharedPostgreSQLContainer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.toJavaUuid

/**
 * Abstract end-to-end test for [MetadataSyncStateExecutor] that verifies relationship state
 * synchronization when metadata is published with [Metadata.syncVariantRelationships] enabled.
 *
 * Subclasses provide the job queue backend (NATS or Redis) while this class defines shared
 * infrastructure setup and test scenarios exercising the full publish-and-sync flow against
 * a real PostgreSQL database.
 */
@OptIn(InternalDI::class)
abstract class MetadataSyncStateEndToEndTest {

    private lateinit var postgresContainer: SharedPostgreSQLContainer
    protected lateinit var connectionPool: ConnectionPool
    private lateinit var cacheManager: CacheManager
    private lateinit var serializer: RequestCacheSerializer
    private lateinit var jobRunner: JobRunner
    private lateinit var jobQueue: JobQueue

    protected val testJson = Json {
        ignoreUnknownKeys = true
        serializersModule = SerializersModule {
            contextual(OffsetDateTimeSerializer())
            contextual(UUIDSerializer())
        }
    }

    private lateinit var collectionService: CollectionServiceImpl
    private lateinit var metadataService: MetadataServiceImpl
    private lateinit var metadataRepository: MetadataRepositoryImpl
    private lateinit var relationshipRepository: MetadataRelationshipRepositoryImpl
    private lateinit var transitioner: Transitioner

    private val securityService = mockk<SecurityService>()
    private val slugService = mockk<SlugService>(relaxed = true)
    private val pubSubService = mockk<PubSubService>(relaxed = true)
    private val timeEventService = mockk<TimeEventService>(relaxed = true)

    private val saGroups = listOf(
        Group(id = UUID.random(), name = "sa", description = "", type = GroupType.SYSTEM),
        Group(id = UUID.random(), name = "administrators", description = "", type = GroupType.SYSTEM),
    )
    private val saPrincipal = Principal(id = UUID.random())

    /**
     * Start backend-specific containers (NATS or Redis).
     */
    abstract fun startContainers()

    /**
     * Stop backend-specific containers.
     */
    abstract fun stopContainers()

    /**
     * Create the [JobQueueFactory] for the backend under test.
     */
    abstract fun createJobQueueFactory(distributedLockFactory: DistributedLockFactory): JobQueueFactory

    /**
     * Create the [DistributedLockFactory] for the backend under test.
     */
    abstract fun createDistributedLockFactory(): DistributedLockFactory

    /**
     * Create the [CacheManager] for the backend under test.
     */
    abstract fun createCacheManager(): CacheManager

    @BeforeTest
    fun setup() = runBlocking {
        unmockkAll()

        // Start containers
        postgresContainer = SharedPostgreSQLContainer("pgvector/pgvector:pg17").apply {
            withUsername("test")
            withPassword("test")
            withDatabaseName("test")
            withReuse(true)
        }
        postgresContainer.start()

        startContainers()

        // Database
        val factory = ConnectionFactoryImpl(
            ConnectionConfig(
                url = postgresContainer.jdbcUrl,
                user = postgresContainer.username,
                password = postgresContainer.password,
            ),
            key = "test"
        )
        connectionPool = ConnectionPool(factory)
        FlywayMigration(connectionPool).migrate(listOf(CoreMigration()))

        // Backend-specific infrastructure
        val distributedLockFactory = createDistributedLockFactory()
        cacheManager = createCacheManager()
        serializer = RequestCacheSerializerImpl(testJson)

        // Job queue + runner
        val jobQueueFactory = createJobQueueFactory(distributedLockFactory)
        jobQueue = jobQueueFactory.create("content")
        jobRunner = JobRunner(jobQueue, 10, distributedLockFactory)

        // Configure SecurityService mock
        coEvery { securityService.getPrincipalGroups(any<UUID>()) } returns saGroups
        coEvery { securityService.getPrincipalByIdentifier("sa") } returns saPrincipal
        val groupEvaluator = GroupEvaluator(securityService)

        // DI registrations
        ProviderRegistry.clear()
        provides<CacheManager>(singleton = true) { cacheManager }
        provides<RequestCacheSerializer>(singleton = true) { serializer }
        provides<Json>(singleton = true) { testJson }
        provides<ConnectionPool>(singleton = true) { connectionPool }
        provides<DistributedLockFactory>(singleton = true) { distributedLockFactory }
        provides<JobQueue>(name = "contentQueue", singleton = true) { jobQueue }
        provides<PubSubService>(singleton = true) { pubSubService }
        provides<JobQueueFactory>(singleton = true) { jobQueueFactory }
        provides<SecurityService>(singleton = true) { securityService }
        RegisterJobsConfiguration()

        // Collection service with real repos
        val collectionRepository = CollectionRepositoryImpl()
        val collectionJobHistoryService = CollectionJobHistoryServiceImpl(
            CollectionJobHistoryRepositoryImpl(),
            pubSubService
        )

        collectionService = CollectionServiceImpl(
            collectionRepository,
            CollectionItemRepositoryImpl(),
            CollectionFindRepository(testJson),
            CollectionCategoryRepositoryImpl(),
            CollectionTraitRepositoryImpl(),
            CollectionMetadataRelationshipRepositoryImpl(),
            CollectionSupplementaryRepositoryImpl(),
            CollectionWorkflowPlanRepositoryImpl(),
            CollectionPermissionRepositoryImpl(),
            mockk(relaxed = true), // TransitionHistoryService
            mockk<ObjectStorageService>(relaxed = true),
            testJson,
            slugService,
            CollectionCollaborationRepositoryImpl(),
            CollectionLanguageVariantRepositoryImpl(),
            CollectionTemplateRepositoryImpl(),
            CollectionTemplateAttributeRepositoryImpl(),
            MetadataRepositoryImpl(),
            createObjectProvider { transitioner },
            createObjectProvider { securityService },
        )

        // Metadata service with real repos
        metadataRepository = MetadataRepositoryImpl()
        relationshipRepository = MetadataRelationshipRepositoryImpl()
        val metadataJobHistoryService = MetadataJobHistoryServiceImpl(
            MetadataJobHistoryRepositoryImpl(),
            pubSubService
        )

        metadataService = MetadataServiceImpl(
            metadataRepository,
            collectionService,
            mockk<CollectionTemplateService>(relaxed = true),
            createObjectProvider { MetadataFindRepository(testJson) },
            MetadataPermissionRepositoryImpl(),
            MetadataTraitRepositoryImpl(),
            MetadataCategoryRepositoryImpl(),
            MetadataWorkflowPlanRepositoryImpl(),
            MetadataProfileRepositoryImpl(),
            MetadataSupplementaryRepositoryImpl(),
            relationshipRepository,
            mockk(relaxed = true), // TransitionHistoryService
            mockk<DocumentService>(relaxed = true),
            mockk<DocumentTemplateService>(relaxed = true),
            mockk<DataTemplateService>(relaxed = true),
            mockk<GuideService>(relaxed = true),
            mockk<GuideTemplateService>(relaxed = true),
            mockk<DataService>(relaxed = true),
            mockk<BibleService>(relaxed = true),
            mockk<ObjectStorageService>(relaxed = true),
            slugService,
            mockk<TraitService>(relaxed = true),
            mockk<CategoryService>(relaxed = true),
            createObjectProvider { securityService },
            createObjectProvider { transitioner },
            mockk<VideoService>(relaxed = true),
        )

        // State/Transition services (real, reading from Flyway-seeded DB)
        val transitionService = TransitionServiceImpl(TransitionRepositoryImpl())
        val stateService = StateServiceImpl(StateRepositoryImpl())

        // Permission evaluators (real)
        val collectionPermissionEvaluator = CollectionPermissionEvaluator(collectionService, securityService, groupEvaluator)
        val metadataPermissionEvaluator = MetadataPermissionEvaluator(metadataService, securityService, groupEvaluator)

        // Transitioner (real)
        transitioner = Transitioner(
            metadataService,
            metadataJobHistoryService,
            collectionService,
            collectionJobHistoryService,
            transitionService,
            stateService,
            metadataPermissionEvaluator,
            collectionPermissionEvaluator
        )

        // Register services and executors
        provides<CollectionService>(singleton = true) { collectionService }
        provides<MetadataService>(singleton = true) { metadataService }
        provides<Transitioner>(singleton = true) { transitioner }

        // Job enqueuers (KSP-generated classes)
        provides<JobConfigurationEnqueuer>(name = "transition-collection", singleton = true) {
            CollectionTransitionExecutorConfigurationEnqueuer()
        }
        provides<JobConfigurationEnqueuer>(name = "transition-metadata", singleton = true) {
            MetadataTransitionExecutorConfigurationEnqueuer()
        }
        provides<JobConfigurationEnqueuer>(name = "metadata-sync-state", singleton = true) {
            MetadataSyncStateExecutorConfigurationEnqueuer()
        }
        provides<JobConfigurationEnqueuer>(name = "index-metadata", singleton = true) {
            MetadataIndexExecutorConfigurationEnqueuer()
        }
        provides<JobConfigurationEnqueuer>(name = "metadata-parent-item-cache-invalidation", singleton = true) {
            MetadataParentItemCacheInvalidationExecutorConfigurationEnqueuer()
        }
        provides<JobConfigurationEnqueuer>(name = "metadata-process-content", singleton = true) {
            MetadataProcessContentExecutorConfigurationEnqueuer()
        }
        provides<JobConfigurationEnqueuer>(name = "auto-assign-collections", singleton = true) {
            AutoAssignCollectionsExecutorConfigurationEnqueuer()
        }

        // Listener that closes out the history row when transition jobs complete
        provides<UpdateContentJobHistoryOnCompleteListener> {
            UpdateContentJobHistoryOnCompleteListener(
                metadataJobHistoryService, collectionJobHistoryService,
                metadataService, collectionService, securityService, transitioner,
            )
        }

        // Executor providers (for job.newExecutor() in JobRunner)
        provides<CollectionTransitionExecutor> {
            CollectionTransitionExecutor(collectionService, collectionJobHistoryService, transitioner, securityService)
        }
        provides<MetadataTransitionExecutor> {
            MetadataTransitionExecutor(metadataService, metadataJobHistoryService, transitioner, securityService)
        }
        provides<MetadataSyncStateExecutor> {
            MetadataSyncStateExecutor(metadataService, timeEventService, transitioner, securityService)
        }

        // Start job runner
        jobRunner.run()
    }

    @AfterTest
    fun teardown() = runBlocking {
        if (::jobRunner.isInitialized) jobRunner.shutdown()
        ProviderRegistry.clear()
        if (::connectionPool.isInitialized) connectionPool.close()
        stopContainers()
        if (::postgresContainer.isInitialized) postgresContainer.stop()
    }

    private inline fun <reified T : Any> createObjectProvider(crossinline factory: () -> T): ObjectProvider<T> {
        val provider = mockk<ObjectProvider<T>>()
        coEvery { provider.get() } answers { factory() }
        return provider
    }

    private suspend fun <T> withRequest(block: suspend () -> T): T {
        val cm = ConnectionManager(connectionPool)
        val rc = RequestCache(cacheManager, serializer)
        val em = EventManager().apply { filter = DisabledEventManagerFilter }
        return withContext(cm.asCoroutineContext() + rc.asCoroutineContext() + em.asCoroutineContext()) {
            try {
                block()
            } finally {
                withContext(NonCancellable) {
                    cm.release()
                }
            }
        }
    }

    private suspend fun <T> withEventsEnabled(block: suspend () -> T): T {
        val cm = ConnectionManager(connectionPool)
        val rc = RequestCache(cacheManager, serializer)
        val em = EventManager().apply { filter = EnabledEventManagerFilter }
        return withContext(cm.asCoroutineContext() + rc.asCoroutineContext() + em.asCoroutineContext()) {
            try {
                block()
            } finally {
                withContext(NonCancellable) {
                    rc.flush()
                    cm.release()
                }
            }
        }
    }

    private suspend fun rawUpdate(sql: String, vararg params: Any) {
        val cm = ConnectionManager(connectionPool)
        withContext(cm.asCoroutineContext()) {
            cm.beginTransaction()
            cm.useStatement(sql) { stmt ->
                params.forEachIndexed { index, param ->
                    when (param) {
                        is UUID -> stmt.setObject(index + 1, param.toJavaUuid())
                        is String -> stmt.setString(index + 1, param)
                        is Boolean -> stmt.setBoolean(index + 1, param)
                        is Int -> stmt.setInt(index + 1, param)
                        else -> stmt.setObject(index + 1, param)
                    }
                }
                stmt.execute()
            }
            withContext(NonCancellable) {
                cm.commitTransaction()
            }
        }
        withContext(NonCancellable) {
            cm.release()
        }
    }

    private fun createAuthContext(): ImpersonatedAuthenticationContext {
        return ImpersonatedAuthenticationContext(saPrincipal, saGroups)
    }

    private suspend fun waitForMetadataState(id: UUID, version: Int, expectedState: String, timeout: kotlin.time.Duration = 30.seconds) {
        val deadline = Clock.System.now() + timeout
        while (Clock.System.now() < deadline) {
            val metadata = withRequest {
                metadataService.removeFromCache(id, version)
                metadataService.getById(id, version)
            }
            if (metadata != null && metadata.workflowStateId == expectedState && metadata.workflowStatePendingId == null) {
                return
            }
            delay(200.milliseconds)
        }
        val current = withRequest {
            metadataService.removeFromCache(id, version)
            metadataService.getById(id, version)
        }
        error("Timed out waiting for metadata state '$expectedState'. Current: state=${current?.workflowStateId}, pending=${current?.workflowStatePendingId}")
    }

    private suspend fun waitForMetadataPublic(id: UUID, version: Int, timeout: kotlin.time.Duration = 120.seconds) {
        val deadline = Clock.System.now() + timeout
        while (Clock.System.now() < deadline) {
            val metadata = withRequest { 
                metadataService.removeFromCache(id, version)
                metadataService.getById(id, version) 
            }
            if (metadata != null && metadata.public && metadata.publicContent && metadata.publicSupplementary) {
                return
            }
            delay(200)
        }
        val current = withRequest { 
            metadataService.removeFromCache(id, version)
            metadataService.getById(id, version) 
        }
        error("Timed out waiting for metadata to become public. Current: public=${current?.public}, publicContent=${current?.publicContent}, publicSupplementary=${current?.publicSupplementary}")
    }

    private suspend fun createMetadata(
        name: String,
        contentType: String = "text/plain",
        syncVariantRelationships: Boolean = true,
    ): Metadata {
        return withRequest {
            metadataRepository.add(
                Metadata(
                    name = name,
                    type = MetadataType.STANDARD,
                    contentType = contentType,
                    contentLength = null,
                    languageTag = "en",
                    workflowStateId = "pending",
                    attributes = JsonObject(emptyMap()),
                    syncVariantRelationships = syncVariantRelationships,
                )
            )
        }
    }

    private suspend fun addRelationship(parentId: UUID, relatedId: UUID, relationship: String = "variant") {
        withRequest {
            relationshipRepository.add(
                MetadataRelationship(
                    metadataId1 = parentId,
                    metadataId2 = relatedId,
                    relationship = relationship,
                )
            )
        }
    }

    @Test
    fun `published metadata syncs related metadata to published state`() = runBlocking {
        withTimeout(2.minutes) {
            // Create parent and related metadata both starting in "pending"
            val parent = createMetadata("Parent Document")
            val related = createMetadata("Related Image", contentType = "image/png")

            // Create relationship
            addRelationship(parent.id, related.id)

            // Set parent to published with public flags via raw SQL
            rawUpdate(
                "UPDATE metadata SET workflow_state_id = 'published', ready = now(), public = true, public_content = true, public_supplementary = true WHERE id = ?",
                parent.id
            )

            // Set ready on related metadata and wait for it to reach draft
            withRequest {
                metadataService.removeFromCache(parent)
                metadataService.setReady(related, saPrincipal)
            }
            waitForMetadataState(related.id, related.version, "draft")

            // Enqueue the sync state job (simulating what MetadataStateChangeComplete event does)
            withRequest {
                MetadataSyncStateJob(id = parent.id, version = parent.version).enqueue()
            }

            // Wait for related metadata to be synced to published via MetadataSyncStateExecutor
            waitForMetadataState(related.id, related.version, "published")

            // Verify public flags were propagated
            val publishedRelated = withRequest { metadataService.getById(related.id, related.version) }
            assertNotNull(publishedRelated)
            assertEquals("published", publishedRelated.workflowStateId)
            assertTrue(publishedRelated.public, "Related metadata should be public")
            assertTrue(publishedRelated.publicContent, "Related metadata should have public content")
            assertTrue(publishedRelated.publicSupplementary, "Related image should have public supplementary")
        }
    }

    @Test
    fun `sync propagates public flags to already-published related metadata`() = runBlocking {
        withTimeout(2.minutes) {
            // Create parent and related metadata
            val parent = createMetadata("Parent Document")
            val related = createMetadata("Related Document")

            // Set both to published state via raw SQL, but related has no public flags
            rawUpdate(
                "UPDATE metadata SET workflow_state_id = 'published', ready = now(), public = true, public_content = true, public_supplementary = true, sync_variant_relationships = true WHERE id = ?",
                parent.id
            )
            rawUpdate(
                "UPDATE metadata SET workflow_state_id = 'published', ready = now(), public = false, public_content = false, public_supplementary = false WHERE id = ?",
                related.id
            )

            // Invalidate cache so the executor reads fresh data from the database
            withRequest { metadataService.removeFromCache(parent) }
            withRequest { metadataService.removeFromCache(related) }

            // Create relationship
            addRelationship(parent.id, related.id)

            // Directly enqueue the sync state job
            withRequest {
                MetadataSyncStateJob(id = parent.id, version = parent.version).enqueue()
            }

            // Wait for public flags to propagate
            waitForMetadataPublic(related.id, related.version)

            val updated = withRequest { metadataService.getById(related.id, related.version) }
            assertNotNull(updated)
            assertEquals("published", updated.workflowStateId, "Already-published metadata should stay published")
            assertTrue(updated.public, "Public flag should be propagated")
            assertTrue(updated.publicContent, "Public content flag should be propagated")
            assertTrue(updated.publicSupplementary, "Public supplementary flag should be propagated")
        }
    }

    @Test
    fun `sync skips when syncVariantRelationships is false`() = runBlocking {
        withTimeout(1.minutes) {
            // Create parent with syncVariantRelationships=false and related metadata
            val parent = createMetadata("Parent No Sync", syncVariantRelationships = false)
            val related = createMetadata("Related Document")

            // Set parent to published with public flags, but sync disabled
            rawUpdate(
                "UPDATE metadata SET workflow_state_id = 'published', ready = now(), public = true, public_content = true, public_supplementary = true, sync_variant_relationships = false WHERE id = ?",
                parent.id
            )

            // Create relationship
            addRelationship(parent.id, related.id)

            // Enqueue sync state job
            withRequest {
                MetadataSyncStateJob(id = parent.id, version = parent.version).enqueue()
            }

            // Wait a bit to give the job time to execute (if it were going to)
            delay(3.seconds)

            // Verify related metadata is still in pending state and not public
            val unchanged = withRequest { metadataService.getById(related.id, related.version) }
            assertNotNull(unchanged)
            assertEquals("pending", unchanged.workflowStateId, "Related metadata should remain in pending state")
            assertTrue(!unchanged.public, "Related metadata should not be public")
            assertTrue(!unchanged.publicContent, "Related metadata should not have public content")
        }
    }

    @Test
    fun `sync handles multiple relationships`() = runBlocking {
        withTimeout(2.minutes) {
            // Create parent metadata
            val parent = createMetadata("Parent Multi-Rel")

            // Create 3 related metadata items
            val related1 = createMetadata("Related 1")
            val related2 = createMetadata("Related 2", contentType = "image/jpeg")
            val related3 = createMetadata("Related 3")

            // Create relationships
            addRelationship(parent.id, related1.id)
            addRelationship(parent.id, related2.id)
            addRelationship(parent.id, related3.id)

            // Set parent to published with public flags
            rawUpdate(
                "UPDATE metadata SET workflow_state_id = 'published', ready = now(), public = true, public_content = true, public_supplementary = true WHERE id = ?",
                parent.id
            )

            // Set ready on all related metadata and move them to draft
            for (rel in listOf(related1, related2, related3)) {
                withRequest { metadataService.setReady(rel, saPrincipal) }
            }
            for (rel in listOf(related1, related2, related3)) {
                waitForMetadataState(rel.id, rel.version, "draft")
            }

            // Enqueue sync state job
            withRequest {
                MetadataSyncStateJob(id = parent.id, version = parent.version).enqueue()
            }

            // Wait for all related metadata to reach published
            for (rel in listOf(related1, related2, related3)) {
                waitForMetadataState(rel.id, rel.version, "published")
            }

            // Verify all are published and have public flags
            for (rel in listOf(related1, related2, related3)) {
                val published = withRequest { metadataService.getById(rel.id, rel.version) }
                assertNotNull(published, "Related metadata ${rel.name} should exist")
                assertEquals("published", published.workflowStateId, "${rel.name} should be published")
                assertTrue(published.public, "${rel.name} should be public")
                assertTrue(published.publicContent, "${rel.name} should have public content")
            }
        }
    }

    @Test
    fun `full transition to published syncs related metadata via event dispatch`() = runBlocking {
        withTimeout(2.minutes) {
            // Create parent and related metadata both starting in "pending"
            val parent = createMetadata("Parent Full Flow")
            val related = createMetadata("Related Full Flow", contentType = "image/png")

            // Create relationship
            addRelationship(parent.id, related.id)

            // Set ready on both and wait for them to reach draft (pending -> processing -> draft)
            withRequest { metadataService.setReady(parent, saPrincipal) }
            withRequest { metadataService.setReady(related, saPrincipal) }
            waitForMetadataState(parent.id, parent.version, "draft")
            waitForMetadataState(related.id, related.version, "draft")

            // Set public flags on parent via raw SQL (simulating what happens during content setup)
            rawUpdate(
                "UPDATE metadata SET public = true, public_content = true, public_supplementary = true WHERE id = ?",
                parent.id
            )
            // Invalidate parent cache so the transition reads fresh data
            withRequest { metadataService.removeFromCache(parent) }

            // Prime the shared cache by reading parent metadata, so it's cached pre-publish
            val cachedParent = withRequest { metadataService.getById(parent.id, parent.version) }
            assertNotNull(cachedParent)
            assertEquals("draft", cachedParent.workflowStateId)

            // Now transition parent to published using the FULL transition flow with events enabled.
            // This exercises: beginTransition -> setPendingStateComplete -> event dispatch -> MetadataSyncStateJob
            // Before the fix, the sync job would read stale cached data and see isPublished=false.
            withEventsEnabled {
                transitioner.beginTransition(
                    createAuthContext(),
                    BeginTransitionInput(
                        metadataId = parent.id,
                        version = parent.version,
                        stateId = "published",
                        status = "Publishing via full transition flow"
                    )
                )
            }

            // Verify parent reached published
            waitForMetadataState(parent.id, parent.version, "published")

            // Wait for related metadata to be synced to published via MetadataSyncStateExecutor
            // (enqueued by the MetadataStateChangeComplete event dispatch, not manually)
            waitForMetadataState(related.id, related.version, "published")

            // Verify public flags were propagated to related metadata
            val publishedRelated = withRequest { metadataService.getById(related.id, related.version) }
            assertNotNull(publishedRelated)
            assertEquals("published", publishedRelated.workflowStateId)
            assertTrue(publishedRelated.public, "Related metadata should be public")
            assertTrue(publishedRelated.publicContent, "Related metadata should have public content")
            assertTrue(publishedRelated.publicSupplementary, "Related image should have public supplementary")
        }
    }
}
