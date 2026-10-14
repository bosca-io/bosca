package bosca.git.model

import kotlinx.serialization.Serializable

/** Updates metadata and, for open or draft requests, the branches being compared. */
@Serializable
data class UpdatePullRequestInput(
    val title: String? = null,
    val description: String? = null,
    val sourceBranch: String? = null,
    val targetBranch: String? = null,
    val expectedVersion: Long? = null,
)
