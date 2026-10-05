package bosca.analytics.model

import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class AnalyticsQueryParameterTest {

    // --- AnalyticsQueryParameter ---

    @Test
    fun `AnalyticsQueryParameter arrayType defaults to NONE`() {
        val param = AnalyticsQueryParameter(
            queryId = Uuid.random(), parameter = "p", name = "n",
            description = "d", type = QueryParameterType.STRING,
            defaultValue = null, required = false, sort = 0
        )
        assertEquals(QueryParameterType.NONE, param.arrayType)
    }

    @Test
    fun `AnalyticsQueryParameter stores all properties`() {
        val queryId = Uuid.random()
        val param = AnalyticsQueryParameter(
            queryId = queryId, parameter = "start_date", name = "Start",
            description = "Start date", type = QueryParameterType.DATE,
            arrayType = QueryParameterType.NONE,
            defaultValue = JsonPrimitive("2024-01-01"), required = true, sort = 1
        )
        assertEquals(queryId, param.queryId)
        assertEquals("start_date", param.parameter)
        assertEquals("Start", param.name)
        assertEquals(QueryParameterType.DATE, param.type)
        assertTrue(param.required)
        assertEquals(1, param.sort)
    }

    // --- AnalyticsQueryParameterDate ---

    @Test
    fun `AnalyticsQueryParameterDate defaults`() {
        val date = AnalyticsQueryParameterDate()
        assertNull(date.value)
        assertFalse(date.now)
        assertNull(date.nowDayOffset)
    }

    @Test
    fun `AnalyticsQueryParameterDate with now flag`() {
        val date = AnalyticsQueryParameterDate(now = true, nowDayOffset = -7)
        assertTrue(date.now)
        assertEquals(-7, date.nowDayOffset)
    }

    // --- AnalyticsQueryExecutionParameterInput ---

    @Test
    fun `AnalyticsQueryExecutionParameterInput stores parameter and value`() {
        val value = JsonPrimitive("test-value")
        val input = AnalyticsQueryExecutionParameterInput(parameter = "search", value = value)
        assertEquals("search", input.parameter)
        assertEquals(value, input.value)
    }
}
