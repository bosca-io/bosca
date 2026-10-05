package bosca.content.transition

import bosca.cache.CacheManager
import bosca.cache.RequestCache
import bosca.cache.RequestCacheSerializer
import bosca.cache.RequestCacheSerializerImpl
import bosca.cache.asCoroutineContext
import bosca.cache.nats.NatsCacheManager
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
import bosca.content.metadata.model.Metadata
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
import bosca.content.state.repository.StateRepositoryImpl
import bosca.content.state.service.StateServiceImpl
import bosca.content.video.service.VideoService
import bosca.content.transition.jobs.CollectionTransitionExecutor
import bosca.content.transition.jobs.CollectionTransitionExecutorConfigurationEnqueuer
import bosca.content.transition.jobs.MetadataTransitionExecutor
import bosca.content.transition.jobs.MetadataTransitionExecutorConfigurationEnqueuer
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
import bosca.lock.nats.NatsDistributedLockFactory
import bosca.nats.NatsConnectionPool
import bosca.pubsub.PubSubService
import bosca.security.model.AuthenticatedPrincipal
import bosca.security.model.Group
import bosca.security.model.Principal
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.security.service.ImpersonatedAuthenticationContext
import bosca.security.service.SecurityService
import bosca.serialization.OffsetDateTimeSerializer
import bosca.serialization.UUID
import bosca.serialization.UUIDSerializer
import bosca.security.model.GroupType
import bosca.sharedqueue.jobs.JobConfigurationEnqueuer
import bosca.sharedqueue.jobs.JobQueue
import bosca.sharedqueue.jobs.JobQueueFactory
import bosca.sharedqueue.jobs.JobRunner
import bosca.sharedqueue.jobs.configuration.RegisterJobsConfiguration
import bosca.sharedqueue.jobs.nats.NatsJobQueueFactory
import bosca.slug.service.SlugService
import bosca.storage.service.ObjectStorageService
import bosca.trait.service.TraitService
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import bosca.test.resources.SharedNatsContainer
import bosca.test.resources.SharedPostgreSQLContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.lifecycle.Startables
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlin.uuid.toJavaUuid

@OptIn(InternalDI::class)
class StateTransitionEndToEndTest {

    private lateinit var natsContainer: SharedNatsContainer
    private lateinit var postgresContainer: SharedPostgreSQLContainer
    private lateinit var connectionPool: ConnectionPool
    private lateinit var cacheManager: CacheManager
    private lateinit var serializer: RequestCacheSerializer
    private lateinit var jobRunner: JobRunner
    private lateinit var natsJobQueue: JobQueue

    private val testJson = Json {
        ignoreUnknownKeys = true
        serializersModule = SerializersModule {
            contextual(OffsetDateTimeSerializer())
            contextual(UUIDSerializer())
        }
    }

    private lateinit var collectionService: CollectionServiceImpl
    private lateinit var collectionRepository: CollectionRepositoryImpl
    private lateinit var variantRepository: CollectionLanguageVariantRepositoryImpl
    private lateinit var metadataService: MetadataServiceImpl
    private lateinit var metadataRepository: MetadataRepositoryImpl
    private lateinit var transitioner: Transitioner

    private val securityService = mockk<SecurityService>()
    private val slugService = mockk<SlugService>(relaxed = true)
    private val pubSubService = mockk<PubSubService>(relaxed = true)

    private val saGroups = listOf(
        Group(id = UUID.random(), name = "sa", description = "", type = GroupType.SYSTEM),
        Group(id = UUID.random(), name = "administrators", description = "", type = GroupType.SYSTEM),
    )
    private val saPrincipal = Principal(id = UUID.random())

