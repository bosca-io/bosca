package bosca.content.collection.service

import bosca.cache.CacheManager
import bosca.cache.RequestCache
import bosca.cache.RequestCacheSerializer
import bosca.cache.RequestCacheSerializerImpl
import bosca.cache.asCoroutineContext
import bosca.cache.nats.NatsCacheManager
import bosca.content.collection.events.CollectionSetReady
import bosca.content.collection.events.dispatch
import bosca.content.collection.model.Collection
import bosca.content.collection.repository.CollectionCategoryRepositoryImpl
import bosca.content.collection.repository.CollectionCollaborationRepositoryImpl
import bosca.content.collection.repository.CollectionFindRepository
import bosca.content.collection.repository.CollectionItemRepositoryImpl
import bosca.content.collection.repository.CollectionLanguageVariantRepositoryImpl
import bosca.content.collection.repository.CollectionMetadataRelationshipRepositoryImpl
import bosca.content.collection.repository.CollectionPermissionRepositoryImpl
import bosca.content.collection.repository.CollectionRepositoryImpl
import bosca.content.collection.repository.CollectionSupplementaryRepositoryImpl
import bosca.content.collection.repository.CollectionTraitRepositoryImpl
import bosca.content.collection.repository.CollectionWorkflowPlanRepositoryImpl
import bosca.content.metadata.repository.CollectionTemplateRepositoryImpl
import bosca.content.metadata.repository.CollectionTemplateAttributeRepositoryImpl
import bosca.content.metadata.repository.MetadataRepositoryImpl
import bosca.content.transition.history.service.TransitionHistoryService
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
import bosca.nats.NatsConnectionPool
import bosca.security.model.Principal
import bosca.security.service.SecurityService
import bosca.serialization.OffsetDateTimeSerializer
import bosca.serialization.UUID
import bosca.serialization.UUIDSerializer
import bosca.slug.service.SlugService
import bosca.storage.service.ObjectStorageService
import io.mockk.coEvery
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.Runs
import io.mockk.unmockkAll
import kotlinx.coroutines.NonCancellable
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
import kotlin.uuid.toJavaUuid

@OptIn(InternalDI::class)
class CollectionServiceCacheEndToEndTest {

    private lateinit var natsContainer: SharedNatsContainer
    private lateinit var postgresContainer: SharedPostgreSQLContainer
    private lateinit var connectionPool: ConnectionPool
    private lateinit var cacheManager: CacheManager
    private lateinit var serializer: RequestCacheSerializer

    private val testJson = Json {
        ignoreUnknownKeys = true
        serializersModule = SerializersModule {
            contextual(OffsetDateTimeSerializer())
            contextual(UUIDSerializer())
        }
    }

    private lateinit var repository: CollectionRepositoryImpl
    private lateinit var service: CollectionServiceImpl

    @BeforeTest
    fun setup() = runBlocking {
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
        val natsPool = natsContainer.newConnectionPool(1)
        cacheManager = NatsCacheManager(natsPool)
        serializer = RequestCacheSerializerImpl(testJson)

        ProviderRegistry.clear()
        provides<CacheManager>(singleton = true) { cacheManager }
        provides<RequestCacheSerializer>(singleton = true) { serializer }
        provides<Json>(singleton = true) { testJson }

        repository = CollectionRepositoryImpl()

        val transitionHistory = mockk<TransitionHistoryService>(relaxed = true)
        val objectService = mockk<ObjectStorageService>(relaxed = true)
        val slugService = mockk<SlugService>(relaxed = true)
        val transitioner = mockk<ObjectProvider<Transitioner>>(relaxed = true)
        val securityService = mockk<ObjectProvider<SecurityService>>(relaxed = true)

        service = CollectionServiceImpl(
            repository,
            CollectionItemRepositoryImpl(),
            CollectionFindRepository(testJson),
            CollectionCategoryRepositoryImpl(),
            CollectionTraitRepositoryImpl(),
            CollectionMetadataRelationshipRepositoryImpl(),
            CollectionSupplementaryRepositoryImpl(),
            CollectionWorkflowPlanRepositoryImpl(),
            CollectionPermissionRepositoryImpl(),
            transitionHistory,
            objectService,
            testJson,
            slugService,
            CollectionCollaborationRepositoryImpl(),
            CollectionLanguageVariantRepositoryImpl(),
            CollectionTemplateRepositoryImpl(),
            CollectionTemplateAttributeRepositoryImpl(),
            MetadataRepositoryImpl(),
            transitioner,
            securityService,
        )
    }

