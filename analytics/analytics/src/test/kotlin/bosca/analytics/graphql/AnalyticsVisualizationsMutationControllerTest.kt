package bosca.analytics.graphql

import bosca.analytics.model.AnalyticsVisualization
import bosca.analytics.model.AnalyticsVisualizationInput
import bosca.analytics.model.AnalyticsVisualizationPermission
import bosca.analytics.model.AnalyticsVisualizationType
import bosca.analytics.security.ANALYTICS_MANAGER_GROUP
import bosca.analytics.security.AnalyticsVisualizationPermissionEvaluator
import bosca.analytics.service.AnalyticsVisualizationService
import bosca.security.model.Permission
import bosca.security.model.PermissionAction
import bosca.security.model.PermissionInput
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.security.service.SecurityException
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AnalyticsVisualizationsMutationControllerTest {

    private val visualizationService = mockk<AnalyticsVisualizationService>()
    private val groupEvaluator = mockk<GroupEvaluator>()
    private val permissionEvaluator = mockk<AnalyticsVisualizationPermissionEvaluator>()
    private val controller = AnalyticsVisualizationsMutationController(
        visualizationService,
        groupEvaluator,
        permissionEvaluator,
    )

    private val auth = mockk<AuthenticationContext>()
    private val vizId = UUID.random()
    private val groupId = UUID.random()
    private val input = AnalyticsVisualizationInput(
        id = vizId,
        key = "k",
        name = "n",
        description = "d",
        type = AnalyticsVisualizationType.BAR,
        configuration = JsonNull,
    )
    private val visualization = AnalyticsVisualization(
        id = vizId,
        key = "k",
        name = "n",
        description = "d",
        type = AnalyticsVisualizationType.BAR,
        configuration = JsonNull,
    )
    private val permissionInput = PermissionInput(
        entityId = vizId,
        groupId = groupId,
        action = PermissionAction.VIEW,
    )
    private val storedPermission = AnalyticsVisualizationPermission(
        entityId = vizId,
        groupId = groupId,
        action = PermissionAction.VIEW,
    )

    private fun allowAdmin() {
        every { groupEvaluator.hasGroup(auth, ANALYTICS_MANAGER_GROUP) } returns false
        every { groupEvaluator.hasAdminGroup(auth) } returns true
    }

    private fun allowManager() {
        every { groupEvaluator.hasGroup(auth, ANALYTICS_MANAGER_GROUP) } returns true
    }

    private fun denyAdmin() {
        every { groupEvaluator.hasGroup(auth, ANALYTICS_MANAGER_GROUP) } returns false
        every { groupEvaluator.hasAdminGroup(auth) } returns false
        every { groupEvaluator.throwUnauthorized() } throws SecurityException("denied")
    }

    private fun allowManage() {
        coEvery { visualizationService.getVisualizationById(vizId) } returns visualization
        coJustRun { permissionEvaluator.verifyAllowed(auth, visualization, PermissionAction.MANAGE) }
    }

    private fun denyManage() {
        coEvery { visualizationService.getVisualizationById(vizId) } returns visualization
        coEvery {
            permissionEvaluator.verifyAllowed(auth, visualization, PermissionAction.MANAGE)
        } throws SecurityException("denied")
    }

    @Test
    fun `add delegates to the service for admins`() = runTest {
        allowAdmin()
        coEvery { visualizationService.addVisualization(input) } returns visualization

        val result = controller.add(auth, input)

        assertSame(visualization, result)
    }

    @Test
    fun `add delegates to the service for analytics managers`() = runTest {
        allowManager()
        coEvery { visualizationService.addVisualization(input) } returns visualization

        val result = controller.add(auth, input)

        assertSame(visualization, result)
    }

    @Test
    fun `add refuses unauthorized callers`() = runTest {
        denyAdmin()

        assertFailsWith<SecurityException> { controller.add(auth, input) }
        coVerify(exactly = 0) { visualizationService.addVisualization(any()) }
    }

    @Test
    fun `edit delegates to the service for admins`() = runTest {
        allowAdmin()
        coEvery { visualizationService.editVisualization(input) } returns visualization

        val result = controller.edit(auth, input)

        assertSame(visualization, result)
    }

    @Test
    fun `edit refuses unauthorized callers`() = runTest {
        denyAdmin()

        assertFailsWith<SecurityException> { controller.edit(auth, input) }
        coVerify(exactly = 0) { visualizationService.editVisualization(any()) }
    }

    @Test
    fun `edit delegates to the service for analytics managers`() = runTest {
        allowManager()
        coEvery { visualizationService.editVisualization(input) } returns visualization

        val result = controller.edit(auth, input)

        assertSame(visualization, result)
    }

    @Test
    fun `delete delegates to the service for admins`() = runTest {
        allowAdmin()
        coJustRun { visualizationService.deleteVisualizationById(vizId) }

        assertTrue(controller.delete(auth, vizId))
        coVerify { visualizationService.deleteVisualizationById(vizId) }
    }

    @Test
    fun `delete delegates to the service for analytics managers`() = runTest {
        allowManager()
        coJustRun { visualizationService.deleteVisualizationById(vizId) }

        assertTrue(controller.delete(auth, vizId))
        coVerify { visualizationService.deleteVisualizationById(vizId) }
    }

    @Test
    fun `delete refuses unauthorized callers`() = runTest {
        denyAdmin()

        assertFailsWith<SecurityException> { controller.delete(auth, vizId) }
        coVerify(exactly = 0) { visualizationService.deleteVisualizationById(any()) }
    }

    @Test
    fun `addPermission grants when the caller can manage the visualization`() = runTest {
        allowManage()
        coEvery { visualizationService.addPermission(permissionInput) } returns storedPermission

        val result = controller.addPermission(auth, permissionInput)

        assertEquals(Permission(groupId, PermissionAction.VIEW), result)
        coVerify { visualizationService.addPermission(permissionInput) }
    }

    @Test
    fun `addPermission refuses callers without manage access`() = runTest {
        denyManage()

        assertFailsWith<SecurityException> { controller.addPermission(auth, permissionInput) }
        coVerify(exactly = 0) { visualizationService.addPermission(any()) }
    }

    @Test
    fun `deletePermission revokes when the caller can manage the visualization`() = runTest {
        allowManage()
        coEvery { visualizationService.deletePermission(permissionInput) } returns storedPermission

        val result = controller.deletePermission(auth, permissionInput)

        assertEquals(Permission(groupId, PermissionAction.VIEW), result)
        coVerify { visualizationService.deletePermission(permissionInput) }
    }

    @Test
    fun `deletePermission refuses callers without manage access`() = runTest {
        denyManage()

        assertFailsWith<SecurityException> { controller.deletePermission(auth, permissionInput) }
        coVerify(exactly = 0) { visualizationService.deletePermission(any()) }
    }
}
