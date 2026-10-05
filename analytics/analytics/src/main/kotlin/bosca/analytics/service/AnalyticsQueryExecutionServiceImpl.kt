package bosca.analytics.service

import bosca.analytics.model.AnalyticsQuery
import bosca.analytics.model.AnalyticsQueryColumn
import bosca.analytics.model.AnalyticsQueryExecutionParameterInput
import bosca.analytics.model.AnalyticsQueryParameter
import bosca.analytics.model.AnalyticsQueryParameterDate
import bosca.analytics.model.QueryParameterType
import bosca.analytics.model.AnalyticsQueryResponse
import bosca.db.ConnectionManager
import bosca.db.ConnectionPool
import bosca.db.query.QueryCompiler
import bosca.db.use
import bosca.di.ObjectProvider
import bosca.di.annotation.ProviderName
import bosca.serialization.JsonConverter.toJsonElement
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.slf4j.LoggerFactory
import java.sql.Date
import java.sql.PreparedStatement
import java.sql.ResultSetMetaData
import java.sql.Time
import java.sql.Timestamp
import java.sql.Types
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

@ServiceImplementation
class AnalyticsQueryExecutionServiceImpl(
    private val queryService: ObjectProvider<AnalyticsQueryService>,
    private val resultCache: ObjectProvider<AnalyticsQueryResultCacheService>,
    @ProviderName("trino-readonly")
    private val connectionPool: ObjectProvider<ConnectionPool>,
    private val json: kotlinx.serialization.json.Json
) : AnalyticsQueryExecutionService {

    private val log = LoggerFactory.getLogger(AnalyticsQueryExecutionServiceImpl::class.java)

    /** Bounded probe cache keyed by a collision-resistant executable-definition fingerprint. */
    private val columnCache = ConcurrentHashMap<UUID, ColumnCacheEntry>()
    internal var currentTimeMillis: () -> Long = System::currentTimeMillis

    override suspend fun execute(key: String, parameters: List<AnalyticsQueryExecutionParameterInput>): AnalyticsQueryResponse {
        val query = queryService.get().getQueryByKey(key) ?: error("Query not found: $key")
        return execute(query.id, parameters)
    }

    override suspend fun execute(id: UUID, parameters: List<AnalyticsQueryExecutionParameterInput>): AnalyticsQueryResponse {
        val query = queryService.get().getQueryById(id)
        val prepared = prepareExecution(query, parameters)
        if (query.refreshIntervalSeconds == null) {
            return executeQuery(query, prepared)
        }
        try {
            resultCache.get().get(query, prepared.effectiveParameters)?.let { return it }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Analytics query cache lookup failed for query {}; executing live", id, e)
        }
        val response = executeQuery(query, prepared)
        try {
            resultCache.get().store(query, prepared.effectiveParameters, response, callerAccess = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A successful analytics-store query must remain successful when the
            // optional cache backend is unavailable or misconfigured.
            log.warn("Analytics query cache store failed for query {}; returning live result", id, e)
        }
        return response
    }

    override suspend fun refresh(id: UUID, parameters: List<AnalyticsQueryExecutionParameterInput>): AnalyticsQueryResponse {
        val query = queryService.get().getQueryById(id)
        val prepared = prepareExecution(query, parameters)
        val response = executeQuery(query, prepared)
        if (query.refreshIntervalSeconds != null) {
            // Refresh failures stay visible to the job so the entry remains stale
            // and is retried rather than being counted as successfully refreshed.
            resultCache.get().store(query, prepared.effectiveParameters, response, callerAccess = false)
        }
        return response
    }

    override suspend fun getColumns(id: UUID): List<AnalyticsQueryColumn> {
        val query = queryService.get().getQueryById(id)
        val availableParameters = queryService.get().getParameters(query.id)
        val fingerprint = columnFingerprint(query, availableParameters)
        val now = currentTimeMillis()
        columnCache[id]?.let { entry ->
            if (entry.fingerprint == fingerprint && entry.expiresAtMillis > now) return entry.columns
        }
        var successful = true
        val columns = try {
            probeColumns(query, availableParameters)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            successful = false
            log.warn("Failed to probe columns for analytics query {} ({}): {}", query.key, id, e.message)
            emptyList()
        }
        if (!columnCache.containsKey(id) && columnCache.size >= MAX_COLUMN_CACHE_ENTRIES) {
            columnCache.keys.firstOrNull()?.let { columnCache.remove(it) }
        }
        columnCache[id] = ColumnCacheEntry(
            fingerprint = fingerprint,
            columns = columns,
            expiresAtMillis = if (successful) Long.MAX_VALUE else now + FAILED_COLUMN_PROBE_TTL_MILLIS,
        )
        return columns
    }

    /**
     * Reads the output column metadata of a query by running it wrapped in `SELECT * FROM (...) LIMIT 0`,
     * so Trino returns the result-set schema without computing any rows. Parameters are bound with their
     * defaults (the column shape does not depend on parameter values, but the SQL must still bind every
     * placeholder — e.g. queries using `OFFSET ? LIMIT ?` would fail with unbound parameters).
     */
    private suspend fun probeColumns(
        query: AnalyticsQuery,
        availableParameters: List<AnalyticsQueryParameter>,
    ): List<AnalyticsQueryColumn> = withContext(Dispatchers.IO) {
        val compiled = QueryCompiler.compileQuery(query.query, throwOnUnknown = false)
        val probeSql = "SELECT * FROM (\n${compiled.sql}\n) AS _bosca_probe LIMIT 0"
        connectionPool.get().connection().use { connection ->
            connection.useStatement(probeSql) { statement ->
                bindParameters(connection, statement, availableParameters, emptyMap())
                statement.executeQuery().use { resultSet ->
                    val metaData = resultSet.metaData
                    (1..metaData.columnCount).map { i ->
                        AnalyticsQueryColumn(
                            name = metaData.getColumnName(i),
                            typeName = metaData.getColumnTypeName(i),
                            nullable = metaData.isNullable(i) != ResultSetMetaData.columnNoNulls,
                        )
                    }
                }
            }
        }
    }

    private fun columnFingerprint(
        query: AnalyticsQuery,
        parameters: List<AnalyticsQueryParameter>,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(query.query.toByteArray(Charsets.UTF_8))
        for (parameter in parameters) {
            digest.update(0.toByte())
            digest.update(parameter.parameter.toByteArray(Charsets.UTF_8))
            digest.update(0.toByte())
            digest.update(parameter.type.name.toByteArray(Charsets.UTF_8))
            digest.update(0.toByte())
            digest.update(parameter.arrayType.name.toByteArray(Charsets.UTF_8))
            digest.update(0.toByte())
            digest.update(parameter.defaultValue.toString().toByteArray(Charsets.UTF_8))
            digest.update(0.toByte())
            digest.update(if (parameter.required) 1.toByte() else 0.toByte())
            digest.update(0.toByte())
            digest.update(parameter.sort.toString().toByteArray(Charsets.UTF_8))
            digest.update(0.toByte())
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private data class ColumnCacheEntry(
        val fingerprint: String,
        val columns: List<AnalyticsQueryColumn>,
        val expiresAtMillis: Long,
    )

    private suspend fun executeQuery(query: AnalyticsQuery, prepared: PreparedExecution): AnalyticsQueryResponse = withContext(Dispatchers.IO) {
        val parameterValues = prepared.effectiveParameters.associateBy { it.parameter }
        val compiled = QueryCompiler.compileQuery(query.query, throwOnUnknown = false)
        connectionPool.get().connection().use { connection ->
            connection.useStatement(compiled.sql) { statement ->
                bindParameters(connection, statement, prepared.availableParameters, parameterValues)

                statement.executeQuery().use { resultSet ->
                    val names = mutableMapOf<Int, String>()
                    val columnCount = resultSet.metaData.columnCount
                    (0 until columnCount).forEach { index ->
                        names[index] = resultSet.metaData.getColumnName(index + 1)
                    }
                    val results = mutableListOf<JsonElement>()
                    while (resultSet.next()) {
                        val map = mutableMapOf<String, Any?>()
                        for (index in 0 until columnCount) {
                            val columnName = names.getValue(index)
                            map[columnName] = resultSet.getObject(index + 1).toAnalyticsValue()
                        }
                        results += map.toJsonElement()
                    }
                    AnalyticsQueryResponse(results)
                }
            }
        }
    }

    private fun Any?.toAnalyticsValue(): Any? {
        if (this !is java.sql.Array) return this
        return try {
            (array as Array<*>).toList()
        } finally {
            free()
        }
    }

    /**
     * Produces the exact effective values used by both binding and cache identity.
     * Unknown and duplicate inputs are rejected instead of silently changing the
     * cache key while having no effect on execution.
     */
    private suspend fun prepareExecution(
        query: AnalyticsQuery,
        supplied: List<AnalyticsQueryExecutionParameterInput>,
    ): PreparedExecution {
        val available = queryService.get().getParameters(query.id)
        val duplicate = supplied.groupingBy { it.parameter }.eachCount().entries.firstOrNull { it.value > 1 }
        require(duplicate == null) { "Duplicate analytics query parameter: ${duplicate?.key}" }

        val availableNames = available.mapTo(mutableSetOf()) { it.parameter }
        val unknown = supplied.firstOrNull { it.parameter !in availableNames }
        require(unknown == null) { "Unknown analytics query parameter: ${unknown?.parameter}" }

        val suppliedByName = supplied.associateBy { it.parameter }
        val effective = available.map { parameter ->
            val suppliedValue = suppliedByName[parameter.parameter]?.value
            AnalyticsQueryExecutionParameterInput(
                parameter = parameter.parameter,
                value = suppliedValue?.takeIf { it !is JsonNull } ?: parameter.defaultValue ?: JsonNull,
            )
        }
        return PreparedExecution(available, effective)
    }

    private data class PreparedExecution(
        val availableParameters: List<AnalyticsQueryParameter>,
        val effectiveParameters: List<AnalyticsQueryExecutionParameterInput>,
    )

    /**
     * Binds the query's declared [availableParameters] onto [statement] in order, using the supplied
     * [parameterValues] (falling back to each parameter's default when absent or null). Shared by query
     * execution and the `LIMIT 0` column probe.
     */
    internal suspend fun bindParameters(
        connection: ConnectionManager,
        statement: PreparedStatement,
        availableParameters: List<AnalyticsQueryParameter>,
        parameterValues: Map<String, AnalyticsQueryExecutionParameterInput>,
    ) {
        availableParameters.forEachIndexed { index, parameter ->
            val parameterValue = parameterValues[parameter.parameter]?.value
            val value = parameterValue?.takeIf { it !is JsonNull } ?: parameter.defaultValue
            when (parameter.type) {
                QueryParameterType.STRING -> {
                    val value = value.scalarValue()?.contentOrNull
                    if (value == null) {
                        statement.setNull(index + 1, Types.VARCHAR)
                    } else {
                        statement.setString(index + 1, value)
                    }
                }

                QueryParameterType.INTEGER -> {
                    val value = value.scalarValue()?.intOrNull
                    if (value == null) {
                        statement.setNull(index + 1, Types.INTEGER)
                    } else {
                        statement.setInt(index + 1, value)
                    }
                }

                QueryParameterType.FLOAT -> {
                    val value = value.scalarValue()?.floatOrNull
                    if (value == null) {
                        statement.setNull(index + 1, Types.FLOAT)
                    } else {
                        statement.setFloat(index + 1, value)
                    }
                }

                QueryParameterType.BOOLEAN -> {
                    val value = value.scalarValue()?.booleanOrNull
                    if (value == null) {
                        statement.setNull(index + 1, Types.BOOLEAN)
                    } else {
                        statement.setBoolean(index + 1, value)
                    }
                }

                QueryParameterType.DATE -> {
                    val date = json.decodeFromJsonElement(AnalyticsQueryParameterDate.serializer(), value ?: JsonNull)
                    if (date.now) {
                        val now = OffsetDateTime.now()
                        val nowDayOffset = date.nowDayOffset
                        val target = if (nowDayOffset != null) {
                            now.plusDays(nowDayOffset.toLong())
                        } else {
                            now
                        }
                        statement.setDate(index + 1, Date.valueOf(target.toLocalDate()))
                    } else {
                        val dateValue = date.value
                        if (dateValue == null) {
                            statement.setNull(index + 1, Types.DATE)
                        } else {
                            statement.setDate(index + 1, Date.valueOf(dateValue.toLocalDate()))
                        }
                    }
                }

                QueryParameterType.TIME -> {
                    val value = value.scalarValue()?.longOrNull
                    if (value == null) {
                        statement.setNull(index + 1, Types.TIME)
                    } else {
                        statement.setTime(index + 1, Time(value))
                    }
                }

                QueryParameterType.DATETIME -> {
                    val date = json.decodeFromJsonElement(AnalyticsQueryParameterDate.serializer(), value ?: JsonNull)
                    if (date.now) {
                        val now = OffsetDateTime.now()
                        val nowDayOffset = date.nowDayOffset
                        if (nowDayOffset != null) {
                            statement.setObject(index + 1, Timestamp.valueOf(now.plusDays(nowDayOffset.toLong()).toLocalDateTime()))
                        } else {
                            statement.setTimestamp(index + 1, Timestamp.valueOf(now.toLocalDateTime()))
                        }
                    } else {
                        val dateValue = date.value
                        if (dateValue == null) {
                            statement.setNull(index + 1, Types.TIMESTAMP)
                        } else {
                            statement.setObject(index + 1, Timestamp.valueOf(dateValue.toLocalDateTime()))
                        }
                    }
                }

                QueryParameterType.ARRAY -> {
                    val (valueType, converter) = when (parameter.arrayType) {
                        QueryParameterType.STRING -> "varchar" to { item: JsonElement -> item.jsonPrimitive.contentOrNull }
                        QueryParameterType.INTEGER -> "int" to { item: JsonElement -> item.jsonPrimitive.intOrNull }
                        QueryParameterType.FLOAT -> "float" to { item: JsonElement -> item.jsonPrimitive.floatOrNull }
                        QueryParameterType.BOOLEAN -> "boolean" to { item: JsonElement -> item.jsonPrimitive.booleanOrNull }
                        QueryParameterType.DATE -> "date" to { item: JsonElement -> item.jsonPrimitive.longOrNull?.let(::Date) }
                        QueryParameterType.TIME -> "time" to { item: JsonElement -> item.jsonPrimitive.longOrNull?.let(::Time) }
                        QueryParameterType.DATETIME -> "timestamp" to { item: JsonElement -> item.jsonPrimitive.longOrNull?.let(::Timestamp) }
                        QueryParameterType.ARRAY -> error("Array of arrays not supported")
                        QueryParameterType.OBJECT -> error("Array of objects not supported")
                        else -> error("Invalid array type: ${parameter.arrayType}")
                    }
                    val value: List<Any?> = value.wrappedArray()?.map(converter) ?: emptyList()
                    statement.setArray(index + 1, connection.createArrayOf(valueType, value.toTypedArray()))
                }

                QueryParameterType.OBJECT -> {
                    val value = value.wrappedObject()?.toString()
                    if (value == null) {
                        statement.setNull(index + 1, Types.OTHER)
                    } else {
                        statement.setString(index + 1, value)
                    }
                }

                else -> error("Invalid parameter type: ${parameter.type}")
            }
        }
    }

    private fun JsonElement?.scalarValue(): JsonPrimitive? = when (this) {
        null -> null
        is JsonPrimitive -> this
        else -> jsonObject["value"]?.jsonPrimitive
    }

    private fun JsonElement?.wrappedArray(): JsonArray? {
        if (this == null) return null
        if (this !is JsonObject) throw IllegalArgumentException("Expected an array parameter envelope")
        val value = this["value"] ?: return null
        if (value !is JsonArray) throw IllegalArgumentException("Expected an array parameter value")
        return value
    }

    private fun JsonElement?.wrappedObject(): JsonObject? {
        if (this == null) return null
        if (this !is JsonObject) throw IllegalArgumentException("Expected an object parameter envelope")
        val value = this["value"] ?: return null
        if (value !is JsonObject) throw IllegalArgumentException("Expected an object parameter value")
        return value
    }

    companion object {
        internal const val MAX_COLUMN_CACHE_ENTRIES = 1_024
        internal const val FAILED_COLUMN_PROBE_TTL_MILLIS = 30_000L
    }
}
