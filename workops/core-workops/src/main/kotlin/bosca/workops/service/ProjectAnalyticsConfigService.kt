package bosca.workops.service

import bosca.serialization.UUID
import bosca.service.Service
import bosca.workops.model.project.ProjectAnalyticsApplication
import bosca.workops.model.project.ProjectAnalyticsService

/**
 * Manages a project's analytics identifiers — the applications (the `sessions.<appId>` / error-group
 * appId) and services (the `http.<service>` name) it owns — used to scope release health telemetry. Named
 * "Config" to avoid clashing with the [ProjectAnalyticsService] entity it manages. Adds are idempotent.
 */
interface ProjectAnalyticsConfigService : Service {
    suspend fun listApplications(projectId: UUID): List<ProjectAnalyticsApplication>
    suspend fun addApplication(projectId: UUID, applicationId: String): ProjectAnalyticsApplication
    suspend fun removeApplication(projectId: UUID, id: UUID)
    suspend fun listServices(projectId: UUID): List<ProjectAnalyticsService>
    suspend fun addService(projectId: UUID, service: String): ProjectAnalyticsService
    suspend fun removeService(projectId: UUID, id: UUID)
}
