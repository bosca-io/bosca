package bosca.analytics.graphql

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertSame

class AnalyticsMutationControllerTest {

    private val controller = AnalyticsMutationController()

    @Test
    fun `AnalyticsMutation is a singleton object`() {
        assertNotNull(AnalyticsMutation)
        assertSame(AnalyticsMutation, AnalyticsMutation)
    }

    @Test
    fun `queries returns AnalyticsQueriesMutation singleton`() {
        val result = controller.queries()
        assertSame(AnalyticsQueriesMutation, result)
    }

    @Test
    fun `visualizations returns AnalyticsVisualizationsMutation singleton`() {
        val result = controller.visualizations()
        assertSame(AnalyticsVisualizationsMutation, result)
    }

    @Test
    fun `dashboards returns AnalyticsDashboardsMutation singleton`() {
        val result = controller.dashboards()
        assertSame(AnalyticsDashboardsMutation, result)
    }

    @Test
    fun `errors returns AnalyticsErrorsMutation singleton`() {
        val result = controller.errors()
        assertSame(AnalyticsErrorsMutation, result)
    }
}
