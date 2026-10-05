package bosca.content.transition

import bosca.cache.CacheManager
import bosca.cache.RequestCache
import bosca.cache.RequestCacheSerializer
import bosca.cache.RequestCacheSerializerImpl
import bosca.cache.asCoroutineContext
import bosca.category.service.CategoryService
import bosca.content.collection.model.CollectionInput
import bosca.content.collection.model.CollectionLanguageVariantInput
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
import bosca.content.state.repository.StateRepositoryImpl
import bosca.content.state.service.StateServiceImpl
import bosca.content.video.service.VideoService
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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import bosca.test.resources.SharedPostgreSQLContainer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.toJavaUuid

/**
 * Abstract end-to-end test for the collection transition lifecycle that verifies collections
 * transition correctly through pending, draft, and published states using a real PostgreSQL
 * database with the full job execution pipeline.
 *
 * Subclasses provide the job queue backend (NATS or Redis) while this class defines shared
 * infrastructure setup and test scenarios for both standard collections and language variants.
 */
@OptIn(InternalDI::class)
abstract class CollectionTransitionEndToEndTest {

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
    private lateinit var collectionJobHistoryService: CollectionJobHistoryServiceImpl
    private lateinit var metadataService: MetadataServiceImpl
    private lateinit var transitioner: Transitioner

