package bosca.analytics.service

import bosca.db.ConnectionManager
import bosca.db.ConnectionPool
import bosca.di.asProvider
import io.mockk.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.ResultSetMetaData
import kotlin.test.*

class AnalyticsWarehouseExecutionTest {
    private val pool = mockk<ConnectionPool>()
    private val connection = mockk<ConnectionManager>(relaxed = true)
    private val statement = mockk<PreparedStatement>(relaxed = true)
    private val rows = mockk<ResultSet>(relaxed = true)
    private val metadata = mockk<ResultSetMetaData>()
    private val sql = mutableListOf<String>()
    private val service = AnalyticsQueryExecutionServiceImpl(mockk(), mockk(), pool.asProvider(), Json)

    @BeforeTest fun setup() {
        every { pool.connection() } returns connection
        coEvery { connection.useReadOnlyStatement(any(), any<suspend (PreparedStatement) -> Any>()) } coAnswers {
            sql += firstArg<String>()
            secondArg<suspend (PreparedStatement) -> Any>().invoke(statement)
        }
        every { statement.executeQuery() } returns rows
        every { rows.metaData } returns metadata
        every { metadata.columnCount } returns 1
        every { metadata.getColumnLabel(1) } returns "value"
        every { metadata.getColumnTypeName(1) } returns "bigint"
        every { metadata.isNullable(1) } returns ResultSetMetaData.columnNullable
        every { rows.next() } returnsMany listOf(true, false)
        every { rows.getObject(1) } returns 42L
        every { rows.getString(1) } returns "warehouse"
    }

    @Test fun `execution returns actual statement typed values empty metadata and accurate truncation`() = runTest {
        val statementSql = "/* analysis */ WITH result AS (SELECT 42 AS value) SELECT value FROM result"
        val result = service.executeSql(statementSql, 1)
        assertEquals(statementSql, result.sql)
        assertEquals(listOf(statementSql), sql)
        assertEquals(JsonPrimitive(42), result.records.single().jsonObject["value"])
        assertEquals("bigint", result.columns.single().typeName)
        assertTrue(result.columns.single().nullable)
        assertTrue(result.durationMs >= 0)
        assertFalse(result.truncated)
        verify { statement.maxRows = 2 }
        every { rows.next() } returnsMany listOf(true, true)
        assertTrue(service.executeSql("SELECT 42 AS value UNION ALL SELECT 43", 1).truncated)
        every { rows.next() } returns false
        val empty = service.executeSql("SELECT 42 AS value WHERE false", 1000)
        assertTrue(empty.records.isEmpty())
        assertEquals("value", empty.columns.single().name)
        assertFalse(empty.truncated)
        verify { rows.close() }
        coVerify { connection.release() }
    }

    @Test fun `every warehouse statement carries a query timeout`() = runTest {
        service.executeSql("SELECT 42 AS value", 1)
        service.describeTable("catalog", "schema", "table")
        service.listCatalogs(0, 1)
        verify(exactly = 3) { statement.queryTimeout = AnalyticsQueryExecutionServiceImpl.WAREHOUSE_QUERY_TIMEOUT_SECONDS }
    }

    @Test fun `parser rejects writes multi statements inline functions and session overrides before connecting`() = runTest {
        for (query in listOf("", "DELETE FROM t", "SELECT 1; DROP TABLE t", "SELECT 1; SELECT 2", "EXPLAIN SELECT 1", "CALL x()", "CREATE TABLE t AS SELECT 1", "WITH SESSION query_max_run_time='1h' SELECT 1", "WITH FUNCTION f() RETURNS bigint RETURN 1 SELECT f()", "WITH q AS (WITH FUNCTION f() RETURNS bigint RETURN 1 SELECT f()) SELECT * FROM q", "SELECT * FROM (WITH SESSION query_max_run_time='1h' SELECT 1)")) {
            assertFails { service.executeSql(query, 100) }
        }
        for (limit in listOf(0, -1, 1001)) assertFailsWith<IllegalArgumentException> { service.executeSql("SELECT 1", limit) }
        verify(exactly = 0) { pool.connection() }
    }

    @Test fun `quoted strings comments and quoted aliases do not masquerade as statements`() = runTest {
        for (query in listOf("SELECT 'DROP TABLE x; -- comment' AS value", "SELECT 1 AS \"delete\"", "SELECT 1 /* DROP TABLE x */", "SELECT 1 -- ; DELETE FROM t\n")) {
            every { rows.next() } returns false
            service.executeSql(query, 10)
        }
        assertEquals(4, sql.size)
    }

    @Test fun `discovery quotes exact identifiers and enforces pagination before accessing a connection`() = runTest {
        assertEquals(listOf("warehouse"), service.listCatalogs(0, 100))
        every { rows.next() } returnsMany listOf(true, true, false)
        assertEquals(listOf("warehouse"), service.listSchemas("a\";--", 1, 1))
        assertEquals("SHOW SCHEMAS FROM \"a\"\";--\"", sql.last())
        every { rows.next() } returns false
        assertTrue(service.listTables("warehouse", "special-schema", 0, 1).isEmpty())
        assertEquals("SHOW TABLES FROM \"warehouse\".\"special-schema\"", sql.last())
        every { metadata.isNullable(1) } returns ResultSetMetaData.columnNoNulls
        assertFalse(service.describeTable("warehouse", "bosca", "events").single().nullable)
        assertEquals("SELECT * FROM \"warehouse\".\"bosca\".\"events\" LIMIT 0", sql.last())
        every { rows.next() } returnsMany listOf(true, false, false)
        assertTrue(service.listCatalogs(20, 10).isEmpty())
        val before = sql.size
        for (identifier in listOf("", " ", "bad\nname")) assertFailsWith<IllegalArgumentException> { service.listSchemas(identifier, 0, 100) }
        assertFailsWith<IllegalArgumentException> { service.listCatalogs(-1, 100) }
        for (limit in listOf(0, 1001)) assertFailsWith<IllegalArgumentException> { service.listCatalogs(0, limit) }
        assertFailsWith<ArithmeticException> { service.listCatalogs(Int.MAX_VALUE, 10) }
        assertEquals(before, sql.size)
    }

    @Test fun `duplicate column labels fail rather than discard evidence and failures propagate`() = runTest {
        every { metadata.columnCount } returns 2
        every { metadata.getColumnLabel(2) } returns "value"
        every { metadata.getColumnTypeName(2) } returns "bigint"
        every { metadata.isNullable(2) } returns ResultSetMetaData.columnNullable
        assertFailsWith<IllegalArgumentException> { service.executeSql("SELECT 1, 2", 10) }
        every { statement.executeQuery() } throws CancellationException("cancelled")
        assertFailsWith<CancellationException> { service.executeSql("SELECT 1", 10) }
        every { statement.executeQuery() } throws IllegalStateException("warehouse unavailable")
        assertFailsWith<IllegalStateException> { service.listCatalogs(0, 100) }
    }
}
