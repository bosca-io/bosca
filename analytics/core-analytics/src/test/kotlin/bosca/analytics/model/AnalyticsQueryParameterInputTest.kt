package bosca.analytics.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class AnalyticsQueryParameterInputTest {

    @Test
    fun `AnalyticsQueryParameterInput defaultValue defaults to null`() {
        val input = AnalyticsQueryParameterInput(
            parameter = "p", name = "n", description = "d",
            type = QueryParameterType.STRING, arrayType = null
        )
        assertNull(input.defaultValue)
    }

    @Test
    fun `AnalyticsQueryParameterInput required defaults to false`() {
        val input = AnalyticsQueryParameterInput(
            parameter = "p", name = "n", description = "d",
            type = QueryParameterType.INTEGER, arrayType = null
        )
        assertFalse(input.required)
    }

    @Test
    fun `AnalyticsQueryParameterInput stores all properties`() {
        val input = AnalyticsQueryParameterInput(
            parameter = "start_date", name = "Start Date",
            description = "The start date", type = QueryParameterType.DATE,
            arrayType = null, required = true
        )
        assertEquals("start_date", input.parameter)
        assertEquals("Start Date", input.name)
        assertEquals("The start date", input.description)
        assertEquals(QueryParameterType.DATE, input.type)
        assertNull(input.arrayType)
        assertEquals(true, input.required)
    }
}
