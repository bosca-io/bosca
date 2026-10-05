@file:OptIn(ExperimentalUuidApi::class, InternalDI::class)

package bosca.analytics.service

import bosca.ai.chat.model.AnalyticsInvestigationKind
import bosca.ai.kit.agents.analytics.AnalyticsServices
import bosca.ai.kit.tools.analytics.AddDashboardVisualizationTool
import bosca.ai.kit.tools.analytics.CreateDashboardTool
import bosca.ai.kit.tools.analytics.CreateSavedQueryTool
import bosca.ai.kit.tools.analytics.CreateVisualizationTool
import bosca.ai.kit.tools.analytics.InvestigationRecorder
import bosca.analytics.graphql.AnalyticsDashboardController
import bosca.analytics.graphql.AnalyticsDashboardsController
import bosca.analytics.graphql.AnalyticsQueriesController
import bosca.analytics.graphql.AnalyticsVisualizationsController
import bosca.analytics.model.AnalyticsQueryColumn
import bosca.analytics.model.AnalyticsVisualization
import bosca.analytics.model.AnalyticsVisualizationType
import bosca.analytics.repository.AnalyticsDashboardPermissionRepositoryImpl
import bosca.analytics.repository.AnalyticsDashboardRepositoryImpl
import bosca.analytics.repository.AnalyticsDashboardVisualizationRepositoryImpl
import bosca.analytics.repository.AnalyticsVisualizationPermissionRepositoryImpl
import bosca.analytics.repository.AnalyticsVisualizationRepositoryImpl
import bosca.analytics.repository.QueryDefinitionRepositoryImpl
import bosca.analytics.repository.QueryParameterRepositoryImpl
import bosca.analytics.repository.QueryPermissionRepositoryImpl
import bosca.analytics.security.ANALYTICS_MANAGER_GROUP
import bosca.analytics.security.AnalyticsDashboardPermissionEvaluator
import bosca.analytics.security.AnalyticsQueryPermissionEvaluator
import bosca.analytics.security.AnalyticsVisualizationPermissionEvaluator
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
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.OffsetDateTimeSerializer
import bosca.serialization.UUID
import bosca.serialization.UUIDSerializer
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.unmockkAll
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi

class KitAnalyticsToolsEndToEndTest {
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

    private lateinit var services: AnalyticsServices
    private lateinit var authentication: AuthenticationContext
    private lateinit var queryController: AnalyticsQueriesController
    private lateinit var visualizationController: AnalyticsVisualizationsController
    private lateinit var dashboardController: AnalyticsDashboardsController
    private lateinit var dashboardTypeController: AnalyticsDashboardController

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

        connectionPool = ConnectionPool(
            ConnectionFactoryImpl(
                ConnectionConfig(
                    url = postgresContainer.jdbcUrl,
                    user = postgresContainer.username,
                    password = postgresContainer.password,
                ),
                key = "test",
            ),
        )
        FlywayMigration(connectionPool).migrate(listOf(CoreMigration()))

        val natsPool = natsContainer.newConnectionPool(1)
        cacheManager = NatsCacheManager(natsPool)
        serializer = RequestCacheSerializerImpl(testJson)
        ProviderRegistry.clear()
        provides<CacheManager>(singleton = true) { cacheManager }
        provides<RequestCacheSerializer>(singleton = true) { serializer }
        provides<Json>(singleton = true) { testJson }

        val queryService = AnalyticsQueryServiceImpl(
            QueryDefinitionRepositoryImpl(),
            QueryParameterRepositoryImpl(),
            QueryPermissionRepositoryImpl(),
            MissingSourceRefServiceProvider,
            TestResultCacheServiceProvider(testJson),
        )
        val visualizationRepository = AnalyticsVisualizationRepositoryImpl()
        val visualizationService = AnalyticsVisualizationServiceImpl(
            visualizationRepository,
            AnalyticsVisualizationPermissionRepositoryImpl(),
        )
        val dashboardService = AnalyticsDashboardServiceImpl(
            AnalyticsDashboardRepositoryImpl(),
            AnalyticsDashboardPermissionRepositoryImpl(),
            AnalyticsDashboardVisualizationRepositoryImpl(),
            visualizationRepository,
        )
        val executionService = mockk<AnalyticsQueryExecutionService>()
        coEvery { executionService.getColumns(any<UUID>()) } returns listOf(
            AnalyticsQueryColumn("week", "date", false),
            AnalyticsQueryColumn("signups", "bigint", false),
        )

        authentication = mockk(relaxed = true)
        val groupEvaluator = mockk<GroupEvaluator>()
        every { groupEvaluator.hasGroup(authentication, ANALYTICS_MANAGER_GROUP) } returns true
        val queryPermission = mockk<AnalyticsQueryPermissionEvaluator>()
        val visualizationPermission = mockk<AnalyticsVisualizationPermissionEvaluator>()
        val dashboardPermission = mockk<AnalyticsDashboardPermissionEvaluator>()
        coEvery { queryPermission.verifyAllowed(any(), any(), any()) } just runs
        coEvery { visualizationPermission.verifyAllowed(any(), any(), any()) } just runs
        coEvery { dashboardPermission.verifyAllowed(any(), any(), any()) } just runs
        coEvery {
            visualizationPermission.isAllowed(
                authentication,
                any<AnalyticsVisualization>(),
                PermissionAction.VIEW,
            )
        } returns true