    @BeforeTest
    fun setup() = runBlocking {
        unmockkAll()

        // Start containers
        natsContainer = SharedNatsContainer()
            .withExposedPorts(4222)
            .withCommand("-js")
            .withReuse(true)
            .waitingFor(Wait.forListeningPort())

        postgresContainer = SharedPostgreSQLContainer("pgvector/pgvector:pg17").apply {
            withUsername("test")
            withPassword("test")
            withDatabaseName("test")
            withReuse(true)
        }

        Startables.deepStart(natsContainer, postgresContainer).join()

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

        // NATS infrastructure
        val natsUrl = "nats://${natsContainer.host}:${natsContainer.getMappedPort(4222)}"
        val natsPool = natsContainer.newConnectionPool(1)
        cacheManager = NatsCacheManager(natsPool)
        serializer = RequestCacheSerializerImpl(testJson)
        val distributedLockFactory = NatsDistributedLockFactory(natsPool)

        // Job queue + runner
        val natsJobQueueFactory = NatsJobQueueFactory(natsPool, testJson, distributedLockFactory, null, emptyList())
        natsJobQueue = natsJobQueueFactory.create("content")
        jobRunner = JobRunner(natsJobQueue, 10, distributedLockFactory)

        // Configure SecurityService mock
        coEvery { securityService.getPrincipalGroups(any<UUID>()) } returns saGroups
        coEvery { securityService.getPrincipalByIdentifier("sa") } returns saPrincipal
        val groupEvaluator = GroupEvaluator(securityService)

        // DI registrations (must come before service construction — some repos resolve DI at init)
        ProviderRegistry.clear()
        provides<CacheManager>(singleton = true) { cacheManager }
        provides<RequestCacheSerializer>(singleton = true) { serializer }
        provides<Json>(singleton = true) { testJson }
        provides<ConnectionPool>(singleton = true) { connectionPool }
        provides<DistributedLockFactory>(singleton = true) { distributedLockFactory }
        provides<JobQueue>(name = "contentQueue", singleton = true) { natsJobQueue }
        provides<PubSubService>(singleton = true) { pubSubService }
        provides<JobQueueFactory>(singleton = true) { natsJobQueueFactory }
        provides<SecurityService>(singleton = true) { securityService }
        RegisterJobsConfiguration()

        // Collection service with real repos
        collectionRepository = CollectionRepositoryImpl()
        variantRepository = CollectionLanguageVariantRepositoryImpl()
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
            variantRepository,
            CollectionTemplateRepositoryImpl(),
            CollectionTemplateAttributeRepositoryImpl(),
            MetadataRepositoryImpl(),
            createObjectProvider { transitioner },
            createObjectProvider { securityService },
        )

        // Metadata service with real repos
        metadataRepository = MetadataRepositoryImpl()
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

        // Transitioner (real, all 8 deps)
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

        // Register services and executors (depends on constructed services above)
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

