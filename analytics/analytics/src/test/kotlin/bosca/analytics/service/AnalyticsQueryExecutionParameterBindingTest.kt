package bosca.analytics.service

import bosca.analytics.model.AnalyticsQueryExecutionParameterInput
import bosca.analytics.model.AnalyticsQueryParameter
import bosca.analytics.model.AnalyticsQueryParameterDate
import bosca.analytics.model.QueryParameterType
import bosca.db.ConnectionManager
import bosca.di.ObjectProvider
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.sql.PreparedStatement
import java.sql.Types
import kotlin.test.Test
import kotlin.test.assertFailsWith

class AnalyticsQueryExecutionParameterBindingTest {
    private val queryService = mockk<ObjectProvider<AnalyticsQueryService>>()
    private val resultCache = mockk<ObjectProvider<AnalyticsQueryResultCacheService>>()
    private val connectionPool = mockk<ObjectProvider<bosca.db.ConnectionPool>>()
    private val json = Json { encodeDefaults = true }
    private val service = AnalyticsQueryExecutionServiceImpl(queryService, resultCache, connectionPool, json)
    private val connection = mockk<ConnectionManager>()
    private val statement = mockk<PreparedStatement>(relaxed = true)

    private fun parameter(
        name: String,
        type: QueryParameterType,
        arrayType: QueryParameterType = QueryParameterType.NONE,
        defaultValue: kotlinx.serialization.json.JsonElement? = null,
    ) = AnalyticsQueryParameter(
        queryId = UUID.random(),
        parameter = name,
        name = name,
        description = name,
        type = type,
        arrayType = arrayType,
        defaultValue = defaultValue,
        required = false,
        sort = 0,
    )

    private fun wrapped(value: kotlinx.serialization.json.JsonElement) = JsonObject(mapOf("value" to value))

    private suspend fun bind(
        parameters: List<AnalyticsQueryParameter>,
        values: List<AnalyticsQueryExecutionParameterInput>,
    ) = service.bindParameters(connection, statement, parameters, values.associateBy { it.parameter })

    @Test
    fun `scalar parameters bind primitive wrapped default and invalid values`() = runTest {
        val parameters = listOf(
            parameter("string_primitive", QueryParameterType.STRING),
            parameter("string_wrapped", QueryParameterType.STRING),
            parameter("string_null", QueryParameterType.STRING),
            parameter("string_default", QueryParameterType.STRING, defaultValue = JsonPrimitive("default")),
            parameter("integer_primitive", QueryParameterType.INTEGER),
            parameter("integer_wrapped", QueryParameterType.INTEGER),
            parameter("integer_null", QueryParameterType.INTEGER),
            parameter("float_primitive", QueryParameterType.FLOAT),
            parameter("float_wrapped", QueryParameterType.FLOAT),
            parameter("float_null", QueryParameterType.FLOAT),
            parameter("boolean_primitive", QueryParameterType.BOOLEAN),
            parameter("boolean_wrapped", QueryParameterType.BOOLEAN),
            parameter("boolean_null", QueryParameterType.BOOLEAN),
            parameter("time_primitive", QueryParameterType.TIME),
            parameter("time_wrapped", QueryParameterType.TIME),
            parameter("time_null", QueryParameterType.TIME),
            parameter("object_value", QueryParameterType.OBJECT),
            parameter("object_null", QueryParameterType.OBJECT),
        )
        val values = listOf(
            AnalyticsQueryExecutionParameterInput("string_primitive", JsonPrimitive("one")),
            AnalyticsQueryExecutionParameterInput("string_wrapped", wrapped(JsonPrimitive("two"))),
            AnalyticsQueryExecutionParameterInput("string_null", JsonObject(emptyMap())),
            AnalyticsQueryExecutionParameterInput("string_default", JsonNull),
            AnalyticsQueryExecutionParameterInput("integer_primitive", JsonPrimitive(1)),
            AnalyticsQueryExecutionParameterInput("integer_wrapped", wrapped(JsonPrimitive(2))),
            AnalyticsQueryExecutionParameterInput("integer_null", JsonPrimitive("bad")),
            AnalyticsQueryExecutionParameterInput("float_primitive", JsonPrimitive(1.5)),
            AnalyticsQueryExecutionParameterInput("float_wrapped", wrapped(JsonPrimitive(2.5))),
            AnalyticsQueryExecutionParameterInput("float_null", JsonPrimitive("bad")),
            AnalyticsQueryExecutionParameterInput("boolean_primitive", JsonPrimitive(true)),
            AnalyticsQueryExecutionParameterInput("boolean_wrapped", wrapped(JsonPrimitive(false))),
            AnalyticsQueryExecutionParameterInput("boolean_null", JsonPrimitive("bad")),
            AnalyticsQueryExecutionParameterInput("time_primitive", JsonPrimitive(1_000L)),
            AnalyticsQueryExecutionParameterInput("time_wrapped", wrapped(JsonPrimitive(2_000L))),
            AnalyticsQueryExecutionParameterInput("time_null", JsonPrimitive("bad")),
            AnalyticsQueryExecutionParameterInput("object_value", wrapped(JsonObject(mapOf("x" to JsonPrimitive(1))))),
            AnalyticsQueryExecutionParameterInput("object_null", JsonObject(emptyMap())),
        )

        bind(parameters, values)

        verify { statement.setString(1, "one") }
        verify { statement.setString(2, "two") }
        verify { statement.setNull(3, Types.VARCHAR) }
        verify { statement.setString(4, "default") }
        verify { statement.setInt(5, 1) }
        verify { statement.setInt(6, 2) }
        verify { statement.setNull(7, Types.INTEGER) }
        verify { statement.setFloat(8, 1.5f) }
        verify { statement.setFloat(9, 2.5f) }
        verify { statement.setNull(10, Types.FLOAT) }
        verify { statement.setBoolean(11, true) }
        verify { statement.setBoolean(12, false) }
        verify { statement.setNull(13, Types.BOOLEAN) }
        verify { statement.setTime(14, java.sql.Time(1_000L)) }
        verify { statement.setTime(15, java.sql.Time(2_000L)) }
        verify { statement.setNull(16, Types.TIME) }
        verify { statement.setString(17, "{\"x\":1}") }
        verify { statement.setNull(18, Types.OTHER) }
    }

