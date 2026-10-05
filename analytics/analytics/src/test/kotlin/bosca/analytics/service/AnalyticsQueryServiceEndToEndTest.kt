@file:OptIn(ExperimentalUuidApi::class, InternalDI::class)

package bosca.analytics.service

import bosca.analytics.configuration.AnalyticsQueryCacheConfiguration
import bosca.analytics.model.AnalyticsQueryExecutionParameterInput
import bosca.analytics.model.AnalyticsQueryInput
import bosca.analytics.model.AnalyticsQueryParameterInput
import bosca.analytics.model.AnalyticsQueryResponse
import bosca.analytics.model.QueryParameterType
import bosca.analytics.repository.QueryCacheEntryRepositoryImpl
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
import bosca.db.connection
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.toJavaUuid

class AnalyticsQueryServiceEndToEndTest {

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

    private lateinit var service: AnalyticsQueryServiceImpl
    private lateinit var resultCache: AnalyticsQueryResultCacheService
    private val cacheEntries = QueryCacheEntryRepositoryImpl()

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

        val resultCacheProvider = TestResultCacheServiceProvider(
            testJson,
            AnalyticsQueryCacheConfiguration(maxEntriesPerQuery = 2),
        )
        resultCache = resultCacheProvider.get()
        service = AnalyticsQueryServiceImpl(
            QueryDefinitionRepositoryImpl(),
            QueryParameterRepositoryImpl(),
            QueryPermissionRepositoryImpl(),
            MissingSourceRefServiceProvider,
            resultCacheProvider,
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
    fun `addQuery creates query and getQueryById retrieves it`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val input = AnalyticsQueryInput(
            key = "test-query",
            name = "Test Query",
            description = "A test analytics query",
            query = "SELECT count(*) FROM events"
        )

        val created = withRequest { service.addQuery(input) }

        assertNotNull(created.id)
        assertEquals("test-query", created.key)
        assertEquals("Test Query", created.name)
        assertEquals("A test analytics query", created.description)
        assertEquals("SELECT count(*) FROM events", created.query)

        val fetched = withRequest { service.getQueryById(created.id) }
        assertEquals(created.id, fetched.id)
        assertEquals("Test Query", fetched.name)
    }

    @Test
    fun `real cache repository preserves caller activity bounds combinations and rejects stale generation writes`() =
        runTest(timeout = kotlin.time.Duration.parse("60s")) {
            val created = withRequest {
                service.addQuery(
                    AnalyticsQueryInput(
                        key = "cache-integration",
                        name = "Cache integration",
                        description = "",
                        query = "select 1",
                        refreshIntervalSeconds = 60,
                    ),
                )
            }
            val firstParameters = listOf(AnalyticsQueryExecutionParameterInput("value", JsonPrimitive(1)))
            val response = AnalyticsQueryResponse(listOf(JsonPrimitive(1)))

            withRequest { resultCache.store(created, firstParameters, response, callerAccess = true) }
            val firstEntry = withRequest { cacheEntries.getEntries(created.id).single() }
            assertEquals(response.records, withRequest { resultCache.get(created, firstParameters) }?.records)

            withRequest {
                connection().useStatement(
                    """
                    update analytics_query_cache_entries
                    set last_refreshed_at = now() - interval '4 minutes'
                    where query_id = ?
                    """.trimIndent(),
                ) { statement ->
                    statement.setObject(1, created.id.toJavaUuid())
                    statement.executeUpdate()
                }
            }
            val staleResponse = withRequest { resultCache.get(created, firstParameters) }
            assertNotNull(
                staleResponse,
                "the last known good result must remain available until refresh succeeds",
            )
            assertTrue(staleResponse.stale)

            withRequest { resultCache.store(created, firstParameters, response, callerAccess = false) }
            val refreshedEntry = withRequest { cacheEntries.getEntries(created.id).single() }
            assertEquals(
                firstEntry.lastAccessedAt,
                refreshedEntry.lastAccessedAt,
                "background refresh must not extend caller activity",
            )

            withRequest { resultCache.invalidate(created.id) }
            withRequest { resultCache.store(created, firstParameters, response, callerAccess = false) }
            assertTrue(
                withRequest { cacheEntries.getEntries(created.id).isEmpty() },
                "background refresh must not recreate a combination removed by pruning",
            )

            withRequest {
                resultCache.store(created, firstParameters, response, callerAccess = true)
                resultCache.store(
                    created,
                    listOf(AnalyticsQueryExecutionParameterInput("value", JsonPrimitive(2))),
                    response,
                    callerAccess = true,
                )
                resultCache.store(
                    created,
                    listOf(AnalyticsQueryExecutionParameterInput("value", JsonPrimitive(3))),
                    response,
                    callerAccess = true,
                )
            }
            assertEquals(2, withRequest { cacheEntries.getEntries(created.id).size })

            val updated = withRequest {
                service.editQuery(
                    AnalyticsQueryInput(
                        id = created.id,
                        key = created.key,
                        name = created.name,
                        description = created.description,
                        query = "select 2",
                        refreshIntervalSeconds = 60,
                    ),
                )
            }
            assertEquals(created.cacheGeneration + 1, updated.cacheGeneration)

            // Simulates a live execution that began before the edit committed.
            withRequest { resultCache.store(created, firstParameters, response, callerAccess = true) }
            assertTrue(withRequest { cacheEntries.getEntries(created.id).isEmpty() })
            assertNull(withRequest { resultCache.get(updated, firstParameters) })
        }

