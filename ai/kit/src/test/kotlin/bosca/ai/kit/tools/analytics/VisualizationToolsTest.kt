package bosca.ai.kit.tools.analytics

import bosca.ai.chat.model.AnalyticsInvestigationKind
import bosca.ai.kit.agents.analytics.AnalyticsServices
import bosca.analytics.model.AnalyticsQuery
import bosca.analytics.model.AnalyticsQueryColumn
import bosca.analytics.model.AnalyticsVisualization
import bosca.analytics.model.AnalyticsVisualizationInput
import bosca.analytics.model.AnalyticsVisualizationType
import bosca.analytics.security.AnalyticsQueryPermissionEvaluator
import bosca.analytics.security.AnalyticsVisualizationPermissionEvaluator
import bosca.analytics.service.AnalyticsQueryExecutionService
import bosca.analytics.service.AnalyticsQueryService
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VisualizationToolsTest {
    private val authentication = mockk<AuthenticationContext>(relaxed = true)
    private val queryId = UUID.parse("550e8400-e29b-41d4-a716-446655440000")

    @Test
    fun `create validates configured columns and passes JsonElement unchanged`() = runTest {
        val queryService = mockk<AnalyticsQueryService>()
        val executionService = mockk<AnalyticsQueryExecutionService>()
        val visualizationService = mockk<AnalyticsVisualizationService>()
        val queryPermissions = mockk<AnalyticsQueryPermissionEvaluator>()
        val services = mockk<AnalyticsServices>()
        val captured = slot<AnalyticsVisualizationInput>()
        val configuration = buildJsonObject {
            put("x", "week")
            putJsonArray("y") { add("signups") }
        }
        every { services.queryService } returns queryService
        every { services.queryExecutionService } returns executionService
        every { services.visualizationService } returns visualizationService
        every { services.queryPermissionEvaluator } returns queryPermissions
        every { services.verifyCanManageAnalytics(authentication) } just runs
        coEvery { queryService.getQueryById(queryId) } returns AnalyticsQuery(queryId, "weekly", "Weekly", "", "SELECT 1")
        coEvery { queryPermissions.verifyAllowed(authentication, any(), PermissionAction.VIEW) } just runs
        coEvery { executionService.getColumns(queryId) } returns listOf(
            AnalyticsQueryColumn("week", "date", false), AnalyticsQueryColumn("signups", "bigint", false),
        )
        coEvery { visualizationService.getVisualizationByKey("weekly-signups") } returns null
        coEvery { visualizationService.addVisualization(capture(captured)) } answers {
            AnalyticsVisualization(
                id = UUID.parse("650e8400-e29b-41d4-a716-446655440000"), key = captured.captured.key,
                name = captured.captured.name, description = "", queryId = queryId,
                type = captured.captured.type, configuration = captured.captured.configuration,
            )
        }
        val recorder = InvestigationRecorder()

        val output = withContext(recorder) {
            CreateVisualizationTool(services).execute(
                authentication,
                CreateVisualizationTool.Input("Weekly Signups", type = AnalyticsVisualizationType.LINE, queryId = queryId.toString(), configuration = configuration),
            )
        }

        assertTrue(output.success)
        assertEquals(configuration, captured.captured.configuration)
        assertEquals(AnalyticsInvestigationKind.ARTIFACT, recorder.steps.single().kind)
    }

    @Test
    fun `create names a missing configured column and lists available columns`() = runTest {
        val queryService = mockk<AnalyticsQueryService>()
        val executionService = mockk<AnalyticsQueryExecutionService>()
        val queryPermissions = mockk<AnalyticsQueryPermissionEvaluator>()
        val services = mockk<AnalyticsServices>()
        every { services.queryService } returns queryService
        every { services.queryExecutionService } returns executionService
        every { services.queryPermissionEvaluator } returns queryPermissions
        every { services.verifyCanManageAnalytics(authentication) } just runs
        coEvery { queryService.getQueryById(queryId) } returns AnalyticsQuery(queryId, "weekly", "Weekly", "", "SELECT 1")
        coEvery { queryPermissions.verifyAllowed(authentication, any(), PermissionAction.VIEW) } just runs
        coEvery { executionService.getColumns(queryId) } returns listOf(AnalyticsQueryColumn("week", "date", false))

        val output = CreateVisualizationTool(services).execute(
            authentication,
            CreateVisualizationTool.Input(
                "Broken", type = AnalyticsVisualizationType.NUMBER, queryId = queryId.toString(),
                configuration = buildJsonObject { put("value", "missing_total") },
            ),
        )

        assertFalse(output.success)
        assertTrue(output.error.orEmpty().contains("missing_total"))
        assertTrue(output.error.orEmpty().contains("week"))
    }

    @Test
    fun `list get and update preserve identity validate columns and record their work`() = runTest {
        val visualizationId = UUID.parse("650e8400-e29b-41d4-a716-446655440000")
        val query = AnalyticsQuery(queryId, "weekly", "Weekly", "", "SELECT week, signups FROM weekly")
        val originalConfig = buildJsonObject { put("value", "signups") }
        val visualization = AnalyticsVisualization(
            visualizationId, "weekly-signups", "Weekly signups", "", queryId,
            AnalyticsVisualizationType.NUMBER, originalConfig,
        )
        val updatedConfig = buildJsonObject { put("x", "week"); putJsonArray("y") { add("signups") } }
        val updated = visualization.copy(name = "Weekly trend", type = AnalyticsVisualizationType.LINE, configuration = updatedConfig)
        val queryService = mockk<AnalyticsQueryService>()
        val executionService = mockk<AnalyticsQueryExecutionService>()
        val visualizationService = mockk<AnalyticsVisualizationService>()
        val queryPermissions = mockk<AnalyticsQueryPermissionEvaluator>()
        val visualizationPermissions = mockk<AnalyticsVisualizationPermissionEvaluator>()
        val services = mockk<AnalyticsServices>()
        val captured = slot<AnalyticsVisualizationInput>()
        every { services.queryService } returns queryService
        every { services.queryExecutionService } returns executionService
        every { services.visualizationService } returns visualizationService
        every { services.queryPermissionEvaluator } returns queryPermissions
        every { services.visualizationPermissionEvaluator } returns visualizationPermissions
        coEvery { visualizationService.getVisualizations(0, 50) } returns listOf(visualization)
        coEvery {
            visualizationPermissions.filterAllowed(authentication, listOf(visualization), PermissionAction.VIEW)
        } returns listOf(visualization)
        coEvery { visualizationService.getVisualizationByKey(visualization.key) } returns visualization
        coEvery { visualizationService.getVisualizationById(visualizationId) } returns visualization
        coEvery { visualizationPermissions.verifyAllowed(authentication, visualization, any()) } just runs
        coEvery { queryService.getQueryById(queryId) } returns query
        coEvery { queryPermissions.verifyAllowed(authentication, query, PermissionAction.VIEW) } just runs
        coEvery { executionService.getColumns(queryId) } returns listOf(
            AnalyticsQueryColumn("week", "date", false), AnalyticsQueryColumn("signups", "bigint", false),
        )
        coEvery { visualizationService.editVisualization(capture(captured)) } returns updated
        val recorder = InvestigationRecorder()

        val listed = withContext(recorder) {
            ListVisualizationsTool(services).execute(authentication, ListVisualizationsTool.Input())
        }
        val fetched = withContext(recorder) {
            GetVisualizationTool(services).execute(authentication, GetVisualizationTool.Input(key = visualization.key))
        }
        val edited = withContext(recorder) {
            UpdateVisualizationTool(services).execute(
                authentication,
                UpdateVisualizationTool.Input(
                    id = visualizationId.toString(), name = updated.name,
                    type = AnalyticsVisualizationType.LINE, configuration = updatedConfig,
                ),
            )
        }

        assertEquals(visualization.key, listed.visualizations.single().key)
        assertEquals(originalConfig, fetched.configuration)
        assertEquals(visualization.key, captured.captured.key)
        assertEquals(updatedConfig, captured.captured.configuration)
        assertEquals(updated.name, edited.name)
        assertEquals(
            listOf("list_visualizations", "get_visualization", "update_visualization"),
            recorder.steps.map { it.tool },
        )
    }
}
