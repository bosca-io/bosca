package bosca.workops.ui

import kotlinx.serialization.Serializable

/** An authorized application/version stream associated with a visible release project. */
@Serializable
data class WorkOpsReleaseLiveSource(
    val appId: String,
    val appVersion: String?,
    val projectId: String,
    val projectLabel: String,
)
