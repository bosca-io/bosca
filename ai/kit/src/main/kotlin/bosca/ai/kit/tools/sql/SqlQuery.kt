package bosca.ai.kit.tools.sql

import bosca.db.ConnectionPool
import bosca.db.use
import org.slf4j.LoggerFactory

/**
 * Read-only SQL access to the analytics warehouse (Trino) for the sql tools. Every entry
 * point validates identifiers and statements so the agent can only ever run SELECT/WITH
 * queries — no DDL/DML, no statements hidden in comments or string literals.
 */
class SqlQuery(private val trinoPool: ConnectionPool) {

    suspend fun listCatalogs(): List<String> = trinoPool.connection().use { manager ->
        manager.useReadOnlyStatement("SHOW CATALOGS") { stmt ->
            val result = mutableListOf<String>()
            stmt.executeQuery().use { rs ->
                while (rs.next()) {
                    result.add(rs.getString(1))
                }
            }
            result
        }
    }

    suspend fun listSchemas(catalog: String): List<String> = trinoPool.connection().use { manager ->
        validateIdentifier(catalog, "catalog")
        manager.useReadOnlyStatement("SHOW SCHEMAS FROM \"$catalog\"") { stmt ->
            val result = mutableListOf<String>()
            stmt.executeQuery().use { rs ->
                while (rs.next()) {
                    result.add(rs.getString(1))
                }
            }
            result
        }
    }

    suspend fun listTables(catalog: String, schema: String): List<String> = trinoPool.connection().use { manager ->
        validateIdentifier(catalog, "catalog")
        validateIdentifier(schema, "schema")
        manager.useReadOnlyStatement("SHOW TABLES FROM \"$catalog\".\"$schema\"") { stmt ->
            val result = mutableListOf<String>()
            stmt.executeQuery().use { rs ->
                while (rs.next()) {
                    result.add(rs.getString(1))
                }
            }
            result
        }
    }

    suspend fun describeTable(catalog: String, schema: String, table: String): List<Map<String, String>> = trinoPool.connection().use { manager ->
        validateIdentifier(catalog, "catalog")
        validateIdentifier(schema, "schema")
        validateIdentifier(table, "table")
        manager.useReadOnlyStatement("DESCRIBE \"$catalog\".\"$schema\".\"$table\"") { stmt ->
            val result = mutableListOf<Map<String, String>>()
            stmt.executeQuery().use { rs ->
                val metaData = rs.metaData
                val columnCount = metaData.columnCount
                while (rs.next()) {
                    val row = mutableMapOf<String, String>()
                    for (i in 1..columnCount) {
                        row[metaData.getColumnName(i)] = rs.getString(i) ?: ""
                    }
                    result.add(row)
                }
            }
            result
        }
    }

    suspend fun executeQuery(sql: String, maxRows: Int = DEFAULT_MAX_ROWS): List<Map<String, Any?>> {
        validateSelectStatement(sql)
        return trinoPool.connection().use { manager ->
            log.debug("Executing SQL query: {}", sql)
            manager.useReadOnlyStatement(sql) { stmt ->
                stmt.maxRows = maxRows
                val result = mutableListOf<Map<String, Any?>>()
                stmt.executeQuery().use { rs ->
                    val metaData = rs.metaData
                    val columnCount = metaData.columnCount
                    while (rs.next()) {
                        val row = mutableMapOf<String, Any?>()
                        for (i in 1..columnCount) {
                            row[metaData.getColumnName(i)] = rs.getObject(i)
                        }
                        result.add(row)
                    }
                }
                result
            }
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(SqlQuery::class.java)
        private const val DEFAULT_MAX_ROWS = 10000
        private val IDENTIFIER_PATTERN = Regex("^[a-zA-Z0-9_]+$")
        private val BLOCKED_KEYWORDS = Regex(
            "\\b(INSERT|UPDATE|DELETE|DROP|ALTER|CREATE|TRUNCATE|GRANT|REVOKE|MERGE|CALL|EXECUTE|EXPLAIN|SET|USE|PREPARE|DEALLOCATE|UNION)\\b",
            RegexOption.IGNORE_CASE,
        )

        fun validateIdentifier(value: String, label: String) {
            if (!IDENTIFIER_PATTERN.matches(value)) {
                error("Invalid $label name: $value")
            }
        }

        fun validateSelectStatement(sql: String) {
            // Strip comments to prevent hiding statements inside them
            val stripped = sql
                .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), " ")
                .replace(Regex("--[^\n]*"), " ")
                .trim()
            // Strip string literals to avoid false positives on keywords in values
            // Handles SQL-standard escaped quotes ('') inside string literals
            val withoutStrings = stripped.replace(Regex("'([^']|'')*'"), "''")
            if (BLOCKED_KEYWORDS.containsMatchIn(withoutStrings)) {
                error("Only SELECT queries are allowed; found disallowed keyword")
            }
            if (!stripped.startsWith("SELECT", ignoreCase = true) &&
                !stripped.startsWith("WITH", ignoreCase = true)
            ) {
                error("Query must start with SELECT or WITH")
            }
        }
    }
}
