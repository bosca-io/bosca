package bosca.git.model

import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * Input for creating a new pull request.
 */
@Serializable
data class CreatePullRequestInput(
    @Contextual val repositoryId: UUID,
    val title: String,
    val description: String? = null,
    val sourceBranch: String,
    val targetBranch: String,
    @Contextual val sourceRepositoryId: UUID? = null,
    val isDraft: Boolean = false,
    /** Overrides the repository default when supplied. */
    val deleteBranchOnMerge: Boolean? = null,
)
