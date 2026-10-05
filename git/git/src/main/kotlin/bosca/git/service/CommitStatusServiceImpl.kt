package bosca.git.service

import bosca.git.model.CommitStatus
import bosca.git.model.CommitStatusState
import bosca.git.repository.CommitStatusRepository
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation

/**
 * Persists and queries CI/CD status checks for commits, with upsert semantics
 * per (repository, commit, context) tuple.
 */
@ServiceImplementation
class CommitStatusServiceImpl(
    private val statusRepository: CommitStatusRepository
) : CommitStatusService {

    override suspend fun recordStatus(
        repositoryId: UUID,
        commitSha: String,
        context: String,
        state: CommitStatusState,
        description: String?,
        targetUrl: String?
    ): CommitStatus {
        val existing = statusRepository.findByContext(repositoryId, commitSha, context)
        return if (existing != null) {
            statusRepository.update(
                existing.copy(state = state, description = description, targetUrl = targetUrl)
            ) ?: existing
        } else {
            statusRepository.create(
                CommitStatus(
                    repositoryId = repositoryId,
                    commitSha = commitSha,
                    context = context,
                    state = state,
                    description = description,
                    targetUrl = targetUrl
                )
            )
        }
    }

    override suspend fun getStatuses(repositoryId: UUID, commitSha: String): List<CommitStatus> {
        return statusRepository.findByCommitSha(repositoryId, commitSha)
    }

    override suspend fun areRequiredChecksPassing(
        repositoryId: UUID,
        commitSha: String,
        requiredContexts: List<String>
    ): Boolean {
        if (requiredContexts.isEmpty()) return true
        val statuses = getStatuses(repositoryId, commitSha)
        val statusMap = statuses.associateBy { it.context }
        return requiredContexts.all { context ->
            statusMap[context]?.state == CommitStatusState.SUCCESS
        }
    }
}
