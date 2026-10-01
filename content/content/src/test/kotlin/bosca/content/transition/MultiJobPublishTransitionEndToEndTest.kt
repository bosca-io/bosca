package bosca.content.transition

import bosca.cache.CacheManager
import bosca.cache.RequestCache
import bosca.cache.RequestCacheSerializer
import bosca.cache.RequestCacheSerializerImpl
import bosca.cache.asCoroutineContext
import bosca.cache.nats.NatsCacheManager
import bosca.category.service.CategoryService
import bosca.configuration.service.ConfigurationService
import bosca.content.collection.events.COLLECTION_UPDATED_CHANNEL
import bosca.content.collection.events.CollectionUpdated
import bosca.content.collection.jobs.AutoAssignCollectionsExecutor
import bosca.content.collection.jobs.AutoAssignCollectionsExecutorConfigurationEnqueuer
import bosca.content.collection.jobs.SetCollectionStatusJobExecutor
import bosca.content.collection.jobs.SetCollectionStatusJobExecutorConfigurationEnqueuer
import bosca.content.collection.model.Collection
import bosca.content.collection.model.CollectionInput
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
import bosca.content.metadata.events.METADATA_UPDATED_CHANNEL
import bosca.content.metadata.events.MetadataUpdated
import bosca.content.metadata.jobs.SetMetadataStatusJobExecutor
import bosca.content.metadata.jobs.SetMetadataStatusJobExecutorConfigurationEnqueuer
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataJobHistory
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
import bosca.content.transition.jobs.CollectionTransitionExecutor
import bosca.content.transition.jobs.CollectionTransitionExecutorConfigurationEnqueuer
import bosca.content.transition.jobs.MetadataTransitionExecutor
import bosca.content.transition.jobs.MetadataTransitionExecutorConfigurationEnqueuer
import bosca.content.transition.jobs.UpdateContentJobHistoryOnCompleteListener
import bosca.content.transition.model.BeginTransitionInput
import bosca.content.transition.repository.TransitionRepositoryImpl
import bosca.content.transition.service.TransitionServiceImpl
import bosca.content.transition.service.Transitioner
import bosca.content.video.service.VideoService
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
import bosca.security.model.Group
import bosca.security.model.GroupType
import bosca.security.model.Principal
import bosca.security.service.GroupEvaluator
import bosca.security.service.ImpersonatedAuthenticationContext
import bosca.security.service.SecurityService
import bosca.serialization.OffsetDateTime
import bosca.serialization.OffsetDateTimeSerializer
import bosca.serialization.UUID
import bosca.serialization.UUIDSerializer
import bosca.sharedqueue.jobs.JobConfigurationEnqueuer
import bosca.sharedqueue.jobs.JobQueue
import bosca.sharedqueue.jobs.JobQueueFactory
import bosca.sharedqueue.jobs.JobRunner
import bosca.sharedqueue.jobs.configuration.JobQueueNames
import bosca.sharedqueue.jobs.configuration.RegisterJobsConfiguration
import bosca.sharedqueue.jobs.nats.NatsJobQueueFactory
import bosca.slug.service.SlugService
import bosca.storage.service.ObjectStorageService
import bosca.trait.service.TraitService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.toJavaUuid

/**
 * End-to-end coverage for the user-reported scenario where the `published` and
 * `advertised` workflow states are configured with a `multi-job` that fans out
 * into `set-metadata-status`, `set-collection-status`, and `auto-assign-collections`
 * child jobs.
 *
 * The multi-job scheme is configured on a state like:
 * ```
 * {
 *   "jobs": [
 *     { "name": "set-metadata-status",     "type": "metadata",   "configuration": { "public": true, "publicContent": true } },
 *     { "name": "set-collection-status",   "type": "collection", "configuration": { "public": true, "publicList": true } },
 *     { "name": "auto-assign-collections", "type": "collection", "configuration": {} },
 *     { "name": "auto-assign-collections", "type": "metadata",   "configuration": {} }
 *   ]
 * }
 * ```
 *
 * The `type` filter in [bosca.sharedqueue.jobs.jobs.MultiJobExecutor] picks only
 * the child jobs whose `type` matches the item being transitioned (metadata vs
 * collection), so a metadata publish fans out to `set-metadata-status` and the
 * metadata-typed `auto-assign-collections`; a collection publish fans out to
 * `set-collection-status` and the collection-typed `auto-assign-collections`.
 *
 * These tests exercise the full Postgres + NATS stack via Testcontainers to
 * cover the same timing/coordination path that the admin UI sees — the bug
 * being guarded against is a parent multi-job whose job-history row never
 * gets `complete` written back after all children finish, leaving the UI
 * reporting the publish as still in flight.
 */
