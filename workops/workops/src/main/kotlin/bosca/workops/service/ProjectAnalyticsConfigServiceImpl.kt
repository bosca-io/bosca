package bosca.workops.service

import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import bosca.workops.model.project.ProjectAnalyticsApplication
import bosca.workops.model.project.ProjectAnalyticsService
import bosca.workops.repository.ProjectAnalyticsRepository

@ServiceImplementation
class ProjectAnalyticsConfigServiceImpl(
    private val repository: ProjectAnalyticsRepository,
) : ProjectAnalyticsConfigService {

    override suspend fun listApplications(projectId: UUID): List<ProjectAnalyticsApplication> =
        repository.listApplications(projectId)

    override suspend fun addApplication(projectId: UUID, applicationId: String): ProjectAnalyticsApplication =
        repository.addApplication(projectId, applicationId.trim())

    override suspend fun removeApplication(projectId: UUID, id: UUID) = repository.removeApplication(projectId, id)

    override suspend fun listServices(projectId: UUID): List<ProjectAnalyticsService> =
        repository.listServices(projectId)

    override suspend fun addService(projectId: UUID, service: String): ProjectAnalyticsService =
        repository.addService(projectId, service.trim())

    override suspend fun removeService(projectId: UUID, id: UUID) = repository.removeService(projectId, id)
}
