package bosca.cli.analytics

import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.testing.test
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AnalyticsCommandTest {

    @Test
    fun `analytics command advertises query visualization dashboard and sample surfaces`() {
        val result = analyticsCommand().test("--help")

        assertEquals(0, result.statusCode)
        for (name in listOf("query", "visualization", "dashboard", "sample")) {
            assertTrue(name in result.stdout, "Expected '$name' in help: ${result.stdout}")
        }
    }

    @Test
    fun `query command advertises complete saved query workflow`() {
        val result = queryCommand().test("--help")

        assertEquals(0, result.statusCode)
        for (name in listOf("list", "get", "create", "update", "delete", "execute", "refresh", "permission")) {
            assertTrue(name in result.stdout, "Expected '$name' in help: ${result.stdout}")
        }
    }

    @Test
    fun `visualization and dashboard commands advertise rendering workflows`() {
        val visualization = visualizationCommand().test("--help")
        val dashboard = dashboardCommand().test("--help")

        assertEquals(0, visualization.statusCode)
        assertEquals(0, dashboard.statusCode)
        for (name in listOf("list", "get", "create", "update", "delete", "render", "permission")) {
            assertTrue(name in visualization.stdout, "Expected '$name' in visualization help")
            assertTrue(name in dashboard.stdout, "Expected '$name' in dashboard help")
        }
        assertTrue("add-visualization" in dashboard.stdout)
        assertTrue("remove-visualization" in dashboard.stdout)
    }

    private fun analyticsCommand() = AnalyticsCommand().subcommands(
        queryCommand(),
        visualizationCommand(),
        dashboardCommand(),
        AnalyticsSampleCommand(),
    )

    private fun queryCommand() = AnalyticsQueryCommand().subcommands(
        AnalyticsQueryListCommand(),
        AnalyticsQueryGetCommand(),
        AnalyticsQueryCreateCommand(),
        AnalyticsQueryUpdateCommand(),
        AnalyticsQueryDeleteCommand(),
        AnalyticsQueryExecuteCommand(),
        AnalyticsQueryRefreshCommand(),
        AnalyticsPermissionCommand().subcommands(
            AnalyticsQueryPermissionGrantCommand(),
            AnalyticsQueryPermissionRevokeCommand(),
        ),
    )

    private fun visualizationCommand() = AnalyticsVisualizationCommand().subcommands(
        AnalyticsVisualizationListCommand(),
        AnalyticsVisualizationGetCommand(),
        AnalyticsVisualizationCreateCommand(),
        AnalyticsVisualizationUpdateCommand(),
        AnalyticsVisualizationDeleteCommand(),
        AnalyticsVisualizationRenderCommand(),
        AnalyticsPermissionCommand().subcommands(
            AnalyticsVisualizationPermissionGrantCommand(),
            AnalyticsVisualizationPermissionRevokeCommand(),
        ),
    )

    private fun dashboardCommand() = AnalyticsDashboardCommand().subcommands(
        AnalyticsDashboardListCommand(),
        AnalyticsDashboardGetCommand(),
        AnalyticsDashboardCreateCommand(),
        AnalyticsDashboardUpdateCommand(),
        AnalyticsDashboardDeleteCommand(),
        AnalyticsDashboardAddVisualizationCommand(),
        AnalyticsDashboardRemoveVisualizationCommand(),
        AnalyticsDashboardRenderCommand(),
        AnalyticsPermissionCommand().subcommands(
            AnalyticsDashboardPermissionGrantCommand(),
            AnalyticsDashboardPermissionRevokeCommand(),
        ),
    )
}
