package bosca.analytics.graphql

import bosca.analytics.model.AnalyticsQuery
import bosca.analytics.model.AnalyticsQueryParameter
import bosca.analytics.model.AnalyticsQueryColumn
import bosca.analytics.model.QueryParameterType
import bosca.analytics.security.AnalyticsQueryPermissionEvaluator
import bosca.analytics.service.AnalyticsQueryExecutionService
import bosca.analytics.service.AnalyticsQueryService
import bosca.serialization.UUID
import bosca.security.model.PermissionAction
import bosca.security.model.EntityPermission
import bosca.security.service.AuthenticationContext
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AnalyticsQueryControllerTest {

    private val queriesService = mockk<AnalyticsQueryService>()
    private val executionService = mockk<AnalyticsQueryExecutionService>(relaxed = true)
    private val permissionEvaluator = mockk<AnalyticsQueryPermissionEvaluator>()
    private val controller = AnalyticsQueryController(queriesService, executionService, permissionEvaluator)

    private val queryId = UUID.random()
    private val query = AnalyticsQuery(
        id = queryId,
        key = "dau",
        name = "Daily Active Users",
        description = "DAU over last 30 days",
        query = "SELECT count(*) FROM events",
        configuration = JsonPrimitive("chart-options")
    )

    @Test
    fun `id delegates to model field`() {
        assertEquals(queryId, controller.id(query))
    }

    @Test
    fun `key delegates to model field`() {
        assertEquals("dau", controller.key(query))
    }

    @Test
    fun `name delegates to model field`() {
        assertEquals("Daily Active Users", controller.name(query))
    }

    @Test
    fun `description delegates to model field`() {
        assertEquals("DAU over last 30 days", controller.description(query))
    }

    @Test
    fun `query delegates to model field`() {
        assertEquals("SELECT count(*) FROM events", controller.query(query))
    }

    @Test
    fun `configuration delegates to model field`() {
        assertEquals(JsonPrimitive("chart-options"), controller.configuration(query))
    }

    @Test
    fun `configuration returns null when not set`() {
        val noConfig = query.copy(configuration = null)
        assertNull(controller.configuration(noConfig))
    }

    @Test
    fun `parameters fetches from service by query id`() = runTest {
        val params = listOf(
            AnalyticsQueryParameter(
                queryId = queryId,
                parameter = "days",
                name = "Days",
                description = "Number of days",
                type = QueryParameterType.INTEGER,
                defaultValue = JsonPrimitive(30),
                required = true,
                sort = 0
            )
        )
        coEvery { queriesService.getParameters(queryId) } returns params

        val result = controller.parameters(query)

        assertEquals(1, result.size)
        assertEquals("days", result[0].parameter)
    }

    @Test
    fun `refresh interval and columns delegate to model and execution service`() = runTest {
        val cached = query.copy(refreshIntervalSeconds = 60)
        val columns = listOf(AnalyticsQueryColumn("value", "bigint", false))
        coEvery { executionService.getColumns(queryId) } returns columns

        assertEquals(60, controller.refreshIntervalSeconds(cached))
        assertEquals(columns, controller.columns(cached))
    }

    @Test
    fun `permissions are hidden without manage access`() = runTest {
        val auth = mockk<AuthenticationContext>()
        coEvery { permissionEvaluator.isAllowed(auth, query, PermissionAction.MANAGE) } returns false

        assertEquals(emptyList(), controller.permissions(auth, query))
    }

    @Test
    fun `permissions map service grants with manage access`() = runTest {
        val auth = mockk<AuthenticationContext>()
        val grant = mockk<EntityPermission>()
        val groupId = UUID.random()
        io.mockk.every { grant.groupId } returns groupId
        io.mockk.every { grant.action } returns PermissionAction.VIEW
        coEvery { permissionEvaluator.isAllowed(auth, query, PermissionAction.MANAGE) } returns true
        coEvery { queriesService.getPermissions(query) } returns listOf(grant)

        val result = controller.permissions(auth, query)

        assertEquals(groupId, result.single().groupId)
        assertEquals(PermissionAction.VIEW, result.single().action)
    }
}
