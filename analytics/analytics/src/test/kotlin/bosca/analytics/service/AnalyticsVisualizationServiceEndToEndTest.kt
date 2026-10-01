@file:OptIn(ExperimentalUuidApi::class, InternalDI::class)

package bosca.analytics.service

import bosca.analytics.model.AnalyticsQueryInput
import bosca.analytics.model.AnalyticsVisualizationInput
import bosca.analytics.model.AnalyticsVisualizationType
import bosca.analytics.repository.AnalyticsVisualizationPermissionRepositoryImpl
import bosca.analytics.repository.AnalyticsVisualizationRepositoryImpl
import bosca.analytics.repository.QueryDefinitionRepositoryImpl
import bosca.analytics.repository.QueryParameterRepositoryImpl
import bosca.analytics.repository.QueryPermissionRepositoryImpl
import bosca.cache.CacheManager
import bosca.cache.RequestCache
import bosca.cache.RequestCacheSerializer
import bosca.cache.RequestCacheSerializerImpl
import bosca.cache.asCoroutineContext
import bosca.cache.nats.NatsCacheManager
import bosca.db.ConnectionConfig
import bosca.db.ConnectionFactoryImpl
import bosca.db.ConnectionManager
import bosca.db.ConnectionPool
import bosca.db.asCoroutineContext
import bosca.db.migrations.CoreMigration
import bosca.db.migrations.FlywayMigration
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.events.DisabledEventManagerFilter
import bosca.events.EventManager
import bosca.events.asCoroutineContext
import bosca.nats.NatsConnectionPool
import bosca.serialization.OffsetDateTimeSerializer
import bosca.serialization.UUIDSerializer
import io.mockk.unmockkAll
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import bosca.test.resources.SharedPostgreSQLContainer
import bosca.test.resources.SharedNatsContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.lifecycle.Startables
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi

class AnalyticsVisualizationServiceEndToEndTest {

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

    private lateinit var service: AnalyticsVisualizationServiceImpl
    private lateinit var queryService: AnalyticsQueryServiceImpl

    @BeforeTest
    fun setup() = runBlocking {
        unmockkAll()

        natsContainer = SharedNatsContainer()
            .withExposedPorts(4222)
            .withCommand("-js")
            .withReuse(true)
            .waitingFor(Wait.forListeningPort())

        postgresContainer = SharedPostgreSQLContainer()
            .withExposedPorts(5432)
            .withEnv("POSTGRES_USER", "test")
            .withEnv("POSTGRES_PASSWORD", "test")
            .withEnv("POSTGRES_DB", "test")
            .withReuse(true)
            .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\s", 2))

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

        service = AnalyticsVisualizationServiceImpl(
            AnalyticsVisualizationRepositoryImpl(),
            AnalyticsVisualizationPermissionRepositoryImpl()
        )

        queryService = AnalyticsQueryServiceImpl(
            QueryDefinitionRepositoryImpl(),
            QueryParameterRepositoryImpl(),
            QueryPermissionRepositoryImpl(),
            MissingSourceRefServiceProvider,
            TestResultCacheServiceProvider(testJson),
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

    @Test
    fun `addVisualization creates visualization and getVisualizationById retrieves it`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val input = AnalyticsVisualizationInput(
            key = "test-viz",
            name = "Test Visualization",
            description = "A test bar chart",
            type = AnalyticsVisualizationType.BAR,
            configuration = JsonObject(mapOf("xAxis" to JsonPrimitive("date")))
        )

        val created = withRequest { service.addVisualization(input) }

        assertNotNull(created.id)
        assertEquals("test-viz", created.key)
        assertEquals("Test Visualization", created.name)
        assertEquals("A test bar chart", created.description)
        assertEquals(AnalyticsVisualizationType.BAR, created.type)
        assertNull(created.queryId)

        val fetched = withRequest { service.getVisualizationById(created.id) }
        assertEquals(created.id, fetched.id)
        assertEquals("Test Visualization", fetched.name)
        assertEquals(AnalyticsVisualizationType.BAR, fetched.type)
    }

    @Test
    fun `addVisualization with queryId links to existing query`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val query = withRequest {
            queryService.addQuery(
                AnalyticsQueryInput(
                    key = "viz-linked-query",
                    name = "Linked Query",
                    description = "Query for visualization",
                    query = "SELECT count(*) FROM events"
                )
            )
        }

        val input = AnalyticsVisualizationInput(
            key = "viz-with-query",
            name = "Viz With Query",
            description = "Visualization linked to a query",
            queryId = query.id,
            type = AnalyticsVisualizationType.LINE,
            configuration = JsonNull
        )

        val created = withRequest { service.addVisualization(input) }

        assertNotNull(created.queryId)
        assertEquals(query.id, created.queryId)

        val fetched = withRequest { service.getVisualizationById(created.id) }
        assertEquals(query.id, fetched.queryId)
    }

    @Test
    fun `getVisualizationByKey retrieves by key`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val input = AnalyticsVisualizationInput(
            key = "by-key-viz",
            name = "By Key Viz",
            description = "Test retrieval by key",
            type = AnalyticsVisualizationType.PIE,
            configuration = JsonNull
        )

        val created = withRequest { service.addVisualization(input) }
        val fetched = withRequest { service.getVisualizationByKey("by-key-viz") }

        assertNotNull(fetched)
        assertEquals(created.id, fetched.id)
    }

    @Test
    fun `getVisualizationByKey returns null for nonexistent key`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val result = withRequest { service.getVisualizationByKey("nonexistent-viz-key") }
        assertNull(result)
    }

    @Test
    fun `editVisualization updates all fields`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val input = AnalyticsVisualizationInput(
            key = "edit-viz",
            name = "Original Viz Name",
            description = "Original description",
            type = AnalyticsVisualizationType.BAR,
            configuration = JsonNull
        )

        val created = withRequest { service.addVisualization(input) }

        val editInput = AnalyticsVisualizationInput(
            id = created.id,
            key = "edit-viz",
            name = "Updated Viz Name",
            description = "Updated description",
            type = AnalyticsVisualizationType.PIE,
            configuration = JsonObject(mapOf("legend" to JsonPrimitive(true)))
        )

        withRequest { service.editVisualization(editInput) }

        val fetched = withRequest { service.getVisualizationById(created.id) }
        assertEquals("Updated Viz Name", fetched.name)
        assertEquals("Updated description", fetched.description)
        assertEquals(AnalyticsVisualizationType.PIE, fetched.type)
    }

    @Test
    fun `deleteVisualizationById removes the visualization`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val input = AnalyticsVisualizationInput(
            key = "delete-viz",
            name = "Delete Me",
            description = "Will be deleted",
            type = AnalyticsVisualizationType.TABLE,
            configuration = JsonNull
        )

        val created = withRequest { service.addVisualization(input) }
        withRequest { service.deleteVisualizationById(created.id) }

        assertFailsWith<IllegalStateException> {
            withRequest { service.getVisualizationById(created.id) }
        }
    }

    @Test
    fun `getVisualizations returns paginated results`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        repeat(3) { i ->
            withRequest {
                service.addVisualization(
                    AnalyticsVisualizationInput(
                        key = "paginated-viz-$i",
                        name = "Paginated Viz $i",
                        description = "Pagination test",
                        type = AnalyticsVisualizationType.NUMBER,
                        configuration = JsonNull
                    )
                )
            }
        }

        val page1 = withRequest { service.getVisualizations(0, 2) }
        assertEquals(2, page1.size)

        val page2 = withRequest { service.getVisualizations(2, 2) }
        assertTrue(page2.size >= 1)
    }
}
