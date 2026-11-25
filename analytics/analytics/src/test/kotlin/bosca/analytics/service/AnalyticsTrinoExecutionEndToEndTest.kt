@file:OptIn(ExperimentalUuidApi::class)

package bosca.analytics.service

import bosca.analytics.graphql.AnalyticsSavedQueryGraphQLFixture
import bosca.analytics.model.AnalyticsQueryCacheEntry
import bosca.analytics.model.AnalyticsQueryParameter
import bosca.analytics.model.QueryParameterType
import bosca.analytics.repository.QueryCacheEntryRepository
import bosca.di.ObjectProvider
import bosca.storage.service.ObjectStorageService
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonNull
import bosca.analytics.model.AnalyticsQuery
import bosca.analytics.model.AnalyticsQueryResponse
import bosca.db.ConnectionConfig
import bosca.db.ConnectionFactoryImpl
import bosca.db.ConnectionPool
import bosca.di.asProvider
import bosca.security.service.AuthenticationContext
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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

class AnalyticsTrinoExecutionEndToEndTest {
    private lateinit var trinoContainer: GenericContainer<*>
    private lateinit var connectionPool: ConnectionPool

    @BeforeTest
    fun setup() {
        assumeTrue("Docker not available -- skipping analytics Trino execution test", isDockerAvailable())
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
                    user = "analytics-test",
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
    fun `warehouse GraphQL discovers real Trino schemas and returns bounded execution evidence`() = runTest(timeout = kotlin.time.Duration.parse("120s")) {
        val execution = AnalyticsQueryExecutionServiceImpl(mockk(), mockk(), connectionPool.asProvider(), Json)
        val group = bosca.security.model.Group(UUID.random(), "administrators", "Administrators", bosca.security.model.GroupType.SYSTEM)
        val authentication = object : AuthenticationContext(null, null) {
            override fun principal() = bosca.security.service.ScopedAuthenticatedPrincipal(
                bosca.security.model.Principal(UUID.random(), anonymous = false), listOf(group), listOf("analytics:sql"), setOf(group.id), 42,
            )
        }
        bosca.analytics.graphql.AnalyticsWarehouseGraphQLFixture(execution).use { fixture ->
            val result = fixture.call("""{ analytics { warehouse {
                catalogs schemas(catalog:"tpch") tables(catalog:"tpch",schema:"tiny")
                describe(catalog:"tpch",schema:"tiny",table:"nation") { name typeName nullable }
                execute(sql:"SELECT nationkey, name FROM tpch.tiny.nation ORDER BY nationkey",limit:2) {
                    sql records columns { name typeName nullable } truncated executedAt durationMs
                }
            } } }""", authentication)
            assertTrue(result.errors.isEmpty(), result.errors.toString())
            val warehouse = requireNotNull(result.data).jsonObject.getValue("analytics").jsonObject.getValue("warehouse").jsonObject
            assertTrue(warehouse.getValue("catalogs").toString().contains("tpch"))
            assertTrue(warehouse.getValue("schemas").toString().contains("tiny"))
            assertTrue(warehouse.getValue("tables").toString().contains("nation"))
            val evidence = warehouse.getValue("execute").jsonObject
            assertEquals("true", evidence.getValue("truncated").jsonPrimitive.content)
            assertTrue(evidence.getValue("records").toString().contains("ALGERIA"))
            assertTrue(evidence.getValue("records").toString().contains("ARGENTINA"))
            assertEquals("SELECT nationkey, name FROM tpch.tiny.nation ORDER BY nationkey", evidence.getValue("sql").jsonPrimitive.content)
        }
    }

    @Test
    fun `saved query GraphQL returns real Trino bindings and serves cache hits without evidence`() =
        runTest(timeout = kotlin.time.Duration.parse("120s")) {
            val query = AnalyticsQuery(UUID.random(), "filtered-nations", "Nations", "",
                "SELECT nationkey, name FROM tpch.tiny.nation WHERE nationkey >= :minimum ORDER BY nationkey LIMIT 2", refreshIntervalSeconds = 60)
            val queries = mockk<AnalyticsQueryService>(relaxed = true)
            coEvery { queries.getQueryById(query.id) } returns query
            coEvery { queries.getQueryByKey(query.key) } returns query
            coEvery { queries.getParameters(query.id) } returns listOf(AnalyticsQueryParameter(
                queryId = query.id, parameter = "minimum", name = "Minimum", description = "",
                type = QueryParameterType.INTEGER, defaultValue = JsonPrimitive(1), required = false, sort = 0,
            ))
            val repository = mockk<QueryCacheEntryRepository>()
            val storage = mockk<ObjectStorageService>()
            var stored: AnalyticsQueryCacheEntry? = null
            val objects = mutableMapOf<String, String>()
            coEvery { repository.getEntry(query.id, query.cacheGeneration, any()) } answers { stored }
            coEvery { repository.markRefreshed(query.id, query.cacheGeneration, any(), any(), any()) } answers {
                val at = OffsetDateTime.now()
                AnalyticsQueryCacheEntry(query.id, thirdArg(), arg(3), at, at,
                    query.cacheGeneration, arg(4)).also { stored = it }
            }
            coEvery { repository.deleteOverflowEntries(any(), any(), any()) } returns emptyList()
            coEvery { repository.touchAccessed(any(), any(), any()) } just runs
            coEvery { storage.setInputStream(any(), any(), null) } answers {
                val payload = secondArg<java.io.InputStream>().bufferedReader().readText()
                objects[firstArg<Any>().toString()] = payload
                payload.length.toLong()
            }
            coEvery { storage.getString(any()) } answers { objects.getValue(firstArg<Any>().toString()) }
            val cache = AnalyticsQueryResultCacheServiceImpl(repository, storage, Json)
            val pool = mockk<ObjectProvider<ConnectionPool>>()
            coEvery { pool.get() } returns connectionPool
            val execution = AnalyticsQueryExecutionServiceImpl(queries.asProvider(), cache.asProvider(), pool, Json)
            val group = bosca.security.model.Group(UUID.random(), "analytics.manager", "Analytics", bosca.security.model.GroupType.SYSTEM)
            coEvery { queries.getPermissions(query) } returns listOf(
                bosca.security.model.Permission(group.id, bosca.security.model.PermissionAction.VIEW),
                bosca.security.model.Permission(group.id, bosca.security.model.PermissionAction.EXECUTE),
            )
            val auth = object : AuthenticationContext(null, null) {
                override fun principal() = bosca.security.service.ScopedAuthenticatedPrincipal(
                    bosca.security.model.Principal(UUID.random(), anonymous = false), listOf(group),
                    listOf("analytics:execute", "analytics:view"), null, 42,
                )
            }
            val operation = """{ analytics { queries { executeByKey(key:"filtered-nations",parameters:[]) {
                records cached stale evidence { queryId sql executedAt durationMs parameters { position parameter type value } }
            } } } }"""
            AnalyticsSavedQueryGraphQLFixture(queries, execution).use { fixture ->
                val fresh = fixture.call(operation, auth)
                assertTrue(fresh.errors.isEmpty(), fresh.errors.toString())
                val result = requireNotNull(fresh.data).jsonObject.getValue("analytics").jsonObject.getValue("queries").jsonObject.getValue("executeByKey").jsonObject
                val snapshot = result.getValue("evidence").jsonObject
                assertEquals(query.query.replace(":minimum", "?"), snapshot.getValue("sql").jsonPrimitive.content)
                val bindings = snapshot.getValue("parameters") as JsonArray
                assertEquals(JsonPrimitive(1), bindings.single().jsonObject.getValue("value"))
                assertTrue(result.getValue("records").toString().contains("ARGENTINA"))
                assertTrue(result.getValue("records").toString().contains("BRAZIL"))
                assertEquals(JsonPrimitive(false), result.getValue("cached"))
                assertEquals(1, objects.size)

                coEvery { pool.get() } throws IllegalStateException("A cache hit must not acquire Trino")
                cache.currentTime = { requireNotNull(stored).lastRefreshedAt.plusSeconds(61) }
                val cached = fixture.call(operation, auth)
                assertTrue(cached.errors.isEmpty(), cached.errors.toString())
                val hit = requireNotNull(cached.data).jsonObject.getValue("analytics").jsonObject.getValue("queries").jsonObject.getValue("executeByKey").jsonObject
                assertEquals(result.getValue("records"), hit.getValue("records"))
                assertEquals(JsonNull, hit.getValue("evidence"))
                assertEquals(JsonPrimitive(true), hit.getValue("cached"))
                assertEquals(JsonPrimitive(true), hit.getValue("stale"))
            }
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
            val fresh = executionService.execute(query.key, emptyList())
            assertFalse(fresh.cached)
            assertEquals(3, fresh.records.size)
            assertEquals("ALGERIA", fresh.records.first().jsonObject.getValue("name").jsonPrimitive.content)
            // Exercise the LIMIT 0 probe only after the first real query has established that the
            // freshly started Trino catalog is ready. Probing during container startup can be cached
            // as an empty result by the production service, making a full-suite run order-dependent.
            assertEquals(listOf("nationkey", "name"), executionService.getColumns(queryId).map { it.name })

            val cachedAt = OffsetDateTime.now()
            coEvery { cache.get(query, emptyList()) } returns AnalyticsQueryResponse(fresh.records, true, cachedAt)
            val cached = executionService.execute(queryId, emptyList())

            assertTrue(cached.cached)
            assertEquals(cachedAt, cached.refreshedAt)
            assertEquals(fresh.records, cached.records)
            coVerify(exactly = 1) { cache.store(query, emptyList(), any(), true) }
        }
}
