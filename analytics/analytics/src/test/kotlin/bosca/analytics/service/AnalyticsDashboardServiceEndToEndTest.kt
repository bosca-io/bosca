@file:OptIn(ExperimentalUuidApi::class, InternalDI::class)

package bosca.analytics.service

import bosca.analytics.model.AnalyticsDashboardInput
import bosca.analytics.model.AnalyticsQueryInput
import bosca.analytics.model.AnalyticsVisualizationInput
import bosca.analytics.model.AnalyticsVisualizationInstanceInput
import bosca.analytics.model.AnalyticsVisualizationType
import bosca.analytics.repository.AnalyticsDashboardPermissionRepositoryImpl
import bosca.analytics.repository.AnalyticsDashboardRepositoryImpl
import bosca.analytics.repository.AnalyticsDashboardVisualizationRepositoryImpl
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
import bosca.serialization.UUID
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

class AnalyticsDashboardServiceEndToEndTest {

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

    private lateinit var service: AnalyticsDashboardServiceImpl
    private lateinit var vizService: AnalyticsVisualizationServiceImpl
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

        val vizRepo = AnalyticsVisualizationRepositoryImpl()

        service = AnalyticsDashboardServiceImpl(
            AnalyticsDashboardRepositoryImpl(),
            AnalyticsDashboardPermissionRepositoryImpl(),
            AnalyticsDashboardVisualizationRepositoryImpl(),
            vizRepo
        )

        vizService = AnalyticsVisualizationServiceImpl(
            vizRepo,
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

    private suspend fun createTestVisualization(key: String): UUID {
        val viz = withRequest {
            vizService.addVisualization(
                AnalyticsVisualizationInput(
                    key = key,
                    name = "Viz $key",
                    description = "Test visualization",
                    type = AnalyticsVisualizationType.BAR,
                    configuration = JsonNull
                )
            )
        }
        return viz.id
    }

    @Test
    fun `addDashboard creates dashboard and getDashboardById retrieves it`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val input = AnalyticsDashboardInput(
            key = "test-dashboard",
            name = "Test Dashboard",
            description = "A test dashboard",
            configuration = JsonObject(mapOf("layout" to JsonPrimitive("grid"))),
            visualizations = emptyList()
        )

        val created = withRequest { service.addDashboard(input) }

        assertNotNull(created.id)
        assertEquals("test-dashboard", created.key)
        assertEquals("Test Dashboard", created.name)
        assertEquals("A test dashboard", created.description)

        val fetched = withRequest { service.getDashboardById(created.id) }
        assertEquals(created.id, fetched.id)
        assertEquals("Test Dashboard", fetched.name)
    }

    @Test
    fun `addDashboard with visualizations creates associations`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val vizId1 = createTestVisualization("dashboard-viz-1")
        val vizId2 = createTestVisualization("dashboard-viz-2")

        val input = AnalyticsDashboardInput(
            key = "dashboard-with-viz",
            name = "Dashboard With Visualizations",
            description = "Dashboard with two visualizations",
            configuration = JsonNull,
            visualizations = listOf(
                AnalyticsVisualizationInstanceInput(
                    visualizationId = vizId1,
                    configuration = JsonObject(mapOf("position" to JsonPrimitive(0)))
                ),
                AnalyticsVisualizationInstanceInput(
                    visualizationId = vizId2,
                    configuration = JsonObject(mapOf("position" to JsonPrimitive(1)))
                )
            )
        )

        val created = withRequest { service.addDashboard(input) }
        val instances = withRequest { service.getVisualizations(created.id) }

