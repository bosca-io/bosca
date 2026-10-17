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
        targetUrl: String? = null,
        ref: String? = null
    ): CommitStatus

    /**
     * Retrieves all status checks reported for a commit.
     */
    suspend fun getStatuses(repositoryId: UUID, commitSha: String): List<CommitStatus>

    /** Reads a persisted status by identity. */
    suspend fun findById(id: UUID): CommitStatus?

    /** Pages recent changes and pending checks for synchronization recovery. */
    suspend fun findChangedSince(repositoryId: UUID, since: bosca.serialization.OffsetDateTime, offset: Long, limit: Int): List<CommitStatus>

    /**
     * Requires every reported check to succeed, plus every explicitly required context to exist.
     * An empty list of reported checks passes only when no explicit contexts are required.
     */
    suspend fun areRequiredChecksPassing(
        repositoryId: UUID,
        commitSha: String,
        requiredContexts: List<String>
    ): Boolean
}
