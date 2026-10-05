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
    val isDraft: Boolean = false
)

/**
 * Input for updating a pull request's mutable fields.
 */
@Serializable
data class UpdatePullRequestInput(
    val title: String? = null,
    val description: String? = null
)

/**
 * Input for submitting a review on a pull request.
 */
@Serializable
data class SubmitReviewInput(
    @Contextual val pullRequestId: UUID,
    val status: ReviewStatus,
    val body: String? = null
)

/**
 * Input for adding a review comment anchored to a specific diff line.
 */
@Serializable
data class AddReviewCommentInput(
    @Contextual val reviewId: UUID,
    @Contextual val pullRequestId: UUID,
    val filePath: String,
    val oldLineNumber: Int? = null,
    val newLineNumber: Int? = null,
    val commitSha: String,
    val content: String
)
