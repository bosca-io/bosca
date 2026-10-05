package bosca.analytics.graphql

import bosca.analytics.model.AnalyticsDashboard
import bosca.analytics.model.AnalyticsDashboardParameter
import bosca.analytics.model.AnalyticsQuery
import bosca.analytics.model.AnalyticsQueryColumn
import bosca.analytics.model.AnalyticsQueryResponse
import bosca.analytics.model.AnalyticsVisualization
import bosca.analytics.model.AnalyticsVisualizationInstance
import bosca.analytics.model.AnalyticsVisualizationType
import bosca.analytics.model.LiveSession
import bosca.analytics.model.QueryParameterType
import bosca.analytics.security.AnalyticsDashboardPermissionEvaluator
import bosca.analytics.security.AnalyticsQueryPermissionEvaluator
import bosca.analytics.security.AnalyticsVisualizationPermissionEvaluator
import bosca.analytics.service.AnalyticsDashboardService
import bosca.analytics.service.AnalyticsQueryExecutionService
import bosca.analytics.service.AnalyticsQueryService
import bosca.analytics.service.AnalyticsVisualizationService
import bosca.security.model.EntityPermission
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

class AnalyticsControllerCoverageTest {
    private val auth = mockk<AuthenticationContext>()

    private fun query(key: String = "query") = AnalyticsQuery(
        id = UUID.random(),
        key = key,
        name = key,
        description = key,
        query = "select 1",
    )

    private fun visualization(key: String = "visualization") = AnalyticsVisualization(
        id = UUID.random(),
        key = key,
        name = key,
        description = key,
        type = AnalyticsVisualizationType.TABLE,
        configuration = JsonObject(emptyMap()),
    )

    private fun dashboard(key: String = "dashboard", parameters: kotlinx.serialization.json.JsonElement? = null) =
        AnalyticsDashboard(
            id = UUID.random(),
            key = key,
            name = key,
            description = key,
            configuration = JsonObject(emptyMap()),
            parameters = parameters,
        )

    @Test
    fun `visualizations query filters lists and verifies keyed lookups`() = runTest {
        val service = mockk<AnalyticsVisualizationService>()
        val evaluator = mockk<AnalyticsVisualizationPermissionEvaluator>()
        val first = visualization("first")
        val second = visualization("second")
        val controller = AnalyticsVisualizationsController(service, evaluator)
        coEvery { service.getVisualizations(0, Int.MAX_VALUE) } returns listOf(first, second)
        coEvery { evaluator.isAllowed(auth, first, PermissionAction.VIEW) } returns true
        coEvery { evaluator.isAllowed(auth, second, PermissionAction.VIEW) } returns false
        coEvery { service.getVisualizationById(first.id) } returns first
        coEvery { service.getVisualizationByKey("first") } returns first
        coEvery { service.getVisualizationByKey("missing") } returns null
        coEvery { evaluator.verifyAllowed(auth, first, PermissionAction.VIEW) } returns Unit

        assertEquals(listOf(first), controller.all(auth))
        assertSame(first, controller.byId(auth, first.id))
        assertSame(first, controller.byKey(auth, "first"))
        assertNull(controller.byKey(auth, "missing"))
    }

    @Test
    fun `dashboards query filters lists and verifies keyed lookups`() = runTest {
        val service = mockk<AnalyticsDashboardService>()
        val evaluator = mockk<AnalyticsDashboardPermissionEvaluator>()
        val first = dashboard("first")
        val second = dashboard("second")
        val controller = AnalyticsDashboardsController(service, evaluator)
        coEvery { service.getDashboards(0, Int.MAX_VALUE) } returns listOf(first, second)
        coEvery { evaluator.isAllowed(auth, first, PermissionAction.VIEW) } returns true
        coEvery { evaluator.isAllowed(auth, second, PermissionAction.VIEW) } returns false
        coEvery { service.getDashboardById(first.id) } returns first
        coEvery { service.getDashboardByKey("first") } returns first
        coEvery { service.getDashboardByKey("missing") } returns null
        coEvery { evaluator.verifyAllowed(auth, first, PermissionAction.VIEW) } returns Unit

        assertEquals(listOf(first), controller.all(auth))
        assertSame(first, controller.byId(auth, first.id))
        assertSame(first, controller.byKey(auth, "first"))
        assertNull(controller.byKey(auth, "missing"))
    }

