package bosca.analytics.service

import bosca.analytics.model.AnalyticsQuery
import bosca.analytics.model.AnalyticsQueryExecutionParameterInput
import bosca.analytics.model.AnalyticsQueryParameter
import bosca.analytics.model.AnalyticsQueryResponse
import bosca.analytics.model.QueryParameterType
import bosca.db.ConnectionManager
import bosca.db.ConnectionPool
import bosca.di.ObjectProvider
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.ResultSetMetaData
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AnalyticsQueryExecutionServiceCoverageTest {
    private val queries = mockk<AnalyticsQueryService>()
    private val queryProvider = mockk<ObjectProvider<AnalyticsQueryService>>()
    private val cache = mockk<AnalyticsQueryResultCacheService>()
    private val cacheProvider = mockk<ObjectProvider<AnalyticsQueryResultCacheService>>()
    private val pool = mockk<ConnectionPool>()
    private val poolProvider = mockk<ObjectProvider<ConnectionPool>>()
    private val connection = mockk<ConnectionManager>(relaxed = true)
    private val statement = mockk<PreparedStatement>(relaxed = true)
    private val resultSet = mockk<ResultSet>(relaxed = true)
    private val metadata = mockk<ResultSetMetaData>()
    private val service = AnalyticsQueryExecutionServiceImpl(queryProvider, cacheProvider, poolProvider, Json)

    @AfterTest
    fun resetClock() {
        service.currentTimeMillis = System::currentTimeMillis
    }

    private fun query(id: UUID = UUID.random(), key: String = "query", sql: String = "select 1", refresh: Int? = null) =
        AnalyticsQuery(id = id, key = key, name = key, description = key, query = sql, refreshIntervalSeconds = refresh)

    private fun configureDatabase(rows: List<Any?> = listOf(1)) {
        coEvery { queryProvider.get() } returns queries
        coEvery { cacheProvider.get() } returns cache
        coEvery { poolProvider.get() } returns pool
        coEvery { pool.connection() } returns connection
        coEvery { connection.useStatement(any(), any<suspend (PreparedStatement) -> Any>()) } coAnswers {
            secondArg<suspend (PreparedStatement) -> Any>().invoke(statement)
        }
        coJustRun { connection.release() }
        every { statement.executeQuery() } returns resultSet
        every { resultSet.metaData } returns metadata
        every { metadata.columnCount } returns 1
        every { metadata.getColumnName(1) } returns "value"
        every { metadata.getColumnTypeName(1) } returns "integer"
        every { metadata.isNullable(1) } returns ResultSetMetaData.columnNullable
        every { resultSet.next() } returnsMany (rows.indices.map { true } + false)
        rows.forEachIndexed { index, value ->
            every { resultSet.getObject(1) } returns value
        }
        coEvery { queries.getParameters(any()) } returns emptyList()
    }

    @Test
    fun `key execution reports missing queries and delegates uncached queries`() = runTest {
        configureDatabase()
        coEvery { queries.getQueryByKey("missing") } returns null
        assertFailsWith<IllegalStateException> { service.execute("missing", emptyList()) }

        val query = query()
        coEvery { queries.getQueryByKey(query.key) } returns query
        coEvery { queries.getQueryById(query.id) } returns query
        assertEquals(JsonPrimitive(1), service.execute(query.key, emptyList()).records.single().jsonObject["value"])
        coVerify(exactly = 0) { cache.get(any<AnalyticsQuery>(), any()) }
    }

    @Test
    fun `query execution emits JDBC arrays as JSON arrays`() = runTest {
        val sqlArray = mockk<java.sql.Array>()
        every { sqlArray.array } returns arrayOf("default", "images")
        every { sqlArray.free() } returns Unit
        configureDatabase(listOf(sqlArray))
        val query = query()
        coEvery { queries.getQueryByKey(query.key) } returns query
        coEvery { queries.getQueryById(query.id) } returns query

        val value = service.execute(query.key, emptyList()).records.single().jsonObject["value"]

        assertEquals(JsonArray(listOf(JsonPrimitive("default"), JsonPrimitive("images"))), value)
        verify(exactly = 1) { sqlArray.free() }
    }

    @Test
    fun `cached execution returns hits and stores misses while refresh obeys query policy`() = runTest {
        configureDatabase()
        val cachedQuery = query(refresh = 60)
        val uncachedQuery = query(refresh = null)
        val hit = AnalyticsQueryResponse(listOf(JsonPrimitive("cached")))
        coEvery { queries.getQueryById(cachedQuery.id) } returns cachedQuery
        coEvery { queries.getQueryById(uncachedQuery.id) } returns uncachedQuery
        coEvery { cache.get(cachedQuery, emptyList()) } returns hit andThen null
        coJustRun { cache.store(any<AnalyticsQuery>(), any(), any(), any()) }

        assertEquals(hit, service.execute(cachedQuery.id, emptyList()))
        assertEquals(1, service.execute(cachedQuery.id, emptyList()).records.size)
        service.refresh(cachedQuery.id, emptyList())
        service.refresh(uncachedQuery.id, emptyList())

        coVerify(exactly = 1) { cache.store(cachedQuery, emptyList(), any(), callerAccess = true) }
        coVerify(exactly = 1) { cache.store(cachedQuery, emptyList(), any(), callerAccess = false) }
        coVerify(exactly = 0) { cache.store(uncachedQuery, emptyList(), any(), any()) }
    }

    @Test
    fun `column probing caches by definition fingerprint and reports nullability`() = runTest {
        configureDatabase(emptyList())
        val id = UUID.random()
        val original = query(id = id, sql = "select one")
        val edited = query(id = id, sql = "select two")
        coEvery { queries.getQueryById(id) } returns original andThen original andThen edited
        every { metadata.columnCount } returns 2
        every { metadata.getColumnName(1) } returns "required"
        every { metadata.getColumnName(2) } returns "optional"
        every { metadata.getColumnTypeName(any()) } returns "bigint"
        every { metadata.isNullable(1) } returns ResultSetMetaData.columnNoNulls
        every { metadata.isNullable(2) } returns ResultSetMetaData.columnNullable

        val first = service.getColumns(id)
        val cached = service.getColumns(id)
        val changed = service.getColumns(id)

        assertFalse(first[0].nullable)
        assertTrue(first[1].nullable)
        assertEquals(first, cached)
        assertEquals(first, changed)
        verify(exactly = 2) { statement.executeQuery() }
    }

    @Test
    fun `column cache fingerprint includes every parameter execution attribute`() = runTest {
        configureDatabase(emptyList())
        val id = UUID.random()
        val query = query(id = id)
        val original = AnalyticsQueryParameter(
            queryId = id,
            parameter = "limit",
            name = "Limit",
            description = "",
            type = QueryParameterType.INTEGER,
            defaultValue = JsonPrimitive(10),
            required = false,
            sort = 0,
        )
        val changed = original.copy(required = true, sort = 1)
        coEvery { queries.getQueryById(id) } returns query
        coEvery { queries.getParameters(id) } returns listOf(original) andThen listOf(original) andThen listOf(changed)

        service.getColumns(id)
        service.getColumns(id)
        service.getColumns(id)

        verify(exactly = 2) { statement.executeQuery() }
    }

    @Test
    fun `column cache remains bounded by evicting entries when capacity is exceeded`() = runTest {
        configureDatabase(emptyList())
        val ids = List(AnalyticsQueryExecutionServiceImpl.MAX_COLUMN_CACHE_ENTRIES + 1) { UUID.random() }
        coEvery { queries.getQueryById(any()) } answers { query(id = firstArg()) }
        var probes = 0
        every { statement.executeQuery() } answers {
            probes += 1
            resultSet
        }

        ids.forEach { service.getColumns(it) }
        ids.forEach { service.getColumns(it) }

        assertTrue(probes > ids.size)
    }

    @Test
    fun `column probe failures use a short negative-cache TTL and are retried`() = runTest {
        configureDatabase(emptyList())
        val query = query()
        coEvery { queries.getQueryById(query.id) } returns query
        var now = 1_000L
        service.currentTimeMillis = { now }
        every { statement.executeQuery() } throws IllegalStateException("Trino unavailable")

        assertEquals(emptyList(), service.getColumns(query.id))
        assertEquals(emptyList(), service.getColumns(query.id))
        now += AnalyticsQueryExecutionServiceImpl.FAILED_COLUMN_PROBE_TTL_MILLIS + 1
        every { statement.executeQuery() } returns resultSet
        assertEquals(1, service.getColumns(query.id).size)
        verify(exactly = 2) { statement.executeQuery() }
    }

    @Test
    fun `cache failures degrade to live execution and effective defaults share one identity`() = runTest {
        configureDatabase()
        val cachedQuery = query(refresh = 60)
        val declared = AnalyticsQueryParameter(
            queryId = cachedQuery.id,
            parameter = "limit",
            name = "Limit",
            description = "",
            type = QueryParameterType.INTEGER,
            defaultValue = JsonPrimitive(5),
            required = false,
            sort = 0,
        )
        coEvery { queries.getQueryById(cachedQuery.id) } returns cachedQuery
        coEvery { queries.getParameters(cachedQuery.id) } returns listOf(declared)
        coEvery { cache.get(cachedQuery, any()) } throws IllegalStateException("cache unavailable")
        coEvery { cache.store(cachedQuery, any(), any(), true) } throws IllegalStateException("cache unavailable")

        service.execute(
            cachedQuery.id,
            listOf(AnalyticsQueryExecutionParameterInput("limit", JsonNull)),
        )

        coVerify {
            cache.get(
                cachedQuery,
                match { it.single().parameter == "limit" && it.single().value == JsonPrimitive(5) },
            )
        }
    }

    @Test
    fun `cache identity uses supplied values defaults and explicit null fallback`() = runTest {
        configureDatabase()
        val query = query(refresh = 60)
        val supplied = AnalyticsQueryParameter(
            queryId = query.id,
            parameter = "supplied",
            name = "Supplied",
            description = "",
            type = QueryParameterType.INTEGER,
            defaultValue = JsonPrimitive(1),
            required = false,
            sort = 0,
        )
        val defaulted = supplied.copy(parameter = "defaulted", name = "Defaulted", defaultValue = JsonPrimitive(2), sort = 1)
        val nullable = supplied.copy(parameter = "nullable", name = "Nullable", defaultValue = null, sort = 2)
        val expected = listOf(
            AnalyticsQueryExecutionParameterInput("supplied", JsonPrimitive(9)),
            AnalyticsQueryExecutionParameterInput("defaulted", JsonPrimitive(2)),
            AnalyticsQueryExecutionParameterInput("nullable", JsonNull),
        )
        val hit = AnalyticsQueryResponse(listOf(JsonPrimitive("cached")))
        coEvery { queries.getQueryById(query.id) } returns query
        coEvery { queries.getParameters(query.id) } returns listOf(supplied, defaulted, nullable)
        coEvery {
            cache.get(
                query,
                match { actual ->
                    actual.map { it.parameter to it.value } == expected.map { it.parameter to it.value }
                },
            )
        } returns hit

        assertEquals(
            hit,
            service.execute(
                query.id,
                listOf(
                    AnalyticsQueryExecutionParameterInput("supplied", JsonPrimitive(9)),
                    AnalyticsQueryExecutionParameterInput("defaulted", JsonNull),
                ),
            ),
        )
    }

    @Test
    fun `unknown and duplicate execution parameters are rejected`() = runTest {
        configureDatabase()
        val query = query()
        coEvery { queries.getQueryById(query.id) } returns query

        assertFailsWith<IllegalArgumentException> {
            service.execute(query.id, listOf(AnalyticsQueryExecutionParameterInput("unknown", JsonPrimitive(1))))
        }

        val declared = AnalyticsQueryParameter(
            queryId = query.id,
            parameter = "known",
            name = "Known",
            description = "",
            type = QueryParameterType.INTEGER,
            defaultValue = null,
            required = false,
            sort = 0,
        )
        coEvery { queries.getParameters(query.id) } returns listOf(declared)
        assertFailsWith<IllegalArgumentException> {
            service.execute(
                query.id,
                listOf(
                    AnalyticsQueryExecutionParameterInput("known", JsonPrimitive(1)),
                    AnalyticsQueryExecutionParameterInput("known", JsonPrimitive(2)),
                ),
            )
        }
    }
}
