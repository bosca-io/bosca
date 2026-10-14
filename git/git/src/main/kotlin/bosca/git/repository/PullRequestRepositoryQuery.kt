package bosca.git.repository

import bosca.git.model.PullRequestStatus
import bosca.serialization.UUID

/** Parameters for querying pull requests across a permission-filtered repository set. */
data class PullRequestRepositoryQuery(
    val repositoryIds: List<UUID>,
    val status: PullRequestStatus?,
    val authorId: UUID?,
    val offset: Long,
    val limit: Int
)
