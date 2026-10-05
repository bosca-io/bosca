package bosca.ai.kit.tools.analytics

import bosca.ai.chat.model.AnalyticsInvestigationKind
import bosca.ai.kit.agents.analytics.AnalyticsServices
import bosca.analytics.model.AnalyticsDashboard
import bosca.analytics.model.AnalyticsDashboardInput
import bosca.analytics.model.AnalyticsVisualization
import bosca.analytics.model.AnalyticsVisualizationInstance
import bosca.analytics.model.AnalyticsVisualizationType
import bosca.analytics.security.AnalyticsDashboardPermissionEvaluator
import bosca.analytics.security.AnalyticsVisualizationPermissionEvaluator
import bosca.analytics.service.AnalyticsDashboardService
import bosca.analytics.service.AnalyticsVisualizationService
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DashboardToolsTest {
    private val authentication = mockk<AuthenticationContext>(relaxed = true)
    private val dashboardId = UUID.parse("550e8400-e29b-41d4-a716-446655440000")
    private val visualizationId = UUID.parse("650e8400-e29b-41d4-a716-446655440000")

    @Test
    fun `add visualization appends below current layout using Studio defaults`() = runTest {
        val dashboardService = mockk<AnalyticsDashboardService>()
        val visualizationService = mockk<AnalyticsVisualizationService>()
        val dashboardPermissions = mockk<AnalyticsDashboardPermissionEvaluator>()
        val visualizationPermissions = mockk<AnalyticsVisualizationPermissionEvaluator>()
        val services = mockk<AnalyticsServices>()
        val capturedConfiguration = slot<JsonElement>()
        val dashboard = AnalyticsDashboard(dashboardId, "growth", "Growth", "", JsonObject(emptyMap()))
        val visualization = AnalyticsVisualization(
            visualizationId, "weekly", "Weekly", "", type = AnalyticsVisualizationType.LINE, configuration = JsonObject(emptyMap()),
        )
        every { services.dashboardService } returns dashboardService
        every { services.visualizationService } returns visualizationService
        every { services.dashboardPermissionEvaluator } returns dashboardPermissions
        every { services.visualizationPermissionEvaluator } returns visualizationPermissions
        coEvery { dashboardService.getDashboardById(dashboardId) } returns dashboard
        coEvery { dashboardPermissions.verifyAllowed(authentication, dashboard, PermissionAction.MANAGE) } just runs
        coEvery { visualizationService.getVisualizationById(visualizationId) } returns visualization
        coEvery { visualizationPermissions.verifyAllowed(authentication, visualization, PermissionAction.VIEW) } just runs
        coEvery { dashboardService.getVisualizations(dashboardId) } returns listOf(
            AnalyticsVisualizationInstance(
                UUID.parse("750e8400-e29b-41d4-a716-446655440000"),
                buildJsonObject { put("y", 12); put("h", 6) }, visualization,
            ),
        )
        val instanceId = UUID.parse("850e8400-e29b-41d4-a716-446655440000")
        coEvery { dashboardService.addVisualization(dashboardId, visualizationId, capture(capturedConfiguration)) } returns instanceId
        val recorder = InvestigationRecorder()

        val output = withContext(recorder) {
            AddDashboardVisualizationTool(services).execute(
                authentication,
                AddDashboardVisualizationTool.Input(dashboardId.toString(), visualizationId.toString()),
            )
        }

        assertTrue(output.success)
        val placement = capturedConfiguration.captured as JsonObject
        assertEquals(18, placement["y"]!!.jsonPrimitive.int)
        assertEquals(24, placement["w"]!!.jsonPrimitive.int)
        assertEquals(12, placement["h"]!!.jsonPrimitive.int)
        assertEquals(AnalyticsInvestigationKind.ARTIFACT, recorder.steps.single().kind)
    }

    @Test
    fun `list get create update and remove map through services and record their work`() = runTest {
        val dashboardService = mockk<AnalyticsDashboardService>()
        val visualizationService = mockk<AnalyticsVisualizationService>()
        val dashboardPermissions = mockk<AnalyticsDashboardPermissionEvaluator>()
        val visualizationPermissions = mockk<AnalyticsVisualizationPermissionEvaluator>()
        val services = mockk<AnalyticsServices>()
        val configuration = buildJsonObject { put("theme", "light") }
        val dashboard = AnalyticsDashboard(dashboardId, "growth", "Growth", "", configuration)
        val visualization = AnalyticsVisualization(
            visualizationId, "weekly", "Weekly", "", type = AnalyticsVisualizationType.LINE,
            configuration = JsonObject(emptyMap()),
        )
        val instanceId = UUID.parse("750e8400-e29b-41d4-a716-446655440000")
        val instance = AnalyticsVisualizationInstance(instanceId, buildJsonObject { put("x", 0) }, visualization)
        val createdId = UUID.parse("850e8400-e29b-41d4-a716-446655440000")
        val created = AnalyticsDashboard(createdId, "new-dashboard", "New Dashboard", "", JsonObject(emptyMap()))
        val edited = dashboard.copy(name = "Growth metrics")
        val inputs = mutableListOf<AnalyticsDashboardInput>()
        every { services.dashboardService } returns dashboardService
        every { services.visualizationService } returns visualizationService
        every { services.dashboardPermissionEvaluator } returns dashboardPermissions
        every { services.visualizationPermissionEvaluator } returns visualizationPermissions
        every { services.verifyCanManageDashboards(authentication) } just runs
        coEvery { dashboardService.getDashboards(0, 50) } returns listOf(dashboard)
        coEvery { dashboardPermissions.filterAllowed(authentication, listOf(dashboard), PermissionAction.VIEW) } returns listOf(dashboard)
        coEvery { dashboardService.getDashboardByKey("growth") } returns dashboard
        coEvery { dashboardService.getDashboardByKey("new-dashboard") } returns null
        coEvery { dashboardService.getDashboardById(dashboardId) } returns dashboard
        coEvery { dashboardPermissions.verifyAllowed(authentication, dashboard, any()) } just runs
        coEvery { dashboardService.getVisualizations(dashboardId) } returns listOf(instance)
        coEvery {
            visualizationPermissions.isAllowed(authentication, visualization, PermissionAction.VIEW)
        } returns true
        coEvery { visualizationService.getVisualizationById(visualizationId) } returns visualization
        coEvery { visualizationPermissions.verifyAllowed(authentication, visualization, PermissionAction.VIEW) } just runs
        coEvery { dashboardService.addDashboard(capture(inputs)) } returns created
        coEvery { dashboardService.editDashboard(capture(inputs)) } returns edited
        coEvery { dashboardService.removeVisualization(instanceId) } just runs
        val recorder = InvestigationRecorder()

        val listed = withContext(recorder) { ListDashboardsTool(services).execute(authentication, ListDashboardsTool.Input()) }
        val fetched = withContext(recorder) { GetDashboardTool(services).execute(authentication, GetDashboardTool.Input(key = "growth")) }
        val createdOutput = withContext(recorder) {
            CreateDashboardTool(services).execute(authentication, CreateDashboardTool.Input(name = "New Dashboard"))
        }
        val editedOutput = withContext(recorder) {
            UpdateDashboardTool(services).execute(
                authentication,
                UpdateDashboardTool.Input(
                    id = dashboardId.toString(), name = edited.name,
                    visualizations = listOf(
                        UpdateDashboardTool.Visualization(visualizationId.toString(), instance.configuration),
                    ),
                ),
            )
        }
        val removed = withContext(recorder) {
            RemoveDashboardVisualizationTool(services).execute(
                authentication,
                RemoveDashboardVisualizationTool.Input(dashboardId.toString(), instanceId.toString()),
            )
        }

        assertEquals("growth", listed.dashboards.single().key)
        assertEquals(instanceId.toString(), fetched.visualizations.single().instanceId)
        assertEquals(createdId.toString(), createdOutput.id)
        assertEquals(edited.name, editedOutput.name)
        assertEquals(instanceId.toString(), removed.instanceId)
        assertEquals(2, inputs.size)
        assertEquals("new-dashboard", inputs.first().key)
        assertEquals("growth", inputs.last().key)
        assertEquals(
            listOf("list_dashboards", "get_dashboard", "create_dashboard", "update_dashboard", "remove_dashboard_visualization"),
            recorder.steps.map { it.tool },
        )
    }
}