        services = AnalyticsServices(
            queryService,
            executionService,
            visualizationService,
            dashboardService,
            queryPermission,
            visualizationPermission,
            dashboardPermission,
            groupEvaluator,
            testJson,
        )
        queryController = AnalyticsQueriesController(queryService, executionService, queryPermission)
        visualizationController = AnalyticsVisualizationsController(visualizationService, visualizationPermission)
        dashboardController = AnalyticsDashboardsController(dashboardService, dashboardPermission)
        dashboardTypeController = AnalyticsDashboardController(dashboardService, dashboardPermission, visualizationPermission)
    }

    @AfterTest
    fun teardown() = runBlocking {
        if (::connectionPool.isInitialized) connectionPool.close()
        if (::natsContainer.isInitialized) natsContainer.stop()
        if (::postgresContainer.isInitialized) postgresContainer.stop()
        ProviderRegistry.clear()
    }

    private suspend fun <T> withRequest(block: suspend () -> T): T {
        val connectionManager = ConnectionManager(connectionPool)
        val requestCache = RequestCache(cacheManager, serializer)
        val eventManager = EventManager().apply { filter = DisabledEventManagerFilter }
        return withContext(
            connectionManager.asCoroutineContext() + requestCache.asCoroutineContext() + eventManager.asCoroutineContext(),
        ) {
            try {
                block()
            } finally {
                withContext(NonCancellable) { connectionManager.release() }
            }
        }
    }

    @Test
    fun `Kit creates an analytics composition that Studio GraphQL resolvers can read`() =
        runTest(timeout = kotlin.time.Duration.parse("90s")) {
            val recorder = InvestigationRecorder()

            val (query, visualization, dashboard, instance) = withRequest {
                withContext(recorder) {
                    val query = CreateSavedQueryTool(services).execute(
                        authentication,
                        CreateSavedQueryTool.Input(
                            name = "Weekly Signups",
                            sql = "SELECT week, signups FROM analytics.weekly_signups",
                            refreshIntervalSeconds = 3600,
                        ),
                    )
                    assertTrue(query.success, query.error)

                    val visualization = CreateVisualizationTool(services).execute(
                        authentication,
                        CreateVisualizationTool.Input(
                            name = "Weekly Signups Trend",
                            type = AnalyticsVisualizationType.LINE,
                            queryId = query.id,
                            configuration = buildJsonObject {
                                put("x", "week")
                                put("y", kotlinx.serialization.json.buildJsonArray { add("signups") })
                            },
                        ),
                    )
                    assertTrue(visualization.success, visualization.error)

                    val dashboard = CreateDashboardTool(services).execute(
                        authentication,
                        CreateDashboardTool.Input(name = "Growth Dashboard"),
                    )
                    assertTrue(dashboard.success, dashboard.error)

                    val instance = AddDashboardVisualizationTool(services).execute(
                        authentication,
                        AddDashboardVisualizationTool.Input(dashboard.id, visualization.id),
                    )
                    assertTrue(instance.success, instance.error)
                    listOf(query.id, visualization.id, dashboard.id, instance.instanceId)
                }
            }

            val storedQuery = withRequest { queryController.queryById(authentication, UUID.parse(query)) }
            val storedVisualization = withRequest {
                visualizationController.byId(authentication, UUID.parse(visualization))
            }
            val storedDashboard = withRequest { dashboardController.byId(authentication, UUID.parse(dashboard)) }
            val instances = withRequest {
                dashboardTypeController.visualizations(authentication, storedDashboard)
            }

            assertEquals(storedQuery.id, storedVisualization.queryId)
            assertEquals(3600, storedQuery.refreshIntervalSeconds)
            assertEquals(UUID.parse(instance), instances.single().id)
            assertEquals(storedVisualization.id, instances.single().visualization.id)
            assertEquals(listOf(1, 2, 3, 4), recorder.steps.map { it.sequence })
            assertEquals(List(4) { AnalyticsInvestigationKind.ARTIFACT }, recorder.steps.map { it.kind })
            assertEquals(
                listOf(
                    "create_saved_query",
                    "create_visualization",
                    "create_dashboard",
                    "add_dashboard_visualization",
                ),
                recorder.steps.map { it.tool },
            )
            assertTrue(recorder.steps[0].resultSummary.contains(query))
            assertTrue(recorder.steps[1].resultSummary.contains(visualization))
            assertTrue(recorder.steps[2].resultSummary.contains(dashboard))
            assertTrue(recorder.steps[3].resultSummary.contains(instance))
        }
}