@OptIn(InternalDI::class)
class MultiJobPublishTransitionEndToEndTest {

    private lateinit var natsContainer: SharedNatsContainer
    private lateinit var postgresContainer: SharedPostgreSQLContainer
    private lateinit var connectionPool: ConnectionPool
    private lateinit var natsPool: NatsConnectionPool
    private lateinit var cacheManager: CacheManager
    private lateinit var serializer: RequestCacheSerializer
    // Production uses two distinct NATS queues: the multi-job parent runs on
    // `common`, its children + transition executors run on `content`. We mirror
    // that split here with two independent JobRunners so the test actually
    // exercises the cross-queue coordination path — collapsing them into one
    // queue would mask any bug that depends on child completion notifications
    // crossing a queue boundary.
    private lateinit var commonJobRunner: JobRunner
    private lateinit var contentJobRunner: JobRunner
    private lateinit var commonNatsJobQueue: JobQueue
    private lateinit var contentNatsJobQueue: JobQueue

    // Matches the production Json config in BoscaApplication so serialization
    // behaviour (and the resulting required-vs-optional field semantics) is
    // identical to what the real server sees at runtime. Deviating — e.g.
    // dropping `explicitNulls = false` — causes the user's multi-job payload
    // to hit a MissingFieldException on unset nullable fields like
    // `publicSupplementary`, which masks the actual bug this test is after.
    private val testJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = false
        explicitNulls = false
        serializersModule = SerializersModule {
            contextual(OffsetDateTimeSerializer())
            contextual(UUIDSerializer())
        }
    }

    private lateinit var collectionService: CollectionServiceImpl
    private lateinit var metadataService: MetadataServiceImpl
    private lateinit var metadataJobHistoryService: MetadataJobHistoryServiceImpl
    private lateinit var collectionJobHistoryService: CollectionJobHistoryServiceImpl
    private lateinit var metadataRepository: MetadataRepositoryImpl
    private lateinit var metadataJobHistoryRepository: MetadataJobHistoryRepositoryImpl
    private lateinit var collectionJobHistoryRepository: CollectionJobHistoryRepositoryImpl
    private lateinit var transitioner: Transitioner

    private val securityService = mockk<SecurityService>()
    private val slugService = mockk<SlugService>(relaxed = true)
    private val pubSubService = mockk<PubSubService>(relaxed = true)
    private val configurationService = mockk<ConfigurationService>(relaxed = true)

    private val saGroups = listOf(
        Group(id = UUID.random(), name = "sa", description = "", type = GroupType.SYSTEM),
        Group(id = UUID.random(), name = "administrators", description = "", type = GroupType.SYSTEM),
    )
    private val saPrincipal = Principal(id = UUID.random())

    /**
     * The exact multi-job configuration from the bug report: four jobs spanning
     * both content types, where the `type` filter inside `MultiJobExecutor`
     * decides which subset actually runs for a given item.
     */
    private val publishedMultiJobConfiguration = """
        {
          "jobs": [
            { "name": "set-metadata-status",     "type": "metadata",   "configuration": { "public": true, "publicContent": true } },
            { "name": "set-collection-status",   "type": "collection", "configuration": { "public": true, "publicList": true } },
            { "name": "auto-assign-collections", "type": "collection", "configuration": {} },
            { "name": "auto-assign-collections", "type": "metadata",   "configuration": {} }
          ]
        }
    """.trimIndent()

    @BeforeTest
    fun setup() = runBlocking<Unit> {
        unmockkAll()

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

        val natsUrl = "nats://${natsContainer.host}:${natsContainer.getMappedPort(4222)}"
        natsPool = natsContainer.newConnectionPool(1)
        cacheManager = NatsCacheManager(natsPool)
        serializer = RequestCacheSerializerImpl(testJson)
        val distributedLockFactory = NatsDistributedLockFactory(natsPool)

        val natsJobQueueFactory = NatsJobQueueFactory(natsPool, testJson, distributedLockFactory, null, emptyList())
        // Mirror production: `common` hosts the multi-job parent,
        // `content` hosts its fan-out children and the transition executors.
        // Each queue gets its own JobRunner so child completion
        // notifications have to cross the queue boundary (via
        // NotifyParentListener + OnChildChangedListener) just like they do
        // in the deployed system.
        commonNatsJobQueue = natsJobQueueFactory.create("common")
        contentNatsJobQueue = natsJobQueueFactory.create("content")
        commonJobRunner = JobRunner(commonNatsJobQueue, 10, distributedLockFactory)
        contentJobRunner = JobRunner(contentNatsJobQueue, 10, distributedLockFactory)

        coEvery { securityService.getPrincipalGroups(any<UUID>()) } returns saGroups
        coEvery { securityService.getPrincipalByIdentifier("sa") } returns saPrincipal
        // `auto-assign-collections` looks up its rules via configuration;
        // returning null for every key makes the executor a no-op, which is
        // exactly what we want for this test.
        coEvery { configurationService.getByKey(any()) } returns null
        val groupEvaluator = GroupEvaluator(securityService)

        ProviderRegistry.clear()
        provides<CacheManager>(singleton = true) { cacheManager }
        provides<RequestCacheSerializer>(singleton = true) { serializer }
        provides<Json>(singleton = true) { testJson }
        provides<ConnectionPool>(singleton = true) { connectionPool }
        provides<DistributedLockFactory>(singleton = true) { distributedLockFactory }
        provides<PubSubService>(singleton = true) { pubSubService }
        provides<JobQueueFactory>(singleton = true) { natsJobQueueFactory }
        provides<SecurityService>(singleton = true) { securityService }
        provides<ConfigurationService>(singleton = true) { configurationService }
        provides<SlugService>(singleton = true) { slugService }
        // RegisterJobsConfiguration registers commonJobQueue via
        // `factory.create("common")`, which would create its own (third)
        // stream. Override with our explicit queue instance so the multi-job
        // parent lands on the same NATS stream our commonJobRunner is
        // actually subscribed to.
        RegisterJobsConfiguration()
        provides<JobQueue>(name = "contentQueue", singleton = true) { contentNatsJobQueue }
        provides<JobQueue>(
            name = JobQueueNames.commonJobQueue,
            singleton = true,
            overrideExisting = true,
        ) { commonNatsJobQueue }

        val collectionRepository = CollectionRepositoryImpl()
        collectionJobHistoryRepository = CollectionJobHistoryRepositoryImpl()
        collectionJobHistoryService = CollectionJobHistoryServiceImpl(
            collectionJobHistoryRepository,
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
            mockk(relaxed = true),
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

        metadataRepository = MetadataRepositoryImpl()
        metadataJobHistoryRepository = MetadataJobHistoryRepositoryImpl()
        metadataJobHistoryService = MetadataJobHistoryServiceImpl(
            metadataJobHistoryRepository,
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
            mockk(relaxed = true),
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

        val transitionService = TransitionServiceImpl(TransitionRepositoryImpl())
        val stateService = StateServiceImpl(StateRepositoryImpl())

        val collectionPermissionEvaluator = CollectionPermissionEvaluator(collectionService, securityService, groupEvaluator)
        val metadataPermissionEvaluator = MetadataPermissionEvaluator(metadataService, securityService, groupEvaluator)

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

        provides<CollectionService>(singleton = true) { collectionService }
        provides<MetadataService>(singleton = true) { metadataService }
        provides<Transitioner>(singleton = true) { transitioner }

        // Transition-executor enqueuers (used for the parent `transition-metadata`
        // / `transition-collection` child that MultiJob acquires via the
        // initializer in Transitioner.transition).
        provides<JobConfigurationEnqueuer>(name = "transition-collection", singleton = true) {
            CollectionTransitionExecutorConfigurationEnqueuer()
        }
        provides<JobConfigurationEnqueuer>(name = "transition-metadata", singleton = true) {
            MetadataTransitionExecutorConfigurationEnqueuer()
        }
        // Enqueuers for every child job named in the multi-job configuration;
        // MultiJobExecutor.execute() resolves each by `name` to build the
        // fan-out, so all four must be registered even though only two fire
        // for any given item type.
        provides<JobConfigurationEnqueuer>(name = "set-metadata-status", singleton = true) {
            SetMetadataStatusJobExecutorConfigurationEnqueuer()
        }
        provides<JobConfigurationEnqueuer>(name = "set-collection-status", singleton = true) {
            SetCollectionStatusJobExecutorConfigurationEnqueuer()
        }
        provides<JobConfigurationEnqueuer>(name = "auto-assign-collections", singleton = true) {
            AutoAssignCollectionsExecutorConfigurationEnqueuer()
        }

        // Executor instances — resolved by class from the DI registry when
        // JobRunner.process() constructs the executor for a dequeued job.
        provides<CollectionTransitionExecutor> {
            CollectionTransitionExecutor(collectionService, collectionJobHistoryService, transitioner, securityService)
        }
        provides<MetadataTransitionExecutor> {
            MetadataTransitionExecutor(metadataService, metadataJobHistoryService, transitioner, securityService)
        }
        provides<SetMetadataStatusJobExecutor> {
            SetMetadataStatusJobExecutor(metadataService)
        }
        provides<SetCollectionStatusJobExecutor> {
            SetCollectionStatusJobExecutor(collectionService)
        }
        provides<AutoAssignCollectionsExecutor> {
            AutoAssignCollectionsExecutor(collectionService, metadataService, configurationService, slugService, testJson)
        }

        // Listener that closes out the history row when the parent transition
        // job (multi-job or otherwise) reaches fully-complete. In production
        // this is registered via @Provider on content/Configuration; the
        // test wires it up manually since it doesn't run the full DI
        // bootstrap.
        provides<UpdateContentJobHistoryOnCompleteListener> {
            UpdateContentJobHistoryOnCompleteListener(
                metadataJobHistoryService, collectionJobHistoryService,
                metadataService, collectionService, securityService, transitioner,
            )
        }

        // Install the multi-job configuration on the `published` and `advertised`
        // states so the DEFAULT phase of these transitions dispatches through
        // MultiJob — mirroring production, where transition finalization happens
        // in UpdateContentJobHistoryOnCompleteListener rather than the fallback
        // transition executors.
        configureStateMultiJob("published")
        configureStateMultiJob("advertised")

        commonJobRunner.run()
        contentJobRunner.run()
    }

    @AfterTest
    fun teardown() = runBlocking<Unit> {
        if (::commonJobRunner.isInitialized) commonJobRunner.shutdown()
        if (::contentJobRunner.isInitialized) contentJobRunner.shutdown()
        if (::natsPool.isInitialized) natsPool.close()
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

    /**
     * Writes the multi-job configuration onto a state row and sets
     * `job_name = 'multi-job'` so the DEFAULT transition phase routes
     * through `MultiJobExecutor` instead of the plain `transition-metadata`
     * fallback.
     */
    private suspend fun configureStateMultiJob(stateId: String) {
        val cm = ConnectionManager(connectionPool)
        withContext(cm.asCoroutineContext()) {
            cm.beginTransaction()
            cm.useStatement(
                "UPDATE states SET job_name = ?, configuration = ?::jsonb WHERE id = ?"
            ) { stmt ->
                stmt.setString(1, "multi-job")
                stmt.setString(2, publishedMultiJobConfiguration)
                stmt.setString(3, stateId)
                stmt.execute()
            }
            withContext(NonCancellable) { cm.commitTransaction() }
        }
        withContext(NonCancellable) { cm.release() }
    }

    private suspend fun rawUpdate(sql: String, id: UUID) {
        val cm = ConnectionManager(connectionPool)
        withContext(cm.asCoroutineContext()) {
            cm.beginTransaction()
            cm.useStatement(sql) { stmt ->
                stmt.setObject(1, id.toJavaUuid())
                stmt.execute()
            }
            withContext(NonCancellable) { cm.commitTransaction() }
        }
        withContext(NonCancellable) { cm.release() }
    }

    private suspend fun rawUpdateState(id: UUID, state: String) {
        val cm = ConnectionManager(connectionPool)
        withContext(cm.asCoroutineContext()) {
            cm.beginTransaction()
            cm.useStatement(
                "UPDATE metadata SET workflow_state_id = ?, workflow_state_pending_id = null, workflow_state_valid = null WHERE id = ?"
            ) { stmt ->
                stmt.setString(1, state)
                stmt.setObject(2, id.toJavaUuid())
                stmt.execute()
            }
            withContext(NonCancellable) { cm.commitTransaction() }
        }
        withContext(NonCancellable) { cm.release() }
    }

    private fun createAuthContext(): ImpersonatedAuthenticationContext {
        return ImpersonatedAuthenticationContext(saPrincipal, saGroups)
    }

    /**
     * Seeds a metadata row directly into `draft` state with `ready` populated
     * — skipping the pending -> processing -> draft dance that the normal
     * setReady path drives. Bypassing the lifecycle keeps each test focused
     * on the publish transition, not the bootstrap.
     */
    private suspend fun insertReadyMetadata(
        name: String,
        attributes: JsonObject = JsonObject(emptyMap()),
    ): Metadata {
        val inserted = withRequest {
            metadataRepository.add(
                Metadata(
                    name = name,
                    type = MetadataType.STANDARD,
                    contentType = "text/plain",
                    contentLength = null,
                    languageTag = "en",
                    workflowStateId = "pending",
                    attributes = attributes,
                )
            )
        }
        rawUpdateState(inserted.id, "draft")
        rawUpdate("UPDATE metadata SET ready = now() WHERE id = ?", inserted.id)
        return withRequest {
            metadataService.removeFromCache(inserted.id, inserted.version)
            metadataService.getById(inserted.id, inserted.version) ?: error("missing metadata")
        }
    }

    private suspend fun insertReadyCollection(
        name: String,
        attributes: JsonObject = JsonObject(emptyMap()),
    ): Collection {
        val created = withRequest {
            collectionService.add(
                CollectionInput(name = name, attributes = attributes),
                parent = null,
                parentItemAttributes = null
            )
        }
        rawUpdate(
            "UPDATE collections SET workflow_state_id = 'draft', " +
                "workflow_state_pending_id = null, workflow_state_valid = null, " +
                "ready = now() WHERE id = ?",
            created.id
        )
        return withRequest {
            collectionService.removeFromCache(created.id)
            collectionService.getById(created.id) ?: error("missing collection")
        }
    }

    private suspend fun waitForMetadata(
        id: UUID,
        version: Int,
        expectedState: String,
        expectedPending: String?,
        timeout: kotlin.time.Duration = 45.seconds
    ): Metadata {
        val deadline = Clock.System.now() + timeout
        while (Clock.System.now() < deadline) {
            val metadata = withRequest {
                metadataService.removeFromCache(id, version)
                metadataService.getById(id, version)
            }
            if (metadata != null
                && metadata.workflowStateId == expectedState
                && metadata.workflowStatePendingId == expectedPending
            ) {
                return metadata
            }
            delay(500)
        }
        val current = withRequest {
            metadataService.removeFromCache(id, version)
            metadataService.getById(id, version)
        }
        val history = withRequest { metadataJobHistoryRepository.getHistory(id, version) }
        error(
            "Timed out waiting for metadata state='$expectedState', pending='$expectedPending'. " +
                "Current: state=${current?.workflowStateId}, pending=${current?.workflowStatePendingId}, " +
                "valid=${current?.workflowStateValid}. " +
                "Job history (${history.size}): " +
                history.joinToString("; ") {
                    "${it.jobName} status=${it.status} complete=${it.complete} success=${it.success}"
                }
        )
    }

    private suspend fun waitForCollection(
        id: UUID,
        expectedState: String,
        expectedPending: String?,
        timeout: kotlin.time.Duration = 45.seconds
    ): Collection {
        val deadline = Clock.System.now() + timeout
        while (Clock.System.now() < deadline) {
            val collection = withRequest {
                collectionService.removeFromCache(id)
                collectionService.getById(id)
            }
            if (collection != null
                && collection.workflowStateId == expectedState
                && collection.workflowStatePendingId == expectedPending
            ) {
                return collection
            }
            delay(500)
        }
        val current = withRequest {
            collectionService.removeFromCache(id)
            collectionService.getById(id)
        }
        val history = withRequest { collectionJobHistoryRepository.getHistory(id) }
        error(
            "Timed out waiting for collection state='$expectedState', pending='$expectedPending'. " +
                "Current: state=${current?.workflowStateId}, pending=${current?.workflowStatePendingId}, " +
                "valid=${current?.workflowStateValid}. " +
                "Job history (${history.size}): " +
                history.joinToString("; ") {
                    "${it.jobName} status=${it.status} complete=${it.complete} success=${it.success}"
                }
        )
    }

    /**
     * Polls the metadata job-history table until every row has `complete`
     * populated (i.e. `getActiveJobs` is empty). Returns the full history
     * list so callers can assert which jobs actually ran.
     *
     * This is the core of the bug: the admin UI shows a job as "in flight"
     * while `complete IS NULL`, and the multi-job parent row has historically
     * been left in that state forever because nobody wires its completion
     * back into the history service.
     */
    private suspend fun waitForAllMetadataJobsComplete(
        id: UUID,
        version: Int,
        timeout: kotlin.time.Duration = 30.seconds
    ): List<MetadataJobHistory> {
        val deadline = Clock.System.now() + timeout
        while (Clock.System.now() < deadline) {
            val active = withRequest { metadataJobHistoryRepository.getActiveJobs(id, version) }
            if (active.isEmpty()) {
                return withRequest { metadataJobHistoryRepository.getHistory(id, version) }
            }
            delay(500)
        }
        val active = withRequest { metadataJobHistoryRepository.getActiveJobs(id, version) }
        val history = withRequest { metadataJobHistoryRepository.getHistory(id, version) }
        fail(
            "Timed out waiting for all metadata jobs to complete. " +
                "Active jobs still pending: ${active.map { "${it.jobName}(${it.jobId})" }}. " +
                "Full history: ${history.map { "${it.jobName}=${if (it.complete != null) "done" else "pending"}" }}"
        )
    }

    private suspend fun waitForAllCollectionJobsComplete(
        id: UUID,
        timeout: kotlin.time.Duration = 30.seconds
    ) {
        val deadline = Clock.System.now() + timeout
        while (Clock.System.now() < deadline) {
            val active = withRequest { collectionJobHistoryRepository.getActiveJobs(id) }
            if (active.isEmpty()) return
            delay(500)
        }
        val active = withRequest { collectionJobHistoryRepository.getActiveJobs(id) }
        val history = withRequest { collectionJobHistoryRepository.getHistory(id) }
        fail(
            "Timed out waiting for all collection jobs to complete. " +
                "Active jobs still pending: ${active.map { "${it.jobName}(${it.jobId})" }}. " +
                "Full history: ${history.map { "${it.jobName}=${if (it.complete != null) "done" else "pending"}" }}"
        )
    }

    /**
     * Publishes a metadata whose `published` state is configured with the
     * four-job multi-job payload and asserts that:
     *  1. the document reaches `published`,
     *  2. the multi-job's metadata-typed children ran (the `set-metadata-status`
     *     side-effect is observable via `public = true` / `publicContent = true`), and
     *  3. the parent multi-job row in `metadata_job_history` is marked
     *     complete, which is what the admin UI reads.
     */
    @Test
    fun `metadata publish with multi-job configuration marks parent job complete`() = runBlocking<Unit> {
        val metadata = insertReadyMetadata("Multi-Job Publish Metadata")

        withRequest {
            transitioner.beginTransition(
                createAuthContext(),
                BeginTransitionInput(
                    metadataId = metadata.id,
                    version = metadata.version,
                    stateId = "published",
                    status = "Publish with multi-job",
                ),
                metadata
            )
        }

        val published = waitForMetadata(
            id = metadata.id,
            version = metadata.version,
            expectedState = "published",
            expectedPending = null,
        )
        assertEquals("published", published.workflowStateId)
        assertNull(published.workflowStatePendingId)

        val history = waitForAllMetadataJobsComplete(metadata.id, metadata.version)

        // The multi-job entry is the one the admin UI tracks for the publish
        // request; the user's bug report is that this row stays `complete IS NULL`
        // even after every fan-out child has finished.
        val multiJobEntry = history.firstOrNull { it.jobName == "multi-job" }
        assertNotNull(
            multiJobEntry,
            "expected a job_history entry for 'multi-job' on the published transition"
        )
        assertNotNull(
            multiJobEntry.complete,
            "multi-job parent history row must be marked complete once every child finishes, " +
                "otherwise the admin UI shows the publish as stuck forever"
        )
        assertTrue(multiJobEntry.success, "multi-job should succeed when all children succeed")

        // Sanity: the metadata-typed fan-out children actually took effect.
        val refreshed = withRequest {
            metadataService.removeFromCache(metadata.id, metadata.version)
            metadataService.getById(metadata.id, metadata.version) ?: error("missing metadata")
        }
        assertTrue(
            refreshed.public,
            "set-metadata-status child should have flipped public=true"
        )
        assertTrue(
            refreshed.publicContent,
            "set-metadata-status child should have flipped publicContent=true"
        )

        // The admin UI's `metadata` subscription listens on METADATA_UPDATED_CHANNEL
        // and only refetches the workflow (including `activeJobs`) when it
        // receives an event there. The last fan-out child fires its own event
        // before the parent is marked complete, so without the history service
        // publishing on setComplete the UI would debounce off that earlier event
        // and never re-query the now-closed history row. Verify it emits one
        // keyed to the same metadata id/version the parent job was scoped to.
        coVerify {
            pubSubService.publish(
                eq(METADATA_UPDATED_CHANNEL),
                any<kotlinx.serialization.SerializationStrategy<MetadataUpdated>>(),
                match<MetadataUpdated> { it.id == metadata.id && it.version == metadata.version },
            )
        }
    }

    /**
     * Same scenario for a collection, which picks up the collection-typed
     * children (`set-collection-status` and collection-typed `auto-assign-collections`).
     */
    @Test
    fun `collection publish with multi-job configuration marks parent job complete`() = runBlocking<Unit> {
        val collection = insertReadyCollection("Multi-Job Publish Collection")

        withRequest {
            transitioner.beginTransition(
                createAuthContext(),
                BeginTransitionInput(
                    collectionId = collection.id,
                    stateId = "published",
                    status = "Publish with multi-job",
                ),
                collection
            )
        }

        val published = waitForCollection(
            id = collection.id,
            expectedState = "published",
            expectedPending = null,
        )
        assertEquals("published", published.workflowStateId)
        assertNull(published.workflowStatePendingId)

        waitForAllCollectionJobsComplete(collection.id)

        val history = withRequest { collectionJobHistoryRepository.getHistory(collection.id) }
        val multiJobEntry = history.firstOrNull { it.jobName == "multi-job" }
        assertNotNull(
            multiJobEntry,
            "expected a job_history entry for 'multi-job' on the published transition"
        )
        assertNotNull(
            multiJobEntry.complete,
            "multi-job parent history row must be marked complete once every child finishes"
        )
        assertTrue(multiJobEntry.success, "multi-job should succeed when all children succeed")

        val refreshed = withRequest {
            collectionService.removeFromCache(collection.id)
            collectionService.getById(collection.id) ?: error("missing collection")
        }
        assertTrue(
            refreshed.public,
            "set-collection-status child should have flipped public=true"
        )
        assertTrue(
            refreshed.publicList,
            "set-collection-status child should have flipped publicList=true"
        )

        // The admin UI's `collection` subscription listens on COLLECTION_UPDATED_CHANNEL;
        // without the history service publishing on setComplete the UI would
        // debounce off the last fan-out child's own event and never pick up the
        // now-closed multi-job history row.
        coVerify {
            pubSubService.publish(
                eq(COLLECTION_UPDATED_CHANNEL),
                any<kotlinx.serialization.SerializationStrategy<CollectionUpdated>>(),
                match<CollectionUpdated> { it.id == collection.id },
            )
        }
    }

    /**
     * The multi-job variant of the advertised→published chain: with the
     * `advertised` state configured to run `multi-job`, the transition executors
     * never run, so [bosca.content.transition.jobs.UpdateContentJobHistoryOnCompleteListener]
     * must both finalize the advertised transition AND schedule the publish for
     * the item's future `published` epoch. Before that chaining existed on the
     * listener path, items settled in `advertised` with nothing pending and were
     * never published.
     */
    @Test
    fun `metadata with past advertised and future published chains through multi-job advertised`() = runBlocking<Unit> {
        val now = System.currentTimeMillis()
        val advertisedEpoch = now - 60_000L
        val publishedEpoch = now + 24L * 60 * 60 * 1000
        val metadata = insertReadyMetadata(
            "Multi-Job Advertised Chain Metadata",
            JsonObject(
                mapOf(
                    "advertised" to JsonPrimitive(advertisedEpoch),
                    "published" to JsonPrimitive(publishedEpoch),
                )
            )
        )

        withRequest {
            transitioner.beginTransition(
                createAuthContext(),
                BeginTransitionInput(
                    metadataId = metadata.id,
                    version = metadata.version,
                    stateId = "published",
                    status = "Publish with multi-job advertised redirect",
                ),
                metadata
            )
        }

        // The advertised redirect fires immediately (its epoch is in the past);
        // the listener then finalizes advertised and must chain a scheduled
        // publish, leaving the item advertised with "published" pending.
        val settled = waitForMetadata(
            id = metadata.id,
            version = metadata.version,
            expectedState = "advertised",
            expectedPending = "published",
        )
        val stateValid = settled.workflowStateValid
        assertNotNull(stateValid, "publish must be scheduled for the future published epoch")
        assertTrue(stateValid > OffsetDateTime.now(), "publish schedule must be in the future")

        val history = withRequest { metadataJobHistoryRepository.getHistory(metadata.id, metadata.version) }
        assertTrue(
            history.any { it.jobName == "multi-job" && it.delayedUntil != null },
            "the chained publish must be recorded as a delayed multi-job; history: " +
                history.joinToString("; ") { "${it.jobName} delayedUntil=${it.delayedUntil}" }
        )
    }

    /** Collection variant of the multi-job advertised→published chain. */
    @Test
    fun `collection with past advertised and future published chains through multi-job advertised`() = runBlocking<Unit> {
        val now = System.currentTimeMillis()
        val advertisedEpoch = now - 60_000L
        val publishedEpoch = now + 24L * 60 * 60 * 1000
        val collection = insertReadyCollection(
            "Multi-Job Advertised Chain Collection",
            JsonObject(
                mapOf(
                    "advertised" to JsonPrimitive(advertisedEpoch),
                    "published" to JsonPrimitive(publishedEpoch),
                )
            )
        )

        withRequest {
            transitioner.beginTransition(
                createAuthContext(),
                BeginTransitionInput(
                    collectionId = collection.id,
                    stateId = "published",
                    status = "Publish with multi-job advertised redirect",
                ),
                collection
            )
        }

        val settled = waitForCollection(
            id = collection.id,
            expectedState = "advertised",
            expectedPending = "published",
        )
        val stateValid = settled.workflowStateValid
        assertNotNull(stateValid, "publish must be scheduled for the future published epoch")
        assertTrue(stateValid > OffsetDateTime.now(), "publish schedule must be in the future")

        val history = withRequest { collectionJobHistoryRepository.getHistory(collection.id) }
        assertTrue(
            history.any { it.jobName == "multi-job" && it.delayedUntil != null },
            "the chained publish must be recorded as a delayed multi-job; history: " +
                history.joinToString("; ") { "${it.jobName} delayedUntil=${it.delayedUntil}" }
        )
    }
}
