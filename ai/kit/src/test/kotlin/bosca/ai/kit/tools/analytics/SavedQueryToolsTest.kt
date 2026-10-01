package bosca.ai.kit.tools.analytics

import bosca.ai.chat.model.AnalyticsInvestigationKind
import bosca.ai.kit.agents.analytics.AnalyticsServices
import bosca.analytics.model.AnalyticsQuery
import bosca.analytics.model.AnalyticsQueryColumn
import bosca.analytics.model.AnalyticsQueryResponse
import bosca.analytics.model.AnalyticsQueryInput
import bosca.analytics.model.QueryParameterType
import bosca.analytics.security.AnalyticsQueryPermissionEvaluator
import bosca.analytics.service.AnalyticsQueryExecutionService
import bosca.analytics.service.AnalyticsQueryService
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
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SavedQueryToolsTest {
    private val authentication = mockk<AuthenticationContext>(relaxed = true)

    @Test
    fun `create maps typed input, suffixes a colliding key, and records the artifact`() = runTest {
        val service = mockk<AnalyticsQueryService>()
        val services = mockk<AnalyticsServices>()
        val captured = slot<AnalyticsQueryInput>()
        every { services.queryService } returns service
        every { services.verifyCanManageAnalytics(authentication) } just runs
        coEvery { service.getQueryByKey("weekly-signups") } returns mockk()
        coEvery { service.getQueryByKey("weekly-signups-2") } returns null
        coEvery { service.addQuery(capture(captured)) } answers {
            AnalyticsQuery(
                id = UUID.parse("550e8400-e29b-41d4-a716-446655440000"),
                key = captured.captured.key,
                name = captured.captured.name,
                description = captured.captured.description,
                query = captured.captured.query,
                refreshIntervalSeconds = captured.captured.refreshIntervalSeconds,
            )
        }
        val recorder = InvestigationRecorder()

        val output = withContext(recorder) {
            CreateSavedQueryTool(services).execute(
                authentication,
                CreateSavedQueryTool.Input(
                    name = "Weekly Signups",
                    sql = "SELECT week, count(*) FROM signups GROUP BY week",
                    parameters = listOf(AnalyticsParameterInput("start", "Start", type = QueryParameterType.DATE, required = true)),
                    refreshIntervalSeconds = 3600,
                ),
            )
        }

        assertTrue(output.success)
        assertEquals("weekly-signups-2", captured.captured.key)
        assertEquals(QueryParameterType.DATE, captured.captured.parameters.single().type)
        assertEquals(3600, captured.captured.refreshIntervalSeconds)
        assertEquals(AnalyticsInvestigationKind.ARTIFACT, recorder.steps.single().kind)
        assertEquals("create_saved_query", recorder.steps.single().tool)
    }

    @Test
    fun `create returns an error envelope when authorization or service work fails`() = runTest {
        val services = mockk<AnalyticsServices>()
        every { services.verifyCanManageAnalytics(authentication) } throws IllegalStateException("not allowed")

        val output = CreateSavedQueryTool(services).execute(
            authentication,
            CreateSavedQueryTool.Input(name = "Denied", sql = "SELECT 1"),
        )

        assertFalse(output.success)
        assertEquals("not allowed", output.error)
    }

    @Test
    fun `list get update and execute map through services and record their work`() = runTest {
        val queryId = UUID.parse("550e8400-e29b-41d4-a716-446655440000")
        val query = AnalyticsQuery(queryId, "weekly", "Weekly", "Original", "SELECT week, total FROM weekly")
        val updated = query.copy(name = "Weekly totals", description = "Updated")
        val queryService = mockk<AnalyticsQueryService>()
        val executionService = mockk<AnalyticsQueryExecutionService>()
        val permissions = mockk<AnalyticsQueryPermissionEvaluator>()
        val services = mockk<AnalyticsServices>()
        val captured = slot<AnalyticsQueryInput>()
        every { services.queryService } returns queryService
        every { services.queryExecutionService } returns executionService
        every { services.queryPermissionEvaluator } returns permissions
        coEvery { queryService.getQueries(0, 50) } returns listOf(query)
        coEvery { permissions.filterAllowed(authentication, listOf(query), PermissionAction.VIEW) } returns listOf(query)
        coEvery { queryService.getQueryByKey(query.key) } returns query
        coEvery { queryService.getQueryById(queryId) } returns query
        coEvery { queryService.getParameters(queryId) } returns emptyList()
        coEvery { executionService.getColumns(queryId) } returns listOf(AnalyticsQueryColumn("total", "bigint", false))
        coEvery { permissions.verifyAllowed(authentication, query, any()) } just runs
        coEvery { queryService.editQuery(capture(captured)) } returns updated
        coEvery { executionService.execute(queryId, emptyList()) } returns AnalyticsQueryResponse(
            listOf(buildJsonObject { put("total", 7) }),
        )
        val recorder = InvestigationRecorder()

        val listed = withContext(recorder) { ListSavedQueriesTool(services).execute(authentication, ListSavedQueriesTool.Input()) }
        val fetched = withContext(recorder) { GetSavedQueryTool(services).execute(authentication, GetSavedQueryTool.Input(key = query.key)) }
        val edited = withContext(recorder) {
            UpdateSavedQueryTool(services).execute(
                authentication,
                UpdateSavedQueryTool.Input(id = queryId.toString(), name = updated.name, description = updated.description, parameters = emptyList()),
            )
        }
        val executed = withContext(recorder) {
            ExecuteSavedQueryTool(services).execute(authentication, ExecuteSavedQueryTool.Input(id = queryId.toString()))
        }

        assertEquals(query.key, listed.queries.single().key)
        assertEquals("total", fetched.columns.single().name)
        assertEquals(updated.name, captured.captured.name)
        assertEquals(updated.name, edited.name)
        assertEquals(1, executed.rowCount)
        assertEquals(
            listOf("list_saved_queries", "get_saved_query", "update_saved_query", "execute_saved_query"),
            recorder.steps.map { it.tool },
        )
        assertEquals(AnalyticsInvestigationKind.SAVED_QUERY, recorder.steps.last().kind)
    }
}
