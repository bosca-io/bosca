package bosca.analytics.graphql

import bosca.analytics.model.AnalyticsVisualization
import bosca.analytics.model.AnalyticsVisualizationPermission
import bosca.analytics.model.AnalyticsVisualizationType
import bosca.analytics.security.AnalyticsVisualizationPermissionEvaluator
import bosca.analytics.service.AnalyticsVisualizationService
import bosca.security.model.Permission
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AnalyticsVisualizationControllerTest {

    private val visualizationService = mockk<AnalyticsVisualizationService>()
    private val permissionEvaluator = mockk<AnalyticsVisualizationPermissionEvaluator>()
    private val controller = AnalyticsVisualizationController(visualizationService, permissionEvaluator)

    private val auth = mockk<AuthenticationContext>()
    private val queryId = UUID.random()
    private val vizId = UUID.random()
    private val groupId = UUID.random()
    private val configuration = JsonObject(mapOf("type" to JsonPrimitive("bar")))

    private val visualization = AnalyticsVisualization(
        id = vizId,
        key = "dau-chart",
        name = "DAU Chart",
        description = "Daily active users bar chart",
        queryId = queryId,
        type = AnalyticsVisualizationType.BAR,
        configuration = configuration
    )

    @Test
    fun `id delegates to model field`() {
        assertEquals(vizId, controller.id(visualization))
    }

    @Test
    fun `key delegates to model field`() {
        assertEquals("dau-chart", controller.key(visualization))
    }

    @Test
    fun `name delegates to model field`() {
        assertEquals("DAU Chart", controller.name(visualization))
    }

    @Test
    fun `description delegates to model field`() {
        assertEquals("Daily active users bar chart", controller.description(visualization))
    }

    @Test
    fun `queryId delegates to model field`() {
        assertEquals(queryId, controller.queryId(visualization))
    }

    @Test
    fun `type delegates to model field`() {
        assertEquals(AnalyticsVisualizationType.BAR, controller.type(visualization))
    }

    @Test
    fun `configuration delegates to model field`() {
        assertEquals(configuration, controller.configuration(visualization))
    }

    @Test
    fun `queryId can be null`() {
        val noQuery = visualization.copy(queryId = null)
        assertNull(controller.queryId(noQuery))
    }

    @Test
    fun `permissions returns mapped permissions when the caller can manage`() = runTest {
        coEvery { permissionEvaluator.isAllowed(auth, visualization, PermissionAction.MANAGE) } returns true
        coEvery { visualizationService.getPermissions(visualization) } returns listOf(
            AnalyticsVisualizationPermission(entityId = vizId, groupId = groupId, action = PermissionAction.VIEW),
        )

        val result = controller.permissions(auth, visualization)

        assertEquals(listOf(Permission(groupId, PermissionAction.VIEW)), result)
    }

    @Test
    fun `permissions returns an empty list when the caller cannot manage`() = runTest {
        coEvery { permissionEvaluator.isAllowed(auth, visualization, PermissionAction.MANAGE) } returns false

        val result = controller.permissions(auth, visualization)

        assertEquals(emptyList(), result)
        coVerify(exactly = 0) { visualizationService.getPermissions(any()) }
    }

    @Test
    fun `permissions returns an empty list for unauthenticated callers without access`() = runTest {
        coEvery { permissionEvaluator.isAllowed(null, visualization, PermissionAction.MANAGE) } returns false

        val result = controller.permissions(null, visualization)

        assertEquals(emptyList(), result)
    }
}
