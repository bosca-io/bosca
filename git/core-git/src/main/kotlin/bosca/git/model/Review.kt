package bosca.git.model

import bosca.db.annotation.ColumnName
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * A review submitted on a pull request. Each review carries a verdict
 * ([ReviewStatus]) and an optional body. Review comments anchored to
 * specific diff lines are stored separately via the core-comments
 * infrastructure with diff-anchor metadata.
 */
@Serializable
data class Review(
    @Contextual val id: UUID = UUID.NIL,
    @Contextual @ColumnName("pull_request_id") val pullRequestId: UUID,
    @Contextual @ColumnName("reviewer_id") val reviewerId: UUID,
    val status: ReviewStatus,
    val body: String? = null,
    @Contextual @ColumnName("dismissed_at") val dismissedAt: OffsetDateTime? = null,
    @ColumnName("dismiss_reason") val dismissReason: String? = null,
    @Contextual val created: OffsetDateTime = OffsetDateTime.now()
)
