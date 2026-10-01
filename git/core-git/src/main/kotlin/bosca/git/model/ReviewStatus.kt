package bosca.git.model

import bosca.db.annotation.DbMapper
import bosca.db.mapper.EnumMapper
import kotlinx.serialization.Serializable

/**
 * The verdict submitted with a pull request review. APPROVED counts toward
 * the required approval threshold. CHANGES_REQUESTED blocks merge when
 * approvals are required. COMMENT_ONLY leaves neither approval nor block.
 */
@DbMapper(ReviewStatusMapper::class)
@Serializable
enum class ReviewStatus {
    APPROVED,
    CHANGES_REQUESTED,
    COMMENT_ONLY
}

object ReviewStatusMapper : EnumMapper<ReviewStatus>({ ReviewStatus.valueOf(it.uppercase()) })
