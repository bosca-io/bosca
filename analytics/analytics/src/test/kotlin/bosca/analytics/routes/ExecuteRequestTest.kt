package bosca.analytics.routes

import bosca.analytics.model.AnalyticsQueryExecutionParameterInput
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExecuteRequestTest {

    @Test
    fun `ExecuteRequest parameters defaults to empty list`() {
        val request = ExecuteRequest()
        assertTrue(request.parameters.isEmpty())
    }

    @Test
    fun `ExecuteRequest stores parameters`() {
        val params = listOf(
            AnalyticsQueryExecutionParameterInput(
                parameter = "date",
                value = JsonPrimitive("2024-01-01")
            )
        )
        val request = ExecuteRequest(parameters = params)
        assertEquals(1, request.parameters.size)
        assertEquals("date", request.parameters[0].parameter)
    }

    @Test
    fun `ExecuteRequest with multiple parameters`() {
        val params = listOf(
            AnalyticsQueryExecutionParameterInput(parameter = "a", value = JsonPrimitive(1)),
            AnalyticsQueryExecutionParameterInput(parameter = "b", value = JsonPrimitive("test")),
            AnalyticsQueryExecutionParameterInput(parameter = "c", value = JsonPrimitive(true))
        )
        val request = ExecuteRequest(parameters = params)
        assertEquals(3, request.parameters.size)
    }

    @Test
    fun `ExecuteRequest serializer covers omitted and explicit parameters`() {
        val json = Json { encodeDefaults = false }
        assertEquals(JsonObject(emptyMap()), json.encodeToJsonElement(ExecuteRequest.serializer(), ExecuteRequest()))
        val request = ExecuteRequest(listOf(AnalyticsQueryExecutionParameterInput("value", JsonPrimitive(1))))
        val encoded = json.encodeToString(ExecuteRequest.serializer(), request)
        assertEquals(1, json.decodeFromString(ExecuteRequest.serializer(), encoded).parameters.size)
        assertTrue(json.decodeFromString(ExecuteRequest.serializer(), "{}").parameters.isEmpty())
        assertTrue(
            Json { ignoreUnknownKeys = true }
                .decodeFromString(ExecuteRequest.serializer(), "{\"unknown\":1}")
                .parameters.isEmpty(),
        )
        assertTrue(
            Json { encodeDefaults = true }
                .encodeToJsonElement(ExecuteRequest.serializer(), ExecuteRequest())
                .toString().contains("parameters"),
        )
    }
}
