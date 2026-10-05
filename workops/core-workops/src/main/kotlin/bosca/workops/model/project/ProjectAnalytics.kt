package bosca.workops.model.project

import bosca.db.annotation.ColumnName
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * An analytics **application** a project owns — the `appId` used in `sessions.<appId>` counters and in
 * error-group `appId` filters (e.g. `mobile`). A project can own several. Managed on the project; the
 * release dashboard reads them to show session + error/crash health per app across a release's projects.
 */
@Serializable
data class ProjectAnalyticsApplication(
    @Contextual
    val id: UUID = UUID.NIL,
    @ColumnName("project_id")
    @Contextual
    val projectId: UUID,
    @ColumnName("application_id")
    val applicationId: String,
)

/**
 * An analytics **service** a project owns — the service name used in `http.<service>.<class>` response-code
 * counters (e.g. `bosca`). A project can own several. Managed on the project; the release dashboard reads
 * them to show API response-code health per service across a release's projects.
 */
@Serializable
data class ProjectAnalyticsService(
    @Contextual
    val id: UUID = UUID.NIL,
    @ColumnName("project_id")
    @Contextual
    val projectId: UUID,
    val service: String,
)
