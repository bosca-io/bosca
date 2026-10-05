package bosca.analytics.graphql

import bosca.analytics.model.AnalyticsDashboard
import bosca.analytics.model.AnalyticsDashboardInput
import bosca.analytics.security.ANALYTICS_MANAGER_GROUP
import bosca.analytics.security.AnalyticsDashboardPermissionEvaluator
import bosca.analytics.service.AnalyticsDashboardService
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.security.service.SecurityException
import bosca.security.model.PermissionAction
import bosca.security.model.PermissionInput
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AnalyticsDashboardsMutationControllerTest {

    private val dashboardService = mockk<AnalyticsDashboardService>()
    private val groupEvaluator = mockk<GroupEvaluator>()
    private val permissionEvaluator = mockk<AnalyticsDashboardPermissionEvaluator>()
    private val controller = AnalyticsDashboardsMutationController(
        dashboardService,
        groupEvaluator,
        permissionEvaluator,
    )

    private val auth = mockk<AuthenticationContext>()
    private val dashboardId = UUID.random()
    private val visualizationId = UUID.random()
    private val input = mockk<AnalyticsDashboardInput>()
    private val dashboard = AnalyticsDashboard(
        id = dashboardId,
        key = "k",
        name = "n",
        description = "d",
        configuration = JsonNull,
    )

    private fun allowEditor() {
        every { groupEvaluator.hasGroup(auth, ANALYTICS_MANAGER_GROUP) } returns false
        every { groupEvaluator.hasEditorGroup(auth) } returns true
    }

    private fun allowManager() {
        every { groupEvaluator.hasGroup(auth, ANALYTICS_MANAGER_GROUP) } returns true
    }

    private fun denyAuthorization() {
        every { groupEvaluator.hasGroup(auth, ANALYTICS_MANAGER_GROUP) } returns false
        every { groupEvaluator.hasEditorGroup(auth) } returns false
        every { groupEvaluator.throwUnauthorized() } throws SecurityException("denied")
    }

    @Test
    fun `add delegates to the service for editors`() = runTest {
        allowEditor()
        coEvery { dashboardService.addDashboard(input) } returns dashboard

        assertSame(dashboard, controller.add(auth, input))
    }

    @Test
    fun `add delegates to the service for analytics managers`() = runTest {
        allowManager()
        coEvery { dashboardService.addDashboard(input) } returns dashboard

        assertSame(dashboard, controller.add(auth, input))
    }

    @Test
    fun `add refuses unauthorized callers`() = runTest {
        denyAuthorization()

        assertFailsWith<SecurityException> { controller.add(auth, input) }
        coVerify(exactly = 0) { dashboardService.addDashboard(any()) }
    }

    @Test
    fun `edit delegates to the service for analytics managers`() = runTest {
        allowManager()
        coEvery { dashboardService.editDashboard(input) } returns dashboard

        assertSame(dashboard, controller.edit(auth, input))
    }

    @Test
    fun `edit refuses unauthorized callers`() = runTest {
        denyAuthorization()

        assertFailsWith<SecurityException> { controller.edit(auth, input) }
        coVerify(exactly = 0) { dashboardService.editDashboard(any()) }
    }

    @Test
    fun `delete delegates to the service for analytics managers`() = runTest {
        allowManager()
        coJustRun { dashboardService.deleteDashboardById(dashboardId) }

        assertTrue(controller.delete(auth, dashboardId))
        coVerify { dashboardService.deleteDashboardById(dashboardId) }
    }

    @Test
    fun `delete refuses unauthorized callers`() = runTest {
        denyAuthorization()

        assertFailsWith<SecurityException> { controller.delete(auth, dashboardId) }
        coVerify(exactly = 0) { dashboardService.deleteDashboardById(any()) }
    }

    @Test
    fun `addVisualization delegates to the service for analytics managers`() = runTest {
        allowManager()
        val instanceId = UUID.random()
        coEvery { dashboardService.addVisualization(dashboardId, visualizationId, JsonNull) } returns instanceId

        assertSame(instanceId, controller.addVisualization(auth, dashboardId, visualizationId, JsonNull))
    }

    @Test
    fun `addVisualization refuses unauthorized callers`() = runTest {
        denyAuthorization()

        assertFailsWith<SecurityException> {
            controller.addVisualization(auth, dashboardId, visualizationId, JsonNull)
        }
        coVerify(exactly = 0) { dashboardService.addVisualization(any(), any(), any()) }
    }

    @Test
    fun `removeVisualization delegates to the service for analytics managers`() = runTest {
        allowManager()
        val instanceId = UUID.random()
        coJustRun { dashboardService.removeVisualization(instanceId) }

        assertTrue(controller.removeVisualization(auth, instanceId))
        coVerify { dashboardService.removeVisualization(instanceId) }
    }

    @Test
    fun `removeVisualization refuses unauthorized callers`() = runTest {
        denyAuthorization()

        assertFailsWith<SecurityException> { controller.removeVisualization(auth, UUID.random()) }
        coVerify(exactly = 0) { dashboardService.removeVisualization(any()) }
    }

    @Test
    fun `addPermission verifies entity management and delegates`() = runTest {
        val permission = PermissionInput(PermissionAction.VIEW, dashboardId, UUID.random())
        coEvery { dashboardService.getDashboardById(dashboardId) } returns dashboard
        coEvery { permissionEvaluator.verifyAllowed(auth, dashboard, PermissionAction.MANAGE) } returns Unit
        coJustRun { dashboardService.addPermission(permission) }

        val result = controller.addPermission(auth, permission)

        assertSame(permission.groupId, result.groupId)
        assertSame(permission.action, result.action)
    }

    @Test
    fun `deletePermission verifies entity management and delegates`() = runTest {
        val permission = PermissionInput(PermissionAction.VIEW, dashboardId, UUID.random())
        coEvery { dashboardService.getDashboardById(dashboardId) } returns dashboard
        coEvery { permissionEvaluator.verifyAllowed(auth, dashboard, PermissionAction.MANAGE) } returns Unit
        coJustRun { dashboardService.deletePermission(permission) }

        val result = controller.deletePermission(auth, permission)

        assertSame(permission.groupId, result.groupId)
        assertSame(permission.action, result.action)
    }
}
