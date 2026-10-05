package bosca.content.transition

import bosca.cache.CacheManager
import bosca.cache.RequestCache
import bosca.cache.RequestCacheSerializer
import bosca.cache.RequestCacheSerializerImpl
import bosca.cache.asCoroutineContext
import bosca.cache.nats.NatsCacheManager
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
import bosca.content.collection.model.Collection
import bosca.content.collection.model.CollectionInput
import bosca.content.collection.model.ICollection
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
import bosca.content.transition.jobs.CollectionTransitionExecutor
import bosca.content.transition.jobs.CollectionTransitionExecutorConfigurationEnqueuer
import bosca.content.transition.jobs.MetadataTransitionExecutor
import bosca.content.transition.jobs.MetadataTransitionExecutorConfigurationEnqueuer
import bosca.content.transition.model.BeginTransitionInput
import bosca.content.transition.repository.TransitionRepositoryImpl
import bosca.content.transition.service.TransitionServiceImpl
import bosca.content.transition.service.Transitioner
import bosca.content.transition.service.Transitioner.Companion.epochAttribute
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
import bosca.serialization.OffsetDateTimeSerializer
import bosca.serialization.UUID
import bosca.serialization.UUIDSerializer
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
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.toJavaUuid

/**
 * End-to-end coverage of the advertised/published transition formula in
 * [Transitioner.doTransition] and [MetadataTransitionExecutor].
 *
 * Two scenarios drive these tests:
 *
 *  1. **First-pass redirect**: when a draft is published with `advertised`
 *     already in the past and `published` in the future, the transition must
 *     redirect to the "advertised" state immediately and schedule the publish
 *     for the future timestamp.
 *
 *  2. **Recursive guard**: once the content has reached "advertised" state,
 *     a follow-up transition request to "published" must NOT bounce back to
 *     "advertised" (which previously created a loop). The publish should
 *     either be scheduled (when published is in the future) or completed
 *     (when published is in the past).
 *
 * Each test exercises the real Postgres + NATS stack via Testcontainers so
 * the job runner, scheduler, and DB-callback wiring are all on the path —
 * the bug being guarded against was timing/recursion-sensitive and would not
 * surface in a pure unit test.
 */
@OptIn(InternalDI::class)
class AdvertisedPublishTransitionEndToEndTest {

    private lateinit var natsContainer: SharedNatsContainer
    private lateinit var postgresContainer: SharedPostgreSQLContainer
    private lateinit var connectionPool: ConnectionPool
    private lateinit var natsPool: NatsConnectionPool
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
    private lateinit var metadataService: MetadataServiceImpl
    private lateinit var metadataJobHistoryService: MetadataJobHistoryServiceImpl
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
        natsJobQueue = natsJobQueueFactory.create("content")
        jobRunner = JobRunner(natsJobQueue, 10, distributedLockFactory)

        coEvery { securityService.getPrincipalGroups(any<UUID>()) } returns saGroups
        coEvery { securityService.getPrincipalByIdentifier("sa") } returns saPrincipal
        val groupEvaluator = GroupEvaluator(securityService)

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
        metadataJobHistoryService = MetadataJobHistoryServiceImpl(
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

        provides<JobConfigurationEnqueuer>(name = "transition-collection", singleton = true) {
            CollectionTransitionExecutorConfigurationEnqueuer()
        }
        provides<JobConfigurationEnqueuer>(name = "transition-metadata", singleton = true) {
            MetadataTransitionExecutorConfigurationEnqueuer()
        }

        provides<CollectionTransitionExecutor> {
            CollectionTransitionExecutor(collectionService, collectionJobHistoryService, transitioner, securityService)
        }
        provides<MetadataTransitionExecutor> {
            MetadataTransitionExecutor(metadataService, metadataJobHistoryService, transitioner, securityService)
        }

        jobRunner.run()
    }

