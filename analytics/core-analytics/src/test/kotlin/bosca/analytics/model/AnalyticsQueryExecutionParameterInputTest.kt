package bosca.analytics.model

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

class AnalyticsQueryExecutionParameterInputTest {

    @Test
    fun `AnalyticsQueryExecutionParameterInput stores parameter name`() {
        val input = AnalyticsQueryExecutionParameterInput(
            parameter = "date_range",
            value = JsonPrimitive("2024-01-01")
        )
        assertEquals("date_range", input.parameter)
    }

    @Test
    fun `AnalyticsQueryExecutionParameterInput stores string value`() {
        val input = AnalyticsQueryExecutionParameterInput(
            parameter = "name",
            value = JsonPrimitive("test")
        )
        assertEquals(JsonPrimitive("test"), input.value)
    }

    @Test
    fun `AnalyticsQueryExecutionParameterInput stores numeric value`() {
        val input = AnalyticsQueryExecutionParameterInput(
            parameter = "limit",
            value = JsonPrimitive(100)
        )
        assertEquals(JsonPrimitive(100), input.value)
    }

    @Test
    fun `AnalyticsQueryExecutionParameterInput stores boolean value`() {
        val input = AnalyticsQueryExecutionParameterInput(
            parameter = "active",
            value = JsonPrimitive(true)
        )
        assertEquals(JsonPrimitive(true), input.value)
    }

    @Test
    fun `AnalyticsQueryExecutionParameterInput stores object value`() {
        val obj = JsonObject(mapOf("key" to JsonPrimitive("val")))
        val input = AnalyticsQueryExecutionParameterInput(
            parameter = "config",
            value = obj
        )
        assertEquals(obj, input.value)
    }
}
