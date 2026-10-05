package bosca.ai.kit.tools.analytics

import bosca.ai.kit.agents.analytics.AnalyticsServices
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals

class AnalyticsToolDescriptorTest {
    @Test
    fun `all analytics artifact tool descriptors can be constructed and have unique names`() {
        val services = mockk<AnalyticsServices>(relaxed = true)

        val names = listOf(
            ListSavedQueriesTool(services).descriptor.name,
            GetSavedQueryTool(services).descriptor.name,
            CreateSavedQueryTool(services).descriptor.name,
            UpdateSavedQueryTool(services).descriptor.name,
            ExecuteSavedQueryTool(services).descriptor.name,
            ListVisualizationsTool(services).descriptor.name,
            GetVisualizationTool(services).descriptor.name,
            CreateVisualizationTool(services).descriptor.name,
            UpdateVisualizationTool(services).descriptor.name,
            ListDashboardsTool(services).descriptor.name,
            GetDashboardTool(services).descriptor.name,
            CreateDashboardTool(services).descriptor.name,
            UpdateDashboardTool(services).descriptor.name,
            AddDashboardVisualizationTool(services).descriptor.name,
            RemoveDashboardVisualizationTool(services).descriptor.name,
        )

        assertEquals(15, names.size)
        assertEquals(names.size, names.toSet().size)
    }
}
