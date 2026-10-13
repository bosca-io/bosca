package bosca.git.model

import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/** Adds a diff-line comment, optionally verifying the version that the author inspected. */
@Serializable
data class AddReviewCommentInput(
    @Contextual val reviewId: UUID,
    @Contextual val pullRequestId: UUID,
    val filePath: String,
    val oldLineNumber: Int? = null,
    val newLineNumber: Int? = null,
    val commitSha: String,
    val content: String,
    val expectedVersion: Long? = null,
)
