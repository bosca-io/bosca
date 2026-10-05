package bosca.git.model

import bosca.db.annotation.DbMapper
import bosca.db.mapper.EnumMapper
import kotlinx.serialization.Serializable

/**
 * Lifecycle state of a pull request. State transitions:
 * DRAFT -> OPEN (marked ready for review),
 * OPEN -> MERGED (merge succeeds),
 * OPEN -> CLOSED (closed without merging),
 * CLOSED -> OPEN (reopened).
 * MERGED is terminal.
 */
@DbMapper(PullRequestStatusMapper::class)
@Serializable
enum class PullRequestStatus {
    OPEN,
    MERGED,
    CLOSED,
    DRAFT
}

object PullRequestStatusMapper : EnumMapper<PullRequestStatus>({ PullRequestStatus.valueOf(it.uppercase()) })