    private val securityService = mockk<SecurityService>()
    private val slugService = mockk<SlugService>(relaxed = true)
    private val pubSubService = mockk<PubSubService>(relaxed = true)

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
        collectionJobHistoryService = CollectionJobHistoryServiceImpl(
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

        // Metadata service with real repos (required by Transitioner)
        val metadataRepository = MetadataRepositoryImpl()
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
            MetadataRelationshipRepositoryImpl(),
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

        // Executor providers (for job.newExecutor() in JobRunner)
        provides<CollectionTransitionExecutor> {
            CollectionTransitionExecutor(collectionService, collectionJobHistoryService, transitioner, securityService)
        }
        provides<MetadataTransitionExecutor> {
            MetadataTransitionExecutor(metadataService, metadataJobHistoryService, transitioner, securityService)
        }

        provides<UpdateContentJobHistoryOnCompleteListener> {
            UpdateContentJobHistoryOnCompleteListener(
                metadataJobHistoryService, collectionJobHistoryService,
                metadataService, collectionService, securityService, transitioner,
            )
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

    private suspend fun rawUpdate(sql: String, id: UUID) {
        val cm = ConnectionManager(connectionPool)
        withContext(cm.asCoroutineContext()) {
            cm.beginTransaction()
            cm.useStatement(sql) { stmt ->
                stmt.setObject(1, id.toJavaUuid())
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

    private suspend fun waitForCollectionState(id: UUID, expectedState: String, timeout: kotlin.time.Duration = 100.seconds) {
        val deadline = Clock.System.now() + timeout
        var lastState: String? = null
        var lastPending: String? = null
        while (Clock.System.now() < deadline) {
            val collection = withRequest {
                collectionService.removeFromCache(id)
                collectionService.getById(id)
            }
            if (collection != null) {
                lastState = collection.workflowStateId
                lastPending = collection.workflowStatePendingId
                if (lastState == expectedState && lastPending == null) {
                    return
                }
            }
            delay(500)
        }
        error("Timed out waiting for collection state '$expectedState'. Current: state=$lastState, pending=$lastPending")
    }

    private suspend fun waitForCollectionStateAndPending(
        id: UUID,
        expectedState: String,
        expectedPending: String?,
        timeout: kotlin.time.Duration = 100.seconds,
    ) {
        val deadline = Clock.System.now() + timeout
        var lastState: String? = null
        var lastPending: String? = null
        while (Clock.System.now() < deadline) {
            val collection = withRequest {
                collectionService.removeFromCache(id)
                collectionService.getById(id)
            }
            if (collection != null) {
                lastState = collection.workflowStateId
                lastPending = collection.workflowStatePendingId
                if (lastState == expectedState && lastPending == expectedPending) {
                    return
                }
            }
            delay(500)
        }
        error("Timed out waiting for collection state='$expectedState', pending='$expectedPending'. Current: state=$lastState, pending=$lastPending")
    }

    private suspend fun waitForVariantState(id: UUID, languageTag: String, expectedState: String, timeout: kotlin.time.Duration = 100.seconds) {
        val deadline = Clock.System.now() + timeout
        var lastState: String? = null
        var lastPending: String? = null
        while (Clock.System.now() < deadline) {
            val variant = withRequest {
                collectionService.removeFromCache(id)
                collectionService.getLanguageVariant(id, languageTag)
            }
            if (variant != null) {
                lastState = variant.workflowStateId
                lastPending = variant.workflowStatePendingId
                if (lastState == expectedState && lastPending == null) {
                    return
                }
            }
            delay(500)
        }
        error("Timed out waiting for variant state '$expectedState'. Current: state=$lastState, pending=$lastPending")
    }

    private suspend fun waitForNoActiveJobs(id: UUID, timeout: kotlin.time.Duration = 10.seconds) {
        val deadline = Clock.System.now() + timeout
        while (Clock.System.now() < deadline) {
            val activeJobs = withRequest { collectionJobHistoryService.getActiveJobs(id) }
            if (activeJobs.isEmpty()) return
            delay(200)
        }
        val activeJobs = withRequest { collectionJobHistoryService.getActiveJobs(id) }
        assertEquals(0, activeJobs.size, "No active jobs should remain after a completed transition, but found: ${activeJobs.map { "${it.jobName} (${it.status})" }}")
    }

    @Test
    fun `collection full lifecycle pending to draft to published`() = runBlocking {
        withTimeout(3.minutes) {
            // Create collection
            val created = withRequest {
                collectionService.add(
                    CollectionInput(name = "Lifecycle Test"),
                    parent = null,
                    parentItemAttributes = null
                )
            }

            assertNotNull(created.id)
            assertEquals("pending", created.workflowStateId)
            assertNull(created.ready)

            // setReady triggers pending -> processing -> draft via real job system
            withRequest {
                collectionService.setReady(created.id, saPrincipal, null)
            }

            // Wait for the transition job to complete (pending -> processing -> draft)
            waitForCollectionState(created.id, "draft")

            val afterDraft = withRequest { collectionService.getById(created.id) }
            assertNotNull(afterDraft)
            assertEquals("draft", afterDraft.workflowStateId)
            assertNull(afterDraft.workflowStatePendingId)
            assertNotNull(afterDraft.ready, "ready should be preserved through transitions")

            // Transition from draft -> published via real Transitioner + job system
            withRequest {
                transitioner.beginTransition(
                    createAuthContext(),
                    BeginTransitionInput(
                        collectionId = created.id,
                        stateId = "published",
                        status = "Publishing collection",
                    ),
                    afterDraft
                )
            }

            waitForCollectionState(created.id, "published")

            val published = withRequest { collectionService.getById(created.id) }
            assertNotNull(published)
            assertEquals("published", published.workflowStateId)
            assertNull(published.workflowStatePendingId)
            assertNotNull(published.ready)

            // Verify no active jobs remain after transition completes
            waitForNoActiveJobs(created.id)
            Unit
        }
    }

    @Test
    fun `collection language variant full lifecycle`() = runBlocking {
        withTimeout(3.minutes) {
            // Create parent collection
            val parent = withRequest {
                collectionService.add(
                    CollectionInput(name = "Parent for Variant"),
                    parent = null,
                    parentItemAttributes = null
                )
            }

            // Set parent to draft to avoid its pending state interfering
            rawUpdate("UPDATE collections SET workflow_state_id = 'draft' WHERE id = ?", parent.id)

            // Add language variant
            val variant = withRequest {
                collectionService.addLanguageVariant(
                    CollectionLanguageVariantInput(
                        id = parent.id,
                        languageTag = "es",
                        name = "Spanish Variant",
                    )
                )
            }

            assertEquals("pending", variant.workflowStateId)
            assertNull(variant.ready)

            // setReady with languageTag triggers pending -> processing -> draft for the variant
            withRequest {
                collectionService.setReady(parent.id, saPrincipal, "es")
            }

            waitForVariantState(parent.id, "es", "draft")

            val afterDraft = withRequest { collectionService.getLanguageVariant(parent.id, "es") }
            assertNotNull(afterDraft)
            assertEquals("draft", afterDraft.workflowStateId)
            assertNull(afterDraft.workflowStatePendingId)
            assertNotNull(afterDraft.ready)

            // Transition variant from draft -> published
            withRequest {
                transitioner.beginTransition(
                    createAuthContext(),
                    BeginTransitionInput(
                        collectionId = parent.id,
                        languageTag = "es",
                        stateId = "published",
                        status = "Publishing variant",
                    ),
                    afterDraft
                )
            }

            waitForVariantState(parent.id, "es", "published")

            val published = withRequest { collectionService.getLanguageVariant(parent.id, "es") }
            assertNotNull(published)
            assertEquals("published", published.workflowStateId)
            assertNull(published.workflowStatePendingId)
            assertNotNull(published.ready)

            // Verify no active jobs remain after transition completes
            waitForNoActiveJobs(parent.id)
            Unit
        }
    }

    /**
     * Reproduces the bug where a "published" transition on a collection whose
     * `advertised` epoch is in the past was routing through the advertised
     * redirect with `delay = getDelay(allowPast = true)` — a non-null but past
     * `OffsetDateTime`. That non-null delay flowed into `addHistory`'s
     * `delayedUntil`, so every history row for the draft→advertised transition
     * carried a `delayedUntil` already in the past even though each job had
     * actually been enqueued immediately. The UI then displayed the stale
     * `delayedUntil` as if the transition were still waiting.
     *
     * After the fix, the advertised redirect still fires (because an
     * `advertised` window is configured), but the delay is only set when the
     * advertised epoch is in the future. A past advertised epoch means the
     * window is already active, so the redirected transition runs immediately
     * with no delay recorded. The recursive advertised→published call then
     * schedules the publish normally using `published.getDelay()`.
     *
     * This mirrors the metadata-side coverage in
     * `AdvertisedPublishTransitionEndToEndTest.draft with future published and
     * past advertised redirects through advertised`; it exists to lock in the
     * symmetric collection behavior that was previously untested.
     */
    @Test
    fun `collection with past advertised and future published redirects without stale delayedUntil`() = runBlocking {
        withTimeout(3.minutes) {
            val now = System.currentTimeMillis()
            val advertisedEpoch = now - 60_000L                  // 1 minute ago
            val publishedEpoch = now + 24L * 60 * 60 * 1000      // 1 day from now

            val created = withRequest {
                collectionService.add(
                    CollectionInput(
                        name = "Past Advertised Future Published",
                        attributes = JsonObject(
                            mapOf(
                                "advertised" to JsonPrimitive(advertisedEpoch),
                                "published" to JsonPrimitive(publishedEpoch),
                            )
                        ),
                    ),
                    parent = null,
                    parentItemAttributes = null,
                )
            }

            withRequest {
                collectionService.setReady(created.id, saPrincipal, null)
            }

            waitForCollectionState(created.id, "draft")

            val draft = withRequest { collectionService.getById(created.id) }
            assertNotNull(draft)

            withRequest {
                transitioner.beginTransition(
                    createAuthContext(),
                    BeginTransitionInput(
                        collectionId = created.id,
                        stateId = "published",
                        status = "User clicks publish",
                    ),
                    draft,
                )
            }

            waitForCollectionStateAndPending(
                id = created.id,
                expectedState = "advertised",
                expectedPending = "published",
            )

            val settled = withRequest { collectionService.getById(created.id) }
            assertNotNull(settled)
            assertEquals("advertised", settled.workflowStateId)
            assertEquals("published", settled.workflowStatePendingId)
            val stateValid = settled.workflowStateValid
            assertNotNull(stateValid, "publish must be scheduled for the future, not committed immediately")
            assertTrue(
                stateValid.toInstant().toEpochMilli() > System.currentTimeMillis(),
                "stateValid should be in the future, was $stateValid",
            )

            // The crux of the bug: every history row written by the
            // draft→advertised transition must have been enqueued for "now",
            // not for the past advertised epoch. The pending publish row is
            // allowed to carry a future delayedUntil; no row should carry one
            // in the past.
            val history = withRequest { collectionJobHistoryService.getHistory(created.id) }
            val staleDelays = history.filter { h ->
                val delayedMs = h.delayedUntil?.toInstant()?.toEpochMilli()
                delayedMs != null && delayedMs < now
            }
            assertEquals(
                0, staleDelays.size,
                "No history row should carry a delayedUntil earlier than the test start, but found: " +
                    staleDelays.joinToString { "${it.jobName} delayed until ${it.delayedUntil}" },
            )
            Unit
        }
    }
}