    @AfterTest
    fun teardown() = runBlocking<Unit> {
        if (::jobRunner.isInitialized) jobRunner.shutdown()
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

    /**
     * Polls until the metadata reaches the requested `state` and `pending` configuration,
     * timing out with a descriptive error so test failures point at the actual stuck state.
     */
    private suspend fun waitForMetadata(
        id: UUID,
        version: Int,
        expectedState: String,
        expectedPending: String?,
        timeout: kotlin.time.Duration = 120.seconds
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
            delay(250.milliseconds)
        }
        // Final check — the state may have changed during the last delay
        val current = withRequest {
            metadataService.removeFromCache(id, version)
            metadataService.getById(id, version)
        }
        if (current != null
            && current.workflowStateId == expectedState
            && current.workflowStatePendingId == expectedPending
        ) {
            return current
        }
        error(
            "Timed out waiting for metadata state='$expectedState', pending='$expectedPending'. " +
                "Current: state=${current?.workflowStateId}, pending=${current?.workflowStatePendingId}"
        )
    }

    /**
     * Inserts a metadata row directly (skipping the pending → processing → draft
     * dance) so each test starts from a known workflow state. The repository's
     * `add` query ignores the [Metadata.workflowStateId] field (it relies on the
     * DB default of "pending"), so we explicitly UPDATE the row to the requested
     * state and mark it ready before returning.
     *
     * After raw DB updates, we call [MetadataServiceImpl.removeFromCache] so
     * the next [MetadataService.getById] (e.g. by the worker that picks up the
     * scheduled transition job) reads the updated row instead of stale cached
     * metadata that still shows the initial inserted state.
     */
    private suspend fun insertReadyMetadata(
        name: String,
        attributes: JsonObject,
        workflowStateId: String,
    ): Metadata {
        val inserted = withRequest {
            metadataRepository.add(
                Metadata(
                    name = name,
                    type = MetadataType.STANDARD,
                    contentType = "text/plain",
                    contentLength = null,
                    languageTag = "en",
                    workflowStateId = "pending", // overwritten below
                    attributes = attributes,
                )
            )
        }
        rawUpdateState(inserted.id, workflowStateId)
        rawUpdate("UPDATE metadata SET ready = now() WHERE id = ?", inserted.id)
        return withRequest {
            metadataService.removeFromCache(inserted.id, inserted.version)
            metadataService.getById(inserted.id, inserted.version) ?: error("missing metadata")
        }
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
            withContext(NonCancellable) {
                cm.commitTransaction()
            }
        }
        withContext(NonCancellable) {
            cm.release()
        }
    }

    /**
     * Reproduces the user-reported scenario: a draft document with `advertised`
     * set to the past and `published` set well in the future. Clicking publish
     * should redirect into the "advertised" state immediately, then schedule the
     * publish for the future timestamp — *not* publish straight away.
     *
     * This test uses the natural pending → processing → draft path via setReady
     * to mirror what the editor actually does, then triggers the publish.
     */
    @Test
    fun `draft with future published and past advertised redirects through advertised`() = runBlocking<Unit> {
        val now = System.currentTimeMillis()
        val advertisedEpoch = now - 60_000L                  // 1 minute ago
        val publishedEpoch = now + 24L * 60 * 60 * 1000      // 1 day from now

        // Create in pending state (default), then setReady drives it to draft
        // via the real job system — same path the editor exercises.
        val inserted = withRequest {
            metadataRepository.add(
                Metadata(
                    name = "Draft Publish Schedule Test",
                    type = MetadataType.STANDARD,
                    contentType = "text/plain",
                    contentLength = null,
                    languageTag = "en",
                    workflowStateId = "pending",
                    attributes = JsonObject(
                        mapOf(
                            "advertised" to JsonPrimitive(advertisedEpoch),
                            "published" to JsonPrimitive(publishedEpoch),
                        )
                    ),
                )
            )
        }

        withRequest { metadataService.setReady(inserted, saPrincipal) }

        val draft = waitForMetadata(
            id = inserted.id,
            version = inserted.version,
            expectedState = "draft",
            expectedPending = null,
        )

        withRequest {
            transitioner.beginTransition(
                createAuthContext(),
                BeginTransitionInput(
                    metadataId = draft.id,
                    version = draft.version,
                    stateId = "published",
                    status = "User clicks publish",
                ),
                draft
            )
        }

        // After the immediate transition completes (transition-metadata runs
        // because the advertised epoch is in the past), the document should
        // have advanced to "advertised" with "published" pending and a
        // future stateValid that defers the publish.
        val settled = waitForMetadata(
            id = draft.id,
            version = draft.version,
            expectedState = "advertised",
            expectedPending = "published",
        )
        val stateValid = settled.workflowStateValid
        assertNotNull(stateValid, "publish must be scheduled for the future, not committed immediately")
        assertTrue(
            stateValid.toInstant().toEpochMilli() > System.currentTimeMillis(),
            "stateValid should be in the future, was $stateValid"
        )
    }

    /**
     * Recursive-guard scenario: the document is already in "advertised" state
     * (which is what `MetadataTransitionExecutor` leaves behind after the first
     * pass) and a follow-up `beginTransition(stateId="published")` is issued.
     *
     * Without the guard added in `Transitioner.doTransition`, the redirect block
     * would see `advertised < published` and bounce the request back to the
     * "advertised" state, looping forever via the recursive call from the
     * executor. With the guard, the advertised redirect is skipped and the
     * publish is scheduled normally.
     */
    @Test
    fun `transitioning from advertised to published does not redirect back to advertised`() = runBlocking<Unit> {
        val now = System.currentTimeMillis()
        val advertisedEpoch = now - 5L * 60 * 1000           // 5 minutes ago
        val publishedEpoch = now + 24L * 60 * 60 * 1000      // 1 day from now

        val metadata = insertReadyMetadata(
            name = "Already Advertised Test",
            attributes = JsonObject(
                mapOf(
                    "advertised" to JsonPrimitive(advertisedEpoch),
                    "published" to JsonPrimitive(publishedEpoch),
                )
            ),
            workflowStateId = "advertised",
        )

        // Pass stateValid=null to mimic what `MetadataTransitionExecutor` would
        // do if it ever called beginTransition without a precomputed delay.
        withRequest {
            transitioner.beginTransition(
                createAuthContext(),
                BeginTransitionInput(
                    metadataId = metadata.id,
                    version = metadata.version,
                    stateId = "published",
                    stateValid = null,
                    status = "Recursive publish from advertised",
                ),
                metadata
            )
        }

        val settled = waitForMetadata(
            id = metadata.id,
            version = metadata.version,
            expectedState = "advertised",
            expectedPending = "published",
        )
        val stateValid = settled.workflowStateValid
        assertNotNull(
            stateValid,
            "publish should be scheduled for the future via the published epoch fallback"
        )
        assertTrue(
            stateValid.toInstant().toEpochMilli() > System.currentTimeMillis(),
            "stateValid should be in the future, was $stateValid"
        )
        assertEquals(
            "advertised", settled.workflowStateId,
            "state must remain advertised; the redirect bug would have flipped pending back to advertised"
        )
    }

    /**
     * Same recursive-guard scenario as above, but with the `published` epoch
     * stored as a JSON STRING rather than a JSON number. This is the form the
     * editor's date input can persist when invalid intermediate text is committed
     * before a valid date is typed (see attribute.ts forceSetDateTimeValue catch).
     *
     * Without the [Transitioner.Companion.epochAttribute] helper's string fallback,
     * `MetadataTransitionExecutor` would parse the value as null, recurse with
     * `stateValid=null`, and the redirect guard would be the only thing keeping the
     * workflow from looping.
     */
    @Test
    fun `string encoded published epoch still schedules the publish`() = runBlocking<Unit> {
        val now = System.currentTimeMillis()
        val advertisedEpoch = now - 5L * 60 * 1000
        val publishedEpoch = now + 24L * 60 * 60 * 1000

        val metadata = insertReadyMetadata(
            name = "String Encoded Epoch Test",
            attributes = JsonObject(
                mapOf(
                    "advertised" to JsonPrimitive(advertisedEpoch),
                    // Stored as a JSON string instead of JSON number to simulate
                    // the editor's invalid-input fallback path.
                    "published" to JsonPrimitive(publishedEpoch.toString()),
                )
            ),
            workflowStateId = "advertised",
        )

        withRequest {
            transitioner.beginTransition(
                createAuthContext(),
                BeginTransitionInput(
                    metadataId = metadata.id,
                    version = metadata.version,
                    stateId = "published",
                    stateValid = null,
                    status = "Recursive publish with string-encoded published",
                ),
                metadata
            )
        }

        val settled = waitForMetadata(
            id = metadata.id,
            version = metadata.version,
            expectedState = "advertised",
            expectedPending = "published",
        )
        val stateValid = settled.workflowStateValid
        assertNotNull(
            stateValid,
            "string-encoded published epoch must still be parsed and used to schedule the publish"
        )
        assertTrue(
            stateValid.toInstant().toEpochMilli() > System.currentTimeMillis(),
            "stateValid should be in the future, was $stateValid"
        )
    }

    /**
     * A draft transition where the user explicitly opts out of advertising
     * (no "advertised" attribute) and just sets a future publish date. The
     * publish should be scheduled, with no advertised redirect.
     */
    @Test
    fun `draft with only future published schedules publish without advertised redirect`() = runBlocking<Unit> {
        val publishedEpoch = System.currentTimeMillis() + 24L * 60 * 60 * 1000

        val metadata = insertReadyMetadata(
            name = "Future Publish Only Test",
            attributes = JsonObject(
                mapOf("published" to JsonPrimitive(publishedEpoch))
            ),
            workflowStateId = "draft",
        )

        withRequest {
            transitioner.beginTransition(
                createAuthContext(),
                BeginTransitionInput(
                    metadataId = metadata.id,
                    version = metadata.version,
                    stateId = "published",
                    status = "Future publish, no advertised",
                ),
                metadata
            )
        }

        val settled = waitForMetadata(
            id = metadata.id,
            version = metadata.version,
            expectedState = "draft",
            expectedPending = "published",
        )
        val stateValid = settled.workflowStateValid
        assertNotNull(stateValid)
        assertTrue(
            stateValid.toInstant().toEpochMilli() > System.currentTimeMillis(),
            "stateValid should be in the future, was $stateValid"
        )
        assertNull(
            settled.attributes.epochAttribute("advertised"),
            "no advertised attribute was set; sanity-check the test fixture"
        )
    }

    // ---------------------------------------------------------------
    // Collection advertised/published flow
    // ---------------------------------------------------------------

    /**
     * Polls until the collection reaches the requested `state` and `pending` configuration,
     * timing out with a descriptive error so test failures point at the actual stuck state.
     */
    private suspend fun waitForCollection(
        id: UUID,
        expectedState: String,
        expectedPending: String?,
        timeout: kotlin.time.Duration = 120.seconds
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
        val current = withRequest { collectionService.getById(id) }
        error(
            "Timed out waiting for collection state='$expectedState', pending='$expectedPending'. " +
                "Current: state=${current?.workflowStateId}, pending=${current?.workflowStatePendingId}"
        )
    }

    /**
     * Helper to insert a collection with given attributes and force it into the
     * specified workflow state via a raw DB update, bypassing the normal
     * pending -> processing -> draft lifecycle.
     */
    private suspend fun insertReadyCollection(
        name: String,
        attributes: JsonObject,
        workflowStateId: String,
    ): Collection {
        val created = withRequest {
            collectionService.add(
                CollectionInput(name = name, attributes = attributes),
                parent = null,
                parentItemAttributes = null
            )
        }
        rawUpdate(
            "UPDATE collections SET workflow_state_id = '$workflowStateId', " +
                "workflow_state_pending_id = null, workflow_state_valid = null, " +
                "ready = now() WHERE id = ?",
            created.id
        )
        return withRequest {
            collectionService.removeFromCache(created.id)
            collectionService.getById(created.id) ?: error("missing collection")
        }
    }

    /**
     * Collection with advertised in the past and published in the future.
     * The transition should redirect through "advertised" just as it does
     * for metadata, then schedule the publish for the future timestamp.
     */
    @Test
    fun `collection with future published and past advertised redirects through advertised`() = runBlocking<Unit> {
        val now = System.currentTimeMillis()
        val advertisedEpoch = now - 60_000L
        val publishedEpoch = now + 24L * 60 * 60 * 1000

        val collection = insertReadyCollection(
            name = "Collection Advertised Redirect Test",
            attributes = JsonObject(
                mapOf(
                    "advertised" to JsonPrimitive(advertisedEpoch),
                    "published" to JsonPrimitive(publishedEpoch),
                )
            ),
            workflowStateId = "draft",
        )

        withRequest {
            transitioner.beginTransition(
                createAuthContext(),
                BeginTransitionInput(
                    collectionId = collection.id,
                    stateId = "published",
                    status = "Collection publish with advertised redirect",
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
        assertNotNull(stateValid, "publish must be scheduled for the future")
        assertTrue(
            stateValid.toInstant().toEpochMilli() > System.currentTimeMillis(),
            "stateValid should be in the future, was $stateValid"
        )
    }

    // ---------------------------------------------------------------
    // Metadata: no advertised set should still publish normally
    // ---------------------------------------------------------------

    /**
     * When no "advertised" attribute is set and published is in the past,
     * the metadata should transition directly to "published" without any
     * redirect through the "advertised" state.
     */
    @Test
    fun `metadata without advertised attribute publishes directly`() = runBlocking<Unit> {
        val metadata = insertReadyMetadata(
            name = "No Advertised Direct Publish Test",
            attributes = JsonObject(emptyMap()),
            workflowStateId = "draft",
        )

        withRequest {
            transitioner.beginTransition(
                createAuthContext(),
                BeginTransitionInput(
                    metadataId = metadata.id,
                    version = metadata.version,
                    stateId = "published",
                    status = "Direct publish, no dates",
                ),
                metadata
            )
        }

        // With no advertised or published epoch attributes, the transition
        // should proceed immediately to published state (no scheduling).
        val settled = waitForMetadata(
            id = metadata.id,
            version = metadata.version,
            expectedState = "published",
            expectedPending = null,
        )
        assertEquals("published", settled.workflowStateId)
    }

    // ---------------------------------------------------------------
    // Metadata: advertised in the FUTURE
    // ---------------------------------------------------------------

    /**
     * When advertised is in the future (not past) and published is further
     * in the future, the transition should schedule a delayed job for the
     * advertised timestamp (redirect to "advertised" state with a future
     * stateValid).
     */
    @Test
    fun `metadata with future advertised schedules advertised for future time`() = runBlocking<Unit> {
        val now = System.currentTimeMillis()
        val advertisedEpoch = now + 30L * 60 * 1000      // 30 minutes from now
        val publishedEpoch = now + 24L * 60 * 60 * 1000   // 1 day from now

        val metadata = insertReadyMetadata(
            name = "Future Advertised Schedule Test",
            attributes = JsonObject(
                mapOf(
                    "advertised" to JsonPrimitive(advertisedEpoch),
                    "published" to JsonPrimitive(publishedEpoch),
                )
            ),
            workflowStateId = "draft",
        )

        withRequest {
            transitioner.beginTransition(
                createAuthContext(),
                BeginTransitionInput(
                    metadataId = metadata.id,
                    version = metadata.version,
                    stateId = "published",
                    status = "Future advertised scheduling test",
                ),
                metadata
            )
        }

        // The transition should redirect to "advertised" with stateValid
        // set to the future advertised epoch.
        val settled = waitForMetadata(
            id = metadata.id,
            version = metadata.version,
            expectedState = "draft",
            expectedPending = "advertised",
        )
        val stateValid = settled.workflowStateValid
        assertNotNull(stateValid, "advertised must be scheduled for the future")
        assertTrue(
            stateValid.toInstant().toEpochMilli() > System.currentTimeMillis(),
            "stateValid should be in the future, was $stateValid"
        )
    }

    // ---------------------------------------------------------------
    // Metadata: advertised AFTER published (should skip advertised)
    // ---------------------------------------------------------------

    /**
     * When advertised >= published, the advertised redirect is skipped
     * entirely. The transition should proceed as if only published was set.
     * With published in the future, it should schedule the publish for
     * the published timestamp.
     */
    @Test
    fun `metadata with advertised after published skips advertised redirect`() = runBlocking<Unit> {
        val now = System.currentTimeMillis()
        val publishedEpoch = now + 24L * 60 * 60 * 1000    // 1 day from now
        val advertisedEpoch = publishedEpoch + 60_000L      // advertised AFTER published

        val metadata = insertReadyMetadata(
            name = "Advertised After Published Test",
            attributes = JsonObject(
                mapOf(
                    "advertised" to JsonPrimitive(advertisedEpoch),
                    "published" to JsonPrimitive(publishedEpoch),
                )
            ),
            workflowStateId = "draft",
        )

        withRequest {
            transitioner.beginTransition(
                createAuthContext(),
                BeginTransitionInput(
                    metadataId = metadata.id,
                    version = metadata.version,
                    stateId = "published",
                    status = "Advertised after published, should skip redirect",
                ),
                metadata
            )
        }

        // advertised >= published means getEffectiveAdvertisedEpoch returns null,
        // so no redirect to "advertised". The published delay should be scheduled.
        val settled = waitForMetadata(
            id = metadata.id,
            version = metadata.version,
            expectedState = "draft",
            expectedPending = "published",
        )
        val stateValid = settled.workflowStateValid
        assertNotNull(stateValid, "publish must be scheduled for the future")
        assertTrue(
            stateValid.toInstant().toEpochMilli() > System.currentTimeMillis(),
            "stateValid should be in the future, was $stateValid"
        )
        // Crucially, pending should be "published", NOT "advertised"
        assertEquals("published", settled.workflowStatePendingId)
    }

    /**
     * When advertised equals published exactly, the advertised redirect is
     * also skipped (getEffectiveAdvertisedEpoch returns null for equal values).
     */
    @Test
    fun `metadata with advertised equal to published skips advertised redirect`() = runBlocking<Unit> {
        val now = System.currentTimeMillis()
        val epoch = now + 24L * 60 * 60 * 1000  // same value for both

        val metadata = insertReadyMetadata(
            name = "Advertised Equals Published Test",
            attributes = JsonObject(
                mapOf(
                    "advertised" to JsonPrimitive(epoch),
                    "published" to JsonPrimitive(epoch),
                )
            ),
            workflowStateId = "draft",
        )

        withRequest {
            transitioner.beginTransition(
                createAuthContext(),
                BeginTransitionInput(
                    metadataId = metadata.id,
                    version = metadata.version,
                    stateId = "published",
                    status = "Equal advertised and published",
                ),
                metadata
            )
        }

        val settled = waitForMetadata(
            id = metadata.id,
            version = metadata.version,
            expectedState = "draft",
            expectedPending = "published",
        )
        assertEquals("published", settled.workflowStatePendingId)
        assertNotNull(settled.workflowStateValid)
    }

    // ---------------------------------------------------------------
    // Metadata: advertised -> published completes end-to-end (short delay)
    // ---------------------------------------------------------------

    /**
     * Verifies the complete advertised -> published flow with a very short
     * published delay (2 seconds). After the document reaches "advertised"
     * state, the scheduled transition should fire and the document should
     * eventually reach "published" state.
     *
     * This exercises the full round-trip: draft -> advertised (immediate) ->
     * published (after 2s delay), validating that
     * [MetadataTransitionExecutor]'s recursive call to beginTransition
     * does not loop and actually completes the publish.
     */
    @Test
    fun `advertised to published completes end to end with short delay`() = runBlocking<Unit> {
        val now = System.currentTimeMillis()
        val advertisedEpoch = now - 60_000L               // 1 minute ago
        val publishedEpoch = now + 10_000L                 // 10 seconds from now

        val metadata = insertReadyMetadata(
            name = "Advertised To Published E2E Test",
            attributes = JsonObject(
                mapOf(
                    "advertised" to JsonPrimitive(advertisedEpoch),
                    "published" to JsonPrimitive(publishedEpoch),
                )
            ),
            workflowStateId = "draft",
        )

        withRequest {
            transitioner.beginTransition(
                createAuthContext(),
                BeginTransitionInput(
                    metadataId = metadata.id,
                    version = metadata.version,
                    stateId = "published",
                    status = "End-to-end advertised to published",
                ),
                metadata
            )
        }

        // First: should redirect to "advertised" with "published" pending
        val advertised = waitForMetadata(
            id = metadata.id,
            version = metadata.version,
            expectedState = "advertised",
            expectedPending = "published",
        )
        assertEquals("advertised", advertised.workflowStateId)
        assertEquals("published", advertised.workflowStatePendingId)

        // Then: the scheduled publish should complete within a reasonable time.
        // The published epoch is 10s from test start; leave the timeout at
        // the 120s default so this test is not flaky when the full suite
        // contends for worker threads and NATS pulls under load (observed
        // failures at the old 60s budget had the scheduled job firing
        // correctly but the transition-metadata executor didn't finish
        // flushing state before the wait expired).
        val published = waitForMetadata(
            id = metadata.id,
            version = metadata.version,
            expectedState = "published",
            expectedPending = null,
        )
        assertEquals("published", published.workflowStateId)
        assertNull(published.workflowStatePendingId)
    }
}
