@file:OptIn(ExperimentalUuidApi::class)

package bosca.analytics.service

import bosca.ai.chat.model.AnalyticsInvestigationKind
import bosca.ai.kit.agents.analytics.AnalyticsServices
import bosca.ai.kit.tools.analytics.ExecuteSavedQueryTool
import bosca.ai.kit.tools.analytics.InvestigationRecorder
import bosca.analytics.model.AnalyticsQuery
import bosca.analytics.model.AnalyticsQueryResponse
import bosca.analytics.security.AnalyticsDashboardPermissionEvaluator
import bosca.analytics.security.AnalyticsQueryPermissionEvaluator
import bosca.analytics.security.AnalyticsVisualizationPermissionEvaluator
import bosca.db.ConnectionConfig
import bosca.db.ConnectionFactoryImpl
import bosca.db.ConnectionPool
import bosca.di.asProvider
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume.assumeTrue
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import java.time.Duration
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi

class KitAnalyticsExecutionEndToEndTest {
    private lateinit var trinoContainer: GenericContainer<*>
    private lateinit var connectionPool: ConnectionPool

    @BeforeTest
    fun setup() {
        assumeTrue("Docker not available -- skipping Kit Trino execution test", isDockerAvailable())
        trinoContainer = GenericContainer("trinodb/trino:479")
            .withExposedPorts(8080)
            .waitingFor(
                Wait.forHttp("/v1/info")
                    .forPort(8080)
                    .forStatusCode(200)
                    .withStartupTimeout(Duration.ofSeconds(90)),
            )
        trinoContainer.start()
        connectionPool = ConnectionPool(
            ConnectionFactoryImpl(
                ConnectionConfig(
                    url = "jdbc:trino://${trinoContainer.host}:${trinoContainer.getMappedPort(8080)}",
                    user = "kit-test",
                    password = "",
                    driverClassName = "io.trino.jdbc.TrinoDriver",
                    maxConnections = 2,
                ),
                key = "trino-readonly-test",
            ),
        )
        runBlocking {
            withTimeout(kotlin.time.Duration.parse("90s")) {
                while (true) {
                    val ready = runCatching {
                        connectionPool.connection().useStatement("SELECT 1 FROM tpch.tiny.nation LIMIT 1") { statement ->
                            statement.executeQuery().use { it.next() }
                        }
                    }.getOrDefault(false)
                    if (ready) break
                    delay(500)
                }
            }
        }
    }

    @AfterTest
    fun teardown() = runTest {
        if (::connectionPool.isInitialized) connectionPool.close()
        if (::trinoContainer.isInitialized) trinoContainer.stop()
    }

    private fun isDockerAvailable(): Boolean = try {
        DockerClientFactory.instance().isDockerAvailable
    } catch (_: Throwable) {
        false
    }

    @Test
    fun `saved query execution uses Trino records and then its configured cache`() =
        runTest(timeout = kotlin.time.Duration.parse("120s")) {
            val queryId = UUID.parse("550e8400-e29b-41d4-a716-446655440000")
            val query = AnalyticsQuery(
                id = queryId,
                key = "tpch-nations",
                name = "TPCH nations",
                description = "Three deterministic TPCH rows",
                query = "SELECT nationkey, name FROM tpch.tiny.nation ORDER BY nationkey LIMIT 3",
                refreshIntervalSeconds = 300,
            )
            val queryService = mockk<AnalyticsQueryService>()
            coEvery { queryService.getQueryByKey(query.key) } returns query
            coEvery { queryService.getQueryById(queryId) } returns query
            coEvery { queryService.getParameters(queryId) } returns emptyList()
            val cache = mockk<AnalyticsQueryResultCacheService>()
            coEvery { cache.get(query, emptyList()) } returns null
            coEvery { cache.store(query, emptyList(), any(), true) } just runs
            val executionService = AnalyticsQueryExecutionServiceImpl(
                queryService.asProvider(),
                cache.asProvider(),
                connectionPool.asProvider(),
                Json,
            )
            val queryPermission = mockk<AnalyticsQueryPermissionEvaluator>()
            val authentication = mockk<AuthenticationContext>(relaxed = true)
            coEvery { queryPermission.verifyAllowed(authentication, query, any()) } just runs
            val services = AnalyticsServices(
                queryService,
                executionService,
                mockk(relaxed = true),
                mockk(relaxed = true),
                queryPermission,
                mockk<AnalyticsVisualizationPermissionEvaluator>(relaxed = true),
                mockk<AnalyticsDashboardPermissionEvaluator>(relaxed = true),
                mockk<GroupEvaluator>(relaxed = true),
                Json,
            )
            val recorder = InvestigationRecorder()

            val fresh = withContext(recorder) {
                ExecuteSavedQueryTool(services).execute(
                    authentication,
                    ExecuteSavedQueryTool.Input(key = query.key),
                )
            }
            assertTrue(fresh.success, fresh.error)
            assertFalse(fresh.cached)
            assertEquals(3, fresh.rowCount)
            assertEquals("ALGERIA", fresh.records.first().jsonObject.getValue("name").jsonPrimitive.content)
            // Exercise the LIMIT 0 probe only after the first real query has established that the
            // freshly started Trino catalog is ready. Probing during container startup can be cached
            // as an empty result by the production service, making a full-suite run order-dependent.
            assertEquals(listOf("nationkey", "name"), executionService.getColumns(queryId).map { it.name })

            val cachedAt = OffsetDateTime.now()
            coEvery { cache.get(query, emptyList()) } returns AnalyticsQueryResponse(fresh.records, true, cachedAt)
            val cached = withContext(recorder) {
                ExecuteSavedQueryTool(services).execute(
                    authentication,
                    ExecuteSavedQueryTool.Input(id = queryId.toString()),
                )
            }

            assertTrue(cached.success, cached.error)
            assertTrue(cached.cached)
            assertEquals(cachedAt.toString(), cached.refreshedAt)
            assertEquals(fresh.records, cached.records)
            assertEquals(listOf(1, 2), recorder.steps.map { it.sequence })
            assertEquals(List(2) { AnalyticsInvestigationKind.SAVED_QUERY }, recorder.steps.map { it.kind })
            assertTrue(recorder.steps.all { it.sql == query.query && it.resultSummary.startsWith("3 rows") })
        }
}