    @Test
    fun `queries query verifies reads and executions`() = runTest {
        val service = mockk<AnalyticsQueryService>()
        val execution = mockk<AnalyticsQueryExecutionService>()
        val evaluator = mockk<AnalyticsQueryPermissionEvaluator>()
        val query = query("first")
        val response = AnalyticsQueryResponse(listOf(JsonObject(mapOf("value" to JsonPrimitive(1)))))
        val controller = AnalyticsQueriesController(service, execution, evaluator)
        coEvery { service.getQueries(0, Int.MAX_VALUE) } returns listOf(query)
        coEvery { evaluator.filterAllowed(auth, listOf(query), PermissionAction.VIEW) } returns listOf(query)
        coEvery { service.getQueryById(query.id) } returns query
        coEvery { service.getQueryByKey("first") } returns query
        coEvery { service.getQueryByKey("missing") } returns null
        coEvery { evaluator.verifyAllowed(auth, query, any()) } returns Unit
        coEvery { execution.execute("first", emptyList()) } returns response
        coEvery { execution.execute(query.id, emptyList()) } returns response

        assertEquals(listOf(query), controller.all(auth))
        assertSame(query, controller.queryById(auth, query.id))
        assertSame(query, controller.queryByKey(auth, "first"))
        assertNull(controller.queryByKey(auth, "missing"))
        assertSame(response, controller.executeByKey(auth, "first", emptyList()))
        assertSame(response, controller.execute(auth, query.id, emptyList()))
        assertFailsWith<IllegalStateException> { controller.executeByKey(auth, "missing", emptyList()) }
    }

    @Test
    fun `dashboard type resolves parameters permissions and visible instances`() = runTest {
        val parameter = AnalyticsDashboardParameter(
            parameter = "date",
            name = "Date",
            description = "Date",
            type = QueryParameterType.DATE,
            arrayType = null,
            defaultValue = null,
            required = false,
        )
        val encoded = Json.encodeToJsonElement(
            kotlinx.serialization.builtins.ListSerializer(AnalyticsDashboardParameter.serializer()),
            listOf(parameter),
        )
        val dashboard = dashboard(parameters = encoded)
        val visible = AnalyticsVisualizationInstance(UUID.random(), JsonObject(emptyMap()), visualization("visible"))
        val hidden = AnalyticsVisualizationInstance(UUID.random(), JsonObject(emptyMap()), visualization("hidden"))
        val service = mockk<AnalyticsDashboardService>()
        val dashboardEvaluator = mockk<AnalyticsDashboardPermissionEvaluator>()
        val visualizationEvaluator = mockk<AnalyticsVisualizationPermissionEvaluator>()
        val controller = AnalyticsDashboardController(service, dashboardEvaluator, visualizationEvaluator)
        val grant = mockk<EntityPermission>()
        val groupId = UUID.random()
        every { grant.groupId } returns groupId
        every { grant.action } returns PermissionAction.VIEW
        coEvery { dashboardEvaluator.isAllowed(auth, dashboard, PermissionAction.MANAGE) } returnsMany listOf(false, true)
        coEvery { service.getPermissions(dashboard) } returns listOf(grant)
        coEvery { service.getVisualizations(dashboard.id) } returns listOf(visible, hidden)
        coEvery { visualizationEvaluator.isAllowed(auth, visible.visualization, PermissionAction.VIEW) } returns true
        coEvery { visualizationEvaluator.isAllowed(auth, hidden.visualization, PermissionAction.VIEW) } returns false

        assertEquals(dashboard.id, controller.id(dashboard))
        assertEquals(dashboard.key, controller.key(dashboard))
        assertEquals(dashboard.name, controller.name(dashboard))
        assertEquals(dashboard.description, controller.description(dashboard))
        assertEquals(dashboard.configuration, controller.configuration(dashboard))
        assertEquals(emptyList(), controller.parameters(dashboard.copy(parameters = null)))
        assertEquals("date", controller.parameters(dashboard).single().parameter)
        assertEquals(emptyList(), controller.permissions(auth, dashboard))
        assertEquals(groupId, controller.permissions(auth, dashboard).single().groupId)
        assertEquals(listOf(visible), controller.visualizations(auth, dashboard))
    }

    @Test
    fun `scalar response session and column controllers expose every field`() {
        val refreshedAt = OffsetDateTime.now()
        val response = AnalyticsQueryResponse(
            listOf(JsonPrimitive(1)),
            cached = true,
            refreshedAt = refreshedAt,
            stale = true,
        )
        val responseController = AnalyticsQueryResponseController()
        assertEquals(response.records, responseController.records(response))
        assertEquals(true, responseController.cached(response))
        assertEquals(true, responseController.stale(response))
        assertEquals(refreshedAt, responseController.refreshedAt(response))

        val session = LiveSession("session", 1.25, 2.5, "app", "1.0")
        val liveController = LiveSessionController()
        assertEquals("session", liveController.sessionId(session))
        assertEquals(1.25, liveController.lat(session))
        assertEquals(2.5, liveController.lon(session))
        assertEquals("app", liveController.appId(session))
        assertEquals("1.0", liveController.appVersion(session))

        val column = AnalyticsQueryColumn("value", "bigint", true)
        val columnController = AnalyticsQueryColumnController()
        assertEquals("value", columnController.name(column))
        assertEquals("bigint", columnController.typeName(column))
        assertEquals(true, columnController.nullable(column))
    }
}
