package bosca.workops.ui

import kotlinx.serialization.Serializable

/** An authorized project-to-application/service link; application versions come from the bundle's owning catalog. */
@Serializable
data class WorkOpsReleaseTelemetrySource(
    val projectId: String,
    val projectLabel: String,
    val identifier: String,
    val versionId: String = "",
    val versionName: String = "",
)
