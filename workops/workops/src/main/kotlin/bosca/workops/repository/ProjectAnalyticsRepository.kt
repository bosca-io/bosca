package bosca.workops.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.UUID
import bosca.workops.model.project.ProjectAnalyticsApplication
import bosca.workops.model.project.ProjectAnalyticsService

/** Persists a project's analytics application/service identifiers (release health telemetry). */
@Repository
interface ProjectAnalyticsRepository {

    @Query("select * from workops.project_analytics_application where project_id = :projectId order by application_id")
    suspend fun listApplications(projectId: UUID): List<ProjectAnalyticsApplication>

    // Idempotent: re-adding the same (project, appId) returns the existing row rather than erroring.
    @Query(
        """
        insert into workops.project_analytics_application (project_id, application_id)
        values (:projectId, :applicationId)
        on conflict (project_id, application_id) do update set application_id = excluded.application_id
        returning *
        """,
    )
    suspend fun addApplication(projectId: UUID, applicationId: String): ProjectAnalyticsApplication

    @Query("delete from workops.project_analytics_application where id = :id and project_id = :projectId")
    suspend fun removeApplication(projectId: UUID, id: UUID)

    @Query("select * from workops.project_analytics_service where project_id = :projectId order by service")
    suspend fun listServices(projectId: UUID): List<ProjectAnalyticsService>

    @Query(
        """
        insert into workops.project_analytics_service (project_id, service)
        values (:projectId, :service)
        on conflict (project_id, service) do update set service = excluded.service
        returning *
        """,
    )
    suspend fun addService(projectId: UUID, service: String): ProjectAnalyticsService

    @Query("delete from workops.project_analytics_service where id = :id and project_id = :projectId")
    suspend fun removeService(projectId: UUID, id: UUID)
}
