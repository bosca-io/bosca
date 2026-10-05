package bosca.workops.service

import bosca.serialization.UUID
import bosca.workops.model.project.ProjectAnalyticsApplication
import bosca.workops.model.project.ProjectAnalyticsService
import bosca.workops.repository.ProjectAnalyticsRepository
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ProjectAnalyticsConfigServiceTest {

    @Test
    fun `analytics configuration delegates project keys and trims new values`() = runTest {
        val repository = mockk<ProjectAnalyticsRepository>()
        val projectId = UUID.random()
        val application = ProjectAnalyticsApplication(UUID.random(), projectId, "studio")
        val analyticsService = ProjectAnalyticsService(UUID.random(), projectId, "bosca")
        coEvery { repository.listApplications(projectId) } returns listOf(application)
        coEvery { repository.addApplication(projectId, "studio") } returns application
        coEvery { repository.removeApplication(projectId, application.id) } just Runs
        coEvery { repository.listServices(projectId) } returns listOf(analyticsService)
        coEvery { repository.addService(projectId, "bosca") } returns analyticsService
        coEvery { repository.removeService(projectId, analyticsService.id) } just Runs
        val service = ProjectAnalyticsConfigServiceImpl(repository)

        assertEquals(listOf(application), service.listApplications(projectId))
        assertEquals(application, service.addApplication(projectId, "  studio  "))
        service.removeApplication(projectId, application.id)
        assertEquals(listOf(analyticsService), service.listServices(projectId))
        assertEquals(analyticsService, service.addService(projectId, "  bosca  "))
        service.removeService(projectId, analyticsService.id)

        coVerify(exactly = 1) { repository.removeApplication(projectId, application.id) }
        coVerify(exactly = 1) { repository.removeService(projectId, analyticsService.id) }
    }
}
