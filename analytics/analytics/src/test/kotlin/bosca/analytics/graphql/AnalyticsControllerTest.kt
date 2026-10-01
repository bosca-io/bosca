package bosca.analytics.graphql

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertSame

class AnalyticsControllerTest {

    private val controller = AnalyticsController()

    @Test
    fun `Analytics is a singleton object`() {
        assertNotNull(Analytics)
        assertSame(Analytics, Analytics)
    }

    @Test
    fun `queries returns AnalyticsQueries singleton`() {
        val result = controller.queries()
        assertSame(AnalyticsQueries, result)
    }

    @Test
    fun `visualizations returns AnalyticsVisualizations singleton`() {
        val result = controller.visualizations()
        assertSame(AnalyticsVisualizations, result)
    }

    @Test
    fun `dashboards returns AnalyticsDashboards singleton`() {
        val result = controller.dashboards()
        assertSame(AnalyticsDashboards, result)
    }

    @Test
    fun `errors returns AnalyticsErrors singleton`() {
        val result = controller.errors()
        assertSame(AnalyticsErrors, result)
    }
}
