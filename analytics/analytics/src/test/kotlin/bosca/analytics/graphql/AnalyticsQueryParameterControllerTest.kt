package bosca.analytics.graphql

import bosca.analytics.model.AnalyticsQueryParameter
import bosca.analytics.model.QueryParameterType
import bosca.serialization.UUID
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnalyticsQueryParameterControllerTest {

    private val controller = AnalyticsQueryParameterController()

    private val param = AnalyticsQueryParameter(
        queryId = UUID.random(),
        parameter = "start_date",
        name = "Start Date",
        description = "The beginning of the date range",
        type = QueryParameterType.DATE,
        arrayType = QueryParameterType.NONE,
        defaultValue = JsonPrimitive("2024-01-01"),
        required = true,
        sort = 1
    )

    @Test
    fun `parameter delegates to model field`() {
        assertEquals("start_date", controller.parameter(param))
    }

    @Test
    fun `name delegates to model field`() {
        assertEquals("Start Date", controller.name(param))
    }

    @Test
    fun `description delegates to model field`() {
        assertEquals("The beginning of the date range", controller.description(param))
    }

    @Test
    fun `type delegates to model field`() {
        assertEquals(QueryParameterType.DATE, controller.type(param))
    }

    @Test
    fun `arrayType delegates to model field`() {
        assertEquals(QueryParameterType.NONE, controller.arrayType(param))
    }

    @Test
    fun `defaultValue delegates to model field`() {
        assertEquals(JsonPrimitive("2024-01-01"), controller.defaultValue(param))
    }

    @Test
    fun `required delegates to model field`() {
        assertTrue(controller.required(param))
    }

    @Test
    fun `sort delegates to model field`() {
        assertEquals(1, controller.sort(param))
    }

    @Test
    fun `defaultValue can be null`() {
        val noDefault = param.copy(defaultValue = null)
        assertNull(controller.defaultValue(noDefault))
    }
}