    @AfterTest
    fun teardown() = runBlocking {
        if (::connectionPool.isInitialized) connectionPool.close()
        if (::natsContainer.isInitialized) natsContainer.stop()
        if (::postgresContainer.isInitialized) postgresContainer.stop()
        ProviderRegistry.clear()
    }

    private suspend fun <T> withRequest(block: suspend () -> T): T {
        val cm = ConnectionManager(connectionPool)
        val rc = RequestCache(cacheManager, serializer)
        return withContext(cm.asCoroutineContext() + rc.asCoroutineContext()) {
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

    @Test
    fun `getById caches in NATS and removeFromCache clears it`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val inserted = withRequest {
            repository.add(
                Collection(
                    name = "Test Collection",
                    languageTag = "en",
                    workflowStateId = "pending",
                    attributes = JsonObject(emptyMap()),
                )
            )
        }
        val testId = inserted.id

        // Request 1: getById populates cache
        val result1 = withRequest { service.getById(testId) }
        assertNotNull(result1)
        assertNull(result1.description)

        // Modify DB directly behind cache's back
        rawUpdate("UPDATE collections SET description = 'updated' WHERE id = ?", testId)

        // Request 2: getById should return STALE cached data (proves caching works)
        val result2 = withRequest { service.getById(testId) }
        assertNotNull(result2)
        assertNull(result2.description, "Should return stale cached data")

        // Request 3: removeFromCache inside a transaction, commit triggers flush to NATS
        val cm3 = ConnectionManager(connectionPool)
        val rc3 = RequestCache(cacheManager, serializer)
        withContext(cm3.asCoroutineContext() + rc3.asCoroutineContext()) {
            cm3.beginTransaction()
            service.removeFromCache(testId)
            withContext(NonCancellable) {
                cm3.commitTransaction()
            }
        }
        withContext(NonCancellable) {
            cm3.release()
        }

        // Request 4: getById should miss NATS and call repo, returning updated description
        val result4 = withRequest { service.getById(testId) }
        assertNotNull(result4)
        assertEquals("updated", result4.description, "Should return fresh data after cache invalidation")
    }

    @Test
    fun `setReady invalidates cache so next request sees fresh data`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val inserted = withRequest {
            repository.add(
                Collection(
                    name = "Test Collection",
                    languageTag = "en",
                    workflowStateId = "pending",
                    attributes = JsonObject(emptyMap()),
                )
            )
        }
        val testId = inserted.id

        // Set workflow_state_id to 'draft' to avoid transitioner check in setReady
        rawUpdate("UPDATE collections SET workflow_state_id = 'draft' WHERE id = ?", testId)

        val testPrincipal = Principal(id = UUID.random())

        mockkStatic("bosca.content.collection.events.CollectionSetReadyExtKt")
        coEvery { any<CollectionSetReady>().dispatch() } just Runs

        // Request 1: getById populates cache with ready = null
        val result1 = withRequest { service.getById(testId) }
        assertNotNull(result1)
        assertNull(result1.ready)
        assertEquals("draft", result1.workflowStateId)

        // Request 2: setReady updates ready timestamp, invalidates cache
        withRequest {
            service.setReady(testId, testPrincipal, null)
        }

        // Request 3: getById should see fresh data with ready != null
        val result3 = withRequest { service.getById(testId) }
        assertNotNull(result3)
        assertNotNull(result3.ready, "ready should be set after setReady call")
    }
}
