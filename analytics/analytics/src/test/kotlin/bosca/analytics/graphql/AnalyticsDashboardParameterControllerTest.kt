package bosca.analytics.graphql

import bosca.analytics.model.AnalyticsDashboardParameter
import bosca.analytics.model.QueryParameterType
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnalyticsDashboardParameterControllerTest {

    private val controller = AnalyticsDashboardParameterController()

    private val param = AnalyticsDashboardParameter(
        parameter = "date_range",
        name = "Date Range",
        description = "The date range filter for the dashboard",
        type = QueryParameterType.DATE,
        arrayType = QueryParameterType.NONE,
        defaultValue = JsonPrimitive("last-30-days"),
        required = true
    )

    @Test
    fun `parameter delegates to model field`() {
        assertEquals("date_range", controller.parameter(param))
    }

    @Test
    fun `name delegates to model field`() {
        assertEquals("Date Range", controller.name(param))
    }

    @Test
    fun `description delegates to model field`() {
        assertEquals("The date range filter for the dashboard", controller.description(param))
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
        assertEquals(JsonPrimitive("last-30-days"), controller.defaultValue(param))
    }

    @Test
    fun `required delegates to model field`() {
        assertTrue(controller.required(param))
    }

    @Test
    fun `defaultValue and arrayType can be null`() {
        val optional = AnalyticsDashboardParameter(
            parameter = "opt",
            name = "Optional",
            description = "An optional param",
            type = QueryParameterType.STRING,
            arrayType = null,
            defaultValue = null,
            required = false
        )
        assertNull(controller.defaultValue(optional))
        assertNull(controller.arrayType(optional))
        assertFalse(controller.required(optional))
    }
}