    @Test
    fun `date and datetime parameters cover current offsets explicit values and nulls`() = runTest {
        val explicit = OffsetDateTime.parse("2025-01-02T03:04:05Z")
        val parameters = listOf(
            parameter("date_now", QueryParameterType.DATE),
            parameter("date_offset", QueryParameterType.DATE),
            parameter("date_null", QueryParameterType.DATE),
            parameter("date_value", QueryParameterType.DATE),
            parameter("datetime_now", QueryParameterType.DATETIME),
            parameter("datetime_offset", QueryParameterType.DATETIME),
            parameter("datetime_null", QueryParameterType.DATETIME),
            parameter("datetime_value", QueryParameterType.DATETIME),
        )
        val values = listOf(
            AnalyticsQueryExecutionParameterInput("date_now", json.encodeToJsonElement(AnalyticsQueryParameterDate.serializer(), AnalyticsQueryParameterDate(now = true))),
            AnalyticsQueryExecutionParameterInput("date_offset", json.encodeToJsonElement(AnalyticsQueryParameterDate.serializer(), AnalyticsQueryParameterDate(now = true, nowDayOffset = 2))),
            AnalyticsQueryExecutionParameterInput("date_null", json.encodeToJsonElement(AnalyticsQueryParameterDate.serializer(), AnalyticsQueryParameterDate())),
            AnalyticsQueryExecutionParameterInput("date_value", json.encodeToJsonElement(AnalyticsQueryParameterDate.serializer(), AnalyticsQueryParameterDate(value = explicit))),
            AnalyticsQueryExecutionParameterInput("datetime_now", json.encodeToJsonElement(AnalyticsQueryParameterDate.serializer(), AnalyticsQueryParameterDate(now = true))),
            AnalyticsQueryExecutionParameterInput("datetime_offset", json.encodeToJsonElement(AnalyticsQueryParameterDate.serializer(), AnalyticsQueryParameterDate(now = true, nowDayOffset = -2))),
            AnalyticsQueryExecutionParameterInput("datetime_null", json.encodeToJsonElement(AnalyticsQueryParameterDate.serializer(), AnalyticsQueryParameterDate())),
            AnalyticsQueryExecutionParameterInput("datetime_value", json.encodeToJsonElement(AnalyticsQueryParameterDate.serializer(), AnalyticsQueryParameterDate(value = explicit))),
        )

        bind(parameters, values)

        verify { statement.setDate(1, any()) }
        verify { statement.setDate(2, any()) }
        verify { statement.setNull(3, Types.DATE) }
        verify { statement.setDate(4, java.sql.Date.valueOf(explicit.toLocalDate())) }
        verify { statement.setTimestamp(5, any()) }
        verify { statement.setObject(6, any<java.sql.Timestamp>()) }
        verify { statement.setNull(7, Types.TIMESTAMP) }
        verify { statement.setObject(8, java.sql.Timestamp.valueOf(explicit.toLocalDateTime())) }
    }