    @Test
    fun `addQuery with parameters persists parameters`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val input = AnalyticsQueryInput(
            key = "parameterized-query",
            name = "Parameterized Query",
            description = "Query with parameters",
            query = "SELECT * FROM events WHERE type = :type AND created > :startDate",
            parameters = listOf(
                AnalyticsQueryParameterInput(
                    parameter = "type",
                    name = "Event Type",
                    description = "The event type to filter",
                    type = QueryParameterType.STRING,
                    arrayType = null,
                    required = true
                ),
                AnalyticsQueryParameterInput(
                    parameter = "startDate",
                    name = "Start Date",
                    description = "Filter events after this date",
                    type = QueryParameterType.DATE,
                    arrayType = null,
                    required = false
                )
            )
        )

        val created = withRequest { service.addQuery(input) }
        val parameters = withRequest { service.getParameters(created.id) }

        assertEquals(2, parameters.size)

        val typeParam = parameters.find { it.parameter == "type" }
        assertNotNull(typeParam)
        assertEquals("Event Type", typeParam.name)
        assertEquals(QueryParameterType.STRING, typeParam.type)
        assertTrue(typeParam.required)

        val dateParam = parameters.find { it.parameter == "startDate" }
        assertNotNull(dateParam)
        assertEquals("Start Date", dateParam.name)
        assertEquals(QueryParameterType.DATE, dateParam.type)
    }

    @Test
    fun `getQueryByKey retrieves by key`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val input = AnalyticsQueryInput(
            key = "by-key-query",
            name = "By Key Query",
            description = "Test retrieval by key",
            query = "SELECT 1"
        )

        val created = withRequest { service.addQuery(input) }
        val fetched = withRequest { service.getQueryByKey("by-key-query") }

        assertNotNull(fetched)
        assertEquals(created.id, fetched.id)
        assertEquals("by-key-query", fetched.key)
    }

    @Test
    fun `getQueryByKey returns null for nonexistent key`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val result = withRequest { service.getQueryByKey("nonexistent-key") }
        assertNull(result)
    }

    @Test
    fun `editQuery updates fields and replaces parameters`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val input = AnalyticsQueryInput(
            key = "edit-query",
            name = "Original Name",
            description = "Original Description",
            query = "SELECT 1",
            parameters = listOf(
                AnalyticsQueryParameterInput(
                    parameter = "old_param",
                    name = "Old Param",
                    description = "Will be replaced",
                    type = QueryParameterType.STRING,
                    arrayType = null
                )
            )
        )

        val created = withRequest { service.addQuery(input) }

        val editInput = AnalyticsQueryInput(
            id = created.id,
            key = "edit-query",
            name = "Updated Name",
            description = "Updated Description",
            query = "SELECT count(*) FROM events",
            parameters = listOf(
                AnalyticsQueryParameterInput(
                    parameter = "new_param1",
                    name = "New Param 1",
                    description = "First new parameter",
                    type = QueryParameterType.INTEGER,
                    arrayType = null,
                    required = true
                ),
                AnalyticsQueryParameterInput(
                    parameter = "new_param2",
                    name = "New Param 2",
                    description = "Second new parameter",
                    type = QueryParameterType.FLOAT,
                    arrayType = null
                )
            )
        )

        withRequest { service.editQuery(editInput) }

        val fetched = withRequest { service.getQueryById(created.id) }
        assertEquals("Updated Name", fetched.name)
        assertEquals("Updated Description", fetched.description)
        assertEquals("SELECT count(*) FROM events", fetched.query)
        assertEquals(created.cacheGeneration + 1, fetched.cacheGeneration)

        val parameters = withRequest { service.getParameters(created.id) }
        assertEquals(2, parameters.size)
        assertTrue(parameters.none { it.parameter == "old_param" }, "old parameter should be removed")
        assertTrue(parameters.any { it.parameter == "new_param1" }, "new param 1 should exist")
        assertTrue(parameters.any { it.parameter == "new_param2" }, "new param 2 should exist")
    }

    @Test
    fun `deleteQueryById removes the query`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val input = AnalyticsQueryInput(
            key = "delete-query",
            name = "Delete Me",
            description = "Will be deleted",
            query = "SELECT 1"
        )

        val created = withRequest { service.addQuery(input) }
        withRequest { service.deleteQueryById(created.id) }

        assertFailsWith<IllegalStateException> {
            withRequest { service.getQueryById(created.id) }
        }
    }

    @Test
    fun `getQueries returns paginated results`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        repeat(3) { i ->
            withRequest {
                service.addQuery(
                    AnalyticsQueryInput(
                        key = "paginated-query-$i",
                        name = "Paginated Query $i",
                        description = "Pagination test",
                        query = "SELECT $i"
                    )
                )
            }
        }

        val page1 = withRequest { service.getQueries(0, 2) }
        assertEquals(2, page1.size)

        val page2 = withRequest { service.getQueries(2, 2) }
        assertTrue(page2.size >= 1)
    }

    @Test
    fun `getParameters returns parameters for each query independently`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val query1 = withRequest {
            service.addQuery(
                AnalyticsQueryInput(
                    key = "multi-param-query-1",
                    name = "Multi Param Query 1",
                    description = "Test",
                    query = "SELECT 1",
                    parameters = listOf(
                        AnalyticsQueryParameterInput(
                            parameter = "q1_param",
                            name = "Q1 Param",
                            description = "First query param",
                            type = QueryParameterType.STRING,
                            arrayType = null
                        )
                    )
                )
            )
        }

        val query2 = withRequest {
            service.addQuery(
                AnalyticsQueryInput(
                    key = "multi-param-query-2",
                    name = "Multi Param Query 2",
                    description = "Test",
                    query = "SELECT 2",
                    parameters = listOf(
                        AnalyticsQueryParameterInput(
                            parameter = "q2_param",
                            name = "Q2 Param",
                            description = "Second query param",
                            type = QueryParameterType.INTEGER,
                            arrayType = null
                        )
                    )
                )
            )
        }

        val params1 = withRequest { service.getParameters(query1.id) }
        val params2 = withRequest { service.getParameters(query2.id) }

        assertEquals(1, params1.size)
        assertEquals("q1_param", params1.first().parameter)

        assertEquals(1, params2.size)
        assertEquals("q2_param", params2.first().parameter)
    }

    @Test
    fun `applyGitSync replaces SQL and parameters when declarations are provided`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val created = withRequest {
            service.addQuery(
                AnalyticsQueryInput(
                    key = "gitsync-replace",
                    name = "n", description = "d",
                    query = "select 1",
                    parameters = listOf(
                        AnalyticsQueryParameterInput(
                            parameter = "old", name = "Old", description = "",
                            type = QueryParameterType.STRING, arrayType = null,
                        )
                    ),
                )
            )
        }

        val declarations = listOf(
            bosca.analytics.query.QueryParameterDeclaration(
                parameter = "newParam",
                name = "New",
                description = "Replaced",
                type = QueryParameterType.DATE,
                required = true,
            )
        )

        val updated = withRequest {
            service.applyGitSync(created.id, "select * from events where created >= :newParam", declarations)
        }

        assertNotNull(updated)
        assertEquals("select * from events where created >= :newParam", updated.query)
        assertEquals(created.cacheGeneration + 1, updated.cacheGeneration)
        val params = withRequest { service.getParameters(created.id) }
        assertEquals(1, params.size)
        assertEquals("newParam", params.single().parameter)
        assertEquals(QueryParameterType.DATE, params.single().type)
        assertTrue(params.single().required)
    }

    @Test
    fun `applyGitSync with null declarations leaves existing parameters intact`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val created = withRequest {
            service.addQuery(
                AnalyticsQueryInput(
                    key = "gitsync-softmode",
                    name = "n", description = "d",
                    query = "select 1",
                    parameters = listOf(
                        AnalyticsQueryParameterInput(
                            parameter = "keepMe", name = "Keep", description = "",
                            type = QueryParameterType.STRING, arrayType = null,
                        )
                    ),
                )
            )
        }

        val updated = withRequest { service.applyGitSync(created.id, "select 2", null) }

        assertNotNull(updated)
        assertEquals("select 2", updated.query)
        assertEquals(created.cacheGeneration + 1, updated.cacheGeneration)
        val params = withRequest { service.getParameters(created.id) }
        assertEquals(1, params.size)
        assertEquals("keepMe", params.single().parameter)
    }

    @Test
    fun `applyGitSync with empty declarations clears all parameters`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val created = withRequest {
            service.addQuery(
                AnalyticsQueryInput(
                    key = "gitsync-clear",
                    name = "n", description = "d",
                    query = "select 1",
                    parameters = listOf(
                        AnalyticsQueryParameterInput(
                            parameter = "wipeMe", name = "W", description = "",
                            type = QueryParameterType.STRING, arrayType = null,
                        )
                    ),
                )
            )
        }

        val updated = withRequest { service.applyGitSync(created.id, "select 1", emptyList()) }

        val params = withRequest { service.getParameters(created.id) }
        assertTrue(params.isEmpty(), "all parameters should be cleared")
        assertEquals(created.cacheGeneration + 1, updated?.cacheGeneration)
    }

    @Test
    fun `applyGitSync returns null when the query does not exist`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val result = withRequest {
            service.applyGitSync(kotlin.uuid.Uuid.random(), "select 1", null)
        }
        assertNull(result)
    }

    @Test
    fun `parser roundtrip restores DB parameters from rendered SQL`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val created = withRequest {
            service.addQuery(
                AnalyticsQueryInput(
                    key = "gitsync-roundtrip",
                    name = "n", description = "d",
                    query = "select * from events where created >= :startDate",
                    parameters = listOf(
                        AnalyticsQueryParameterInput(
                            parameter = "startDate",
                            name = "Start",
                            description = "Range start",
                            type = QueryParameterType.DATE,
                            arrayType = null,
                            required = true,
                        )
                    ),
                )
            )
        }
        val storedParams = withRequest { service.getParameters(created.id) }
        val declarations = storedParams.map {
            bosca.analytics.query.QueryParameterDeclaration(
                parameter = it.parameter,
                name = it.name,
                description = it.description,
                type = it.type,
                arrayType = it.arrayType,
                defaultValue = it.defaultValue,
                required = it.required,
                sort = it.sort,
            )
        }

        // Render as if writing back to git, then parse as if receiving a push, then
        // apply via the canonical sync path — the round trip must restore exactly
        // the same SQL and parameters.
        val rendered = bosca.analytics.query.QuerySourceCodec.render(created.query, declarations)
        val parsed = bosca.analytics.query.QuerySourceCodec.parse(rendered)

        withRequest { service.applyGitSync(created.id, parsed.cleanSql, parsed.parameters) }

        val after = withRequest { service.getQueryById(created.id) }
        val afterParams = withRequest { service.getParameters(created.id) }
        assertEquals(created.query, after.query)
        assertEquals(1, afterParams.size)
        assertEquals("startDate", afterParams.single().parameter)
        assertEquals(QueryParameterType.DATE, afterParams.single().type)
        assertTrue(afterParams.single().required)
    }
}
