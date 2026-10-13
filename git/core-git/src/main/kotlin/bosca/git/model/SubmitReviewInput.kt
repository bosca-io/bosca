package bosca.git.model

import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/** Submits a review, optionally verifying the version that the reviewer inspected. */
@Serializable
data class SubmitReviewInput(
    @Contextual val pullRequestId: UUID,
    val status: ReviewStatus,
    val body: String? = null,
    val expectedVersion: Long? = null,
)
