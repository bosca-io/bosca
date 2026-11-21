package bosca.workops.ui

import kotlinx.serialization.Serializable

/** The actual committed file returned by the existing deploy-config setup operation. */
@Serializable
data class WorkOpsProjectDeployConfig(
    val repositoryId: String,
    val repositoryName: String,
    val path: String,
    val branch: String,
    val sha: String,
    val content: String,
)