    @Test
    fun `all supported array element types are created and bound`() = runTest {
        val types = listOf(
            QueryParameterType.STRING,
            QueryParameterType.INTEGER,
            QueryParameterType.FLOAT,
            QueryParameterType.BOOLEAN,
            QueryParameterType.DATE,
            QueryParameterType.TIME,
            QueryParameterType.DATETIME,
        )
        val values = listOf(
            JsonPrimitive("value"),
            JsonPrimitive(1),
            JsonPrimitive(1.5),
            JsonPrimitive(true),
            JsonPrimitive(1_000L),
            JsonPrimitive(2_000L),
            JsonPrimitive(3_000L),
        )
        val sqlArrays = types.map { mockk<java.sql.Array>() }
        types.forEachIndexed { index, type ->
            coEvery { connection.createArrayOf<Any?>(any(), any()) } returns sqlArrays[index]
        }

        types.forEachIndexed { index, type ->
            val name = "array_$index"
            bind(
                listOf(parameter(name, QueryParameterType.ARRAY, arrayType = type)),
                listOf(AnalyticsQueryExecutionParameterInput(name, wrapped(JsonArray(listOf(values[index], JsonNull))))),
            )
            verify { statement.setArray(1, any()) }
        }
        coVerify(atLeast = 7) { connection.createArrayOf<Any?>(any(), any()) }
    }

    @Test
    fun `empty array payload binds an empty SQL array`() = runTest {
        val sqlArray = mockk<java.sql.Array>()
        coEvery { connection.createArrayOf<Any?>("varchar", any()) } returns sqlArray

        bind(listOf(parameter("values", QueryParameterType.ARRAY, QueryParameterType.STRING)), emptyList())

        verify { statement.setArray(1, sqlArray) }
    }

    @Test
    fun `unsupported array and top-level parameter types fail loudly`() = runTest {
        for (type in listOf(QueryParameterType.ARRAY, QueryParameterType.OBJECT, QueryParameterType.NONE)) {
            assertFailsWith<IllegalStateException> {
                bind(listOf(parameter("values", QueryParameterType.ARRAY, type)), emptyList())
            }
        }
        assertFailsWith<IllegalStateException> {
            bind(listOf(parameter("none", QueryParameterType.NONE)), emptyList())
        }
    }

    @Test
    fun `absent scalar object and malformed wrapped array values cover null binding paths`() = runTest {
        val parameters = listOf(
            parameter("integer", QueryParameterType.INTEGER),
            parameter("float", QueryParameterType.FLOAT),
            parameter("boolean", QueryParameterType.BOOLEAN),
            parameter("time", QueryParameterType.TIME),
            parameter("object", QueryParameterType.OBJECT),
        )
        bind(parameters, emptyList())
        verify { statement.setNull(1, Types.INTEGER) }
        verify { statement.setNull(2, Types.FLOAT) }
        verify { statement.setNull(3, Types.BOOLEAN) }
        verify { statement.setNull(4, Types.TIME) }
        verify { statement.setNull(5, Types.OTHER) }

        val array = mockk<java.sql.Array>()
        coEvery { connection.createArrayOf<Any?>("varchar", any()) } returns array
        bind(
            listOf(parameter("array", QueryParameterType.ARRAY, QueryParameterType.STRING)),
            listOf(AnalyticsQueryExecutionParameterInput("array", JsonObject(emptyMap()))),
        )
        verify { statement.setArray(1, array) }
    }

    @Test
    fun `date parameters reject an absent structured value`() = runTest {
        assertFailsWith<kotlinx.serialization.SerializationException> {
            bind(listOf(parameter("date", QueryParameterType.DATE)), emptyList())
        }
        assertFailsWith<kotlinx.serialization.SerializationException> {
            bind(listOf(parameter("datetime", QueryParameterType.DATETIME)), emptyList())
        }
    }

    @Test
    fun `wrapped collection parameters reject non-object defaults`() = runTest {
        assertFailsWith<IllegalArgumentException> {
            bind(
                listOf(parameter("array", QueryParameterType.ARRAY, QueryParameterType.STRING, JsonNull)),
                emptyList(),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            bind(listOf(parameter("object", QueryParameterType.OBJECT, defaultValue = JsonNull)), emptyList())
        }
        assertFailsWith<IllegalArgumentException> {
            bind(
                listOf(parameter("array", QueryParameterType.ARRAY, QueryParameterType.STRING)),
                listOf(AnalyticsQueryExecutionParameterInput("array", wrapped(JsonNull))),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            bind(
                listOf(parameter("object", QueryParameterType.OBJECT)),
                listOf(AnalyticsQueryExecutionParameterInput("object", wrapped(JsonNull))),
            )
        }
    }
}
