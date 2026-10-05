package bosca.git.service

import bosca.git.model.CommitStatus
import bosca.git.model.CommitStatusState
import bosca.serialization.UUID
import bosca.service.Service

/**
 * Manages CI/CD commit status checks. Statuses are keyed by (repository, commit,
 * context) — updating the same context overwrites the previous state. Used by
 * branch protection to gate PR merges on required status checks passing.
 */
interface CommitStatusService : Service {

    /**
     * Records or updates a status check for a commit. If a status with the same
     * context already exists, it is overwritten.
     */
    suspend fun recordStatus(
        repositoryId: UUID,
        commitSha: String,
        context: String,
        state: CommitStatusState,
        description: String? = null,
        targetUrl: String? = null
    ): CommitStatus

    /**
     * Retrieves all status checks reported for a commit.
     */
    suspend fun getStatuses(repositoryId: UUID, commitSha: String): List<CommitStatus>

    /**
     * Checks whether all required status contexts have a SUCCESS state for the
     * given commit. Returns false if any required context is missing or not SUCCESS.
     */
    suspend fun areRequiredChecksPassing(
        repositoryId: UUID,
        commitSha: String,
        requiredContexts: List<String>
    ): Boolean
}