        // Start job runner
        jobRunner.run()
    }

    @AfterTest
    fun teardown() = runBlocking {
        if (::jobRunner.isInitialized) jobRunner.shutdown()
        if (::connectionPool.isInitialized) connectionPool.close()
        if (::natsContainer.isInitialized) natsContainer.stop()
        if (::postgresContainer.isInitialized) postgresContainer.stop()
        ProviderRegistry.clear()
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

    private suspend fun waitForCollectionState(id: UUID, expectedState: String, timeout: kotlin.time.Duration = 120.seconds) {
        val started = TimeSource.Monotonic.markNow()
        // Poll committed state without populating the service cache while the job is mutating it.
        while (started.elapsedNow() < timeout) {
            val collection = withRequest { collectionRepository.getById(id) }
            if (collection != null && collection.workflowStateId == expectedState && collection.workflowStatePendingId == null) {
                return
            }
            pollingDelay(200)
        }
        val current = withRequest { collectionRepository.getById(id) }
        error("Timed out waiting for collection state '$expectedState'. Current: state=${current?.workflowStateId}, pending=${current?.workflowStatePendingId}")
    }

    private suspend fun waitForVariantState(id: UUID, languageTag: String, expectedState: String, timeout: kotlin.time.Duration = 120.seconds) {
        val started = TimeSource.Monotonic.markNow()
        while (started.elapsedNow() < timeout) {
            val variant = withRequest { variantRepository.getLanguageVariant(id, languageTag) }
            if (variant != null && variant.workflowStateId == expectedState && variant.workflowStatePendingId == null) {
                return
            }
            pollingDelay(200)
        }
        val current = withRequest { variantRepository.getLanguageVariant(id, languageTag) }
        error("Timed out waiting for variant state '$expectedState'. Current: state=${current?.workflowStateId}, pending=${current?.workflowStatePendingId}")
    }

    private suspend fun waitForMetadataState(id: UUID, version: Int, expectedState: String, timeout: kotlin.time.Duration = 120.seconds) {
        val started = TimeSource.Monotonic.markNow()
        while (started.elapsedNow() < timeout) {
            val metadata = withRequest { metadataRepository.getById(id, version) }
            if (metadata != null && metadata.workflowStateId == expectedState && metadata.workflowStatePendingId == null) {
                return
            }
            pollingDelay(200)
        }
        val current = withRequest { metadataRepository.getById(id, version) }
        error("Timed out waiting for metadata state '$expectedState'. Current: state=${current?.workflowStateId}, pending=${current?.workflowStatePendingId}")
    }

    // Database and queue work runs in real time; runTest must not skip the polling interval.
    private suspend fun pollingDelay(millis: Long) = withContext(Dispatchers.Default) { delay(millis) }

    @Test
    fun `state polling observes committed collection state despite a stale cache`() = runTest(timeout = 30.seconds) {
        val created = withRequest {
            collectionService.add(CollectionInput(name = "Polling Test"), parent = null, parentItemAttributes = null)
        }
        withRequest { collectionService.getById(created.id) }
        rawUpdate("UPDATE collections SET workflow_state_id = 'draft' WHERE id = ?", created.id)
        assertEquals("pending", withRequest { collectionService.getById(created.id) }?.workflowStateId)

        waitForCollectionState(created.id, "draft", timeout = 1.seconds)
    }

    @Test
    fun `collection full lifecycle pending to draft to published`() = runTest(timeout = 3.minutes) {
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

        // setReady triggers pending → processing → draft via real job system
        withRequest {
            collectionService.setReady(created.id, saPrincipal, null)
        }

        // Wait for the transition job to complete (pending → processing → draft)
        waitForCollectionState(created.id, "draft")

        val afterDraft = withRequest { collectionService.getById(created.id) }
        assertNotNull(afterDraft)
        assertEquals("draft", afterDraft.workflowStateId)
        assertNull(afterDraft.workflowStatePendingId)
        assertNotNull(afterDraft.ready, "ready should be preserved through transitions")

        // Transition from draft → published via real Transitioner + job system
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
    }

    @Test
    fun `collection language variant full lifecycle`() = runTest(timeout = 3.minutes) {
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

        // setReady with languageTag triggers pending → processing → draft for the variant
        withRequest {
            collectionService.setReady(parent.id, saPrincipal, "es")
        }

        waitForVariantState(parent.id, "es", "draft")

        val afterDraft = withRequest { collectionService.getLanguageVariant(parent.id, "es") }
        assertNotNull(afterDraft)
        assertEquals("draft", afterDraft.workflowStateId)
        assertNull(afterDraft.workflowStatePendingId)
        assertNotNull(afterDraft.ready)

        // Transition variant from draft → published
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
    }

    @Test
    fun `metadata full lifecycle pending to draft to published`() = runTest(timeout = 3.minutes) {
        // Create metadata
        val inserted = withRequest {
            metadataRepository.add(
                Metadata(
                    name = "Lifecycle Test Metadata",
                    type = MetadataType.STANDARD,
                    contentType = "text/plain",
                    contentLength = null,
                    languageTag = "en",
                    workflowStateId = "pending",
                    attributes = JsonObject(emptyMap()),
                )
            )
        }

        assertEquals("pending", inserted.workflowStateId)
        assertNull(inserted.ready)

        // setReady triggers pending → processing → draft via real job system
        withRequest {
            metadataService.setReady(inserted, saPrincipal)
        }

        waitForMetadataState(inserted.id, inserted.version, "draft")

        val afterDraft = withRequest { metadataService.getById(inserted.id, inserted.version) }
        assertNotNull(afterDraft)
        assertEquals("draft", afterDraft.workflowStateId)
        assertNull(afterDraft.workflowStatePendingId)
        assertNotNull(afterDraft.ready)

        // Transition from draft → published
        withRequest {
            transitioner.beginTransition(
                createAuthContext(),
                BeginTransitionInput(
                    metadataId = inserted.id,
                    version = inserted.version,
                    stateId = "published",
                    status = "Publishing metadata",
                ),
                afterDraft
            )
        }

        waitForMetadataState(inserted.id, inserted.version, "published")

        val published = withRequest { metadataService.getById(inserted.id, inserted.version) }
        assertNotNull(published)
        assertEquals("published", published.workflowStateId)
        assertNull(published.workflowStatePendingId)
        assertNotNull(published.ready)
    }

    @Test
    fun `batch setReady in tight loop transitions all items to draft`() = runTest(timeout = 5.minutes) {
        val count = 100
        val items = mutableListOf<Metadata>()

        for (i in 0 until count) {
            val inserted = withRequest {
                metadataRepository.add(
                    Metadata(
                        name = "Batch Item $i",
                        type = MetadataType.STANDARD,
                        contentType = "image/png",
                        contentLength = null,
                        languageTag = "en",
                        workflowStateId = "pending",
                        attributes = JsonObject(emptyMap()),
                    )
                )
            }
            items.add(inserted)
        }

        for (item in items) {
            withRequest {
                metadataService.setReady(item, saPrincipal)
            }
        }

        val started = TimeSource.Monotonic.markNow()
        val failed = mutableListOf<Metadata>()
        while (started.elapsedNow() < 120.seconds) {
            failed.clear()
            for (item in items) {
                val current = withRequest { metadataRepository.getById(item.id, item.version) }
                if (current == null || current.workflowStateId != "draft" || current.workflowStatePendingId != null) {
                    failed.add(current ?: item)
                }
            }
            if (failed.isEmpty()) break
            pollingDelay(500)
        }

        if (failed.isNotEmpty()) {
            val summary = failed.joinToString("\n") { m ->
                "  ${m.id}: state=${m.workflowStateId}, pending=${m.workflowStatePendingId}, ready=${m.ready}"
            }
            error("${failed.size}/$count items did NOT reach draft:\n$summary")
        }
    }
}