        assertEquals(2, instances.size)
        assertTrue(instances.any { it.visualization.id == vizId1 })
        assertTrue(instances.any { it.visualization.id == vizId2 })
    }

    @Test
    fun `getDashboardByKey retrieves by key`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val input = AnalyticsDashboardInput(
            key = "by-key-dashboard",
            name = "By Key Dashboard",
            description = "Test retrieval by key",
            configuration = JsonNull,
            visualizations = emptyList()
        )

        val created = withRequest { service.addDashboard(input) }
        val fetched = withRequest { service.getDashboardByKey("by-key-dashboard") }

        assertNotNull(fetched)
        assertEquals(created.id, fetched.id)
    }

    @Test
    fun `getDashboardByKey returns null for nonexistent key`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val result = withRequest { service.getDashboardByKey("nonexistent-dashboard") }
        assertNull(result)
    }

    @Test
    fun `editDashboard updates fields and replaces visualization associations`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val vizId1 = createTestVisualization("edit-dash-viz-1")
        val vizId2 = createTestVisualization("edit-dash-viz-2")
        val vizId3 = createTestVisualization("edit-dash-viz-3")

        val input = AnalyticsDashboardInput(
            key = "edit-dashboard",
            name = "Original Dashboard",
            description = "Original description",
            configuration = JsonNull,
            visualizations = listOf(
                AnalyticsVisualizationInstanceInput(vizId1, JsonNull)
            )
        )

        val created = withRequest { service.addDashboard(input) }

        val editInput = AnalyticsDashboardInput(
            id = created.id,
            key = "edit-dashboard",
            name = "Updated Dashboard",
            description = "Updated description",
            configuration = JsonObject(mapOf("theme" to JsonPrimitive("dark"))),
            visualizations = listOf(
                AnalyticsVisualizationInstanceInput(vizId2, JsonNull),
                AnalyticsVisualizationInstanceInput(vizId3, JsonNull)
            )
        )

        withRequest { service.editDashboard(editInput) }

        val fetched = withRequest { service.getDashboardById(created.id) }
        assertEquals("Updated Dashboard", fetched.name)
        assertEquals("Updated description", fetched.description)

        val instances = withRequest { service.getVisualizations(created.id) }
        assertEquals(2, instances.size)
        assertTrue(instances.none { it.visualization.id == vizId1 }, "old viz should be removed")
        assertTrue(instances.any { it.visualization.id == vizId2 })
        assertTrue(instances.any { it.visualization.id == vizId3 })
    }

    @Test
    fun `deleteDashboardById removes dashboard and cascade-deletes associations`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val vizId = createTestVisualization("delete-dash-viz")

        val input = AnalyticsDashboardInput(
            key = "delete-dashboard",
            name = "Delete Me",
            description = "Will be deleted",
            configuration = JsonNull,
            visualizations = listOf(
                AnalyticsVisualizationInstanceInput(vizId, JsonNull)
            )
        )

        val created = withRequest { service.addDashboard(input) }
        withRequest { service.deleteDashboardById(created.id) }

        assertFailsWith<IllegalStateException> {
            withRequest { service.getDashboardById(created.id) }
        }

        // The visualization itself should still exist (only the association is deleted)
        val viz = withRequest { vizService.getVisualizationById(vizId) }
        assertNotNull(viz)
    }

    @Test
    fun `addVisualization adds instance to existing dashboard`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val input = AnalyticsDashboardInput(
            key = "add-viz-dashboard",
            name = "Add Viz Dashboard",
            description = "Dashboard to add viz to",
            configuration = JsonNull,
            visualizations = emptyList()
        )

        val created = withRequest { service.addDashboard(input) }

        val vizId = createTestVisualization("add-instance-viz")
        val config = JsonObject(mapOf("width" to JsonPrimitive(6)))

        val instanceId = withRequest { service.addVisualization(created.id, vizId, config) }
        assertNotNull(instanceId)

        val instances = withRequest { service.getVisualizations(created.id) }
        assertEquals(1, instances.size)
        assertEquals(vizId, instances[0].visualization.id)
        assertEquals(instanceId, instances[0].id)
    }

    @Test
    fun `removeVisualization removes specific instance`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val vizId1 = createTestVisualization("remove-viz-1")
        val vizId2 = createTestVisualization("remove-viz-2")

        val input = AnalyticsDashboardInput(
            key = "remove-viz-dashboard",
            name = "Remove Viz Dashboard",
            description = "Dashboard to remove viz from",
            configuration = JsonNull,
            visualizations = emptyList()
        )

        val created = withRequest { service.addDashboard(input) }

        val instanceId1 = withRequest { service.addVisualization(created.id, vizId1, JsonNull) }
        withRequest { service.addVisualization(created.id, vizId2, JsonNull) }

        // Remove the first instance
        withRequest { service.removeVisualization(instanceId1) }

        val instances = withRequest { service.getVisualizations(created.id) }
        assertEquals(1, instances.size)
        assertEquals(vizId2, instances[0].visualization.id)
    }

    @Test
    fun `getDashboards returns paginated results`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        repeat(3) { i ->
            withRequest {
                service.addDashboard(
                    AnalyticsDashboardInput(
                        key = "paginated-dashboard-$i",
                        name = "Paginated Dashboard $i",
                        description = "Pagination test",
                        configuration = JsonNull,
                        visualizations = emptyList()
                    )
                )
            }
        }

        val page1 = withRequest { service.getDashboards(0, 2) }
        assertEquals(2, page1.size)

        val page2 = withRequest { service.getDashboards(2, 2) }
        assertTrue(page2.size >= 1)
    }
}
