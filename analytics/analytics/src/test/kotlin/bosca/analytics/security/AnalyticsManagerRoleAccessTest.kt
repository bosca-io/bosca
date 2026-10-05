package bosca.analytics.security

import bosca.analytics.model.AnalyticsDashboard
import bosca.analytics.model.AnalyticsQuery
import bosca.analytics.model.AnalyticsVisualization
import bosca.analytics.model.AnalyticsVisualizationType
import bosca.analytics.service.AnalyticsDashboardService
import bosca.analytics.service.AnalyticsQueryService
import bosca.analytics.service.AnalyticsVisualizationService
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.security.service.SecurityService
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Verifies the analytics.manager group override in the analytics permission
 * evaluators: members get every action (including MANAGE and EXECUTE, which
 * the platform's generic role fallback withholds from editors), while
 * non-members without explicit grants stay denied.
 */
class AnalyticsManagerRoleAccessTest {

    private val securityService = mockk<SecurityService>()
    private val groupEvaluator = mockk<GroupEvaluator>()
    private val auth = mockk<AuthenticationContext>()

    private val query = AnalyticsQuery(
        id = UUID.random(),
        key = "k",
        name = "n",
        description = "d",
        query = "select 1",
        configuration = JsonNull,
    )
    private val visualization = AnalyticsVisualization(
        id = UUID.random(),
        key = "k",
        name = "n",
        description = "d",
        type = AnalyticsVisualizationType.BAR,
        configuration = JsonNull,
    )
    private val dashboard = AnalyticsDashboard(
        id = UUID.random(),
        key = "k",
        name = "n",
        description = "d",
        configuration = JsonNull,
    )

    private fun stubRoles(isManager: Boolean) {
        every { auth.principal() } returns null
        every { groupEvaluator.hasGroup(auth, ANALYTICS_MANAGER_GROUP) } returns isManager
        every { groupEvaluator.hasSaGroup(auth) } returns false
        every { groupEvaluator.hasAdminGroup(auth) } returns false
        every { groupEvaluator.hasEditorGroup(auth) } returns false
    }

    private fun queryEvaluator(): AnalyticsQueryPermissionEvaluator {
        val service = mockk<AnalyticsQueryService>()
        coEvery { service.getPermissions(query) } returns emptyList()
        coEvery { service.isParentAllowed(auth, query, any()) } returns false
        return AnalyticsQueryPermissionEvaluator(service, securityService, groupEvaluator)
    }

    private fun visualizationEvaluator(): AnalyticsVisualizationPermissionEvaluator {
        val service = mockk<AnalyticsVisualizationService>()
        coEvery { service.getPermissions(visualization) } returns emptyList()
        coEvery { service.isParentAllowed(auth, visualization, any()) } returns false
        return AnalyticsVisualizationPermissionEvaluator(service, securityService, groupEvaluator)
    }

    private fun dashboardEvaluator(): AnalyticsDashboardPermissionEvaluator {
        val service = mockk<AnalyticsDashboardService>()
        coEvery { service.getPermissions(dashboard) } returns emptyList()
        coEvery { service.isParentAllowed(auth, dashboard, any()) } returns false
        return AnalyticsDashboardPermissionEvaluator(service, securityService, groupEvaluator)
    }

    @Test
    fun `analytics manager can MANAGE queries`() = runTest {
        stubRoles(isManager = true)
        assertTrue(queryEvaluator().isAllowed(auth, query, PermissionAction.MANAGE))
    }

    @Test
    fun `analytics manager can EXECUTE queries`() = runTest {
        stubRoles(isManager = true)
        assertTrue(queryEvaluator().isAllowed(auth, query, PermissionAction.EXECUTE))
    }

    @Test
    fun `non-manager without grants cannot MANAGE queries`() = runTest {
        stubRoles(isManager = false)
        assertFalse(queryEvaluator().isAllowed(auth, query, PermissionAction.MANAGE))
    }

    @Test
    fun `analytics manager can MANAGE visualizations`() = runTest {
        stubRoles(isManager = true)
        assertTrue(visualizationEvaluator().isAllowed(auth, visualization, PermissionAction.MANAGE))
    }

    @Test
    fun `non-manager without grants cannot MANAGE visualizations`() = runTest {
        stubRoles(isManager = false)
        assertFalse(visualizationEvaluator().isAllowed(auth, visualization, PermissionAction.MANAGE))
    }

    @Test
    fun `analytics manager can MANAGE dashboards`() = runTest {
        stubRoles(isManager = true)
        assertTrue(dashboardEvaluator().isAllowed(auth, dashboard, PermissionAction.MANAGE))
    }

    @Test
    fun `non-manager without grants cannot MANAGE dashboards`() = runTest {
        stubRoles(isManager = false)
        assertFalse(dashboardEvaluator().isAllowed(auth, dashboard, PermissionAction.MANAGE))
    }
}
