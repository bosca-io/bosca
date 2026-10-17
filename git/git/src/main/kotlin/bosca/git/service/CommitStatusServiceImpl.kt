package bosca.git.service

import bosca.db.transaction
import bosca.git.model.CommitStatus
import bosca.git.model.CommitStatusEvent
import bosca.git.model.CommitStatusState
import bosca.git.model.dispatch
import bosca.git.repository.CommitStatusRepository
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation

/** Owns the individual checks displayed on pull requests and exported to paired repositories. */
@ServiceImplementation
class CommitStatusServiceImpl(
    private val statusRepository: CommitStatusRepository,
) : CommitStatusService {
    override suspend fun recordStatus(
        repositoryId: UUID, commitSha: String, context: String, state: CommitStatusState,
        description: String?, targetUrl: String?, ref: String?,
    ): CommitStatus = transaction {
        val changed = statusRepository.upsert(CommitStatus(
            repositoryId = repositoryId, commitSha = commitSha, context = context,
            state = state, description = description, targetUrl = targetUrl, ref = ref,
        ))
        if (changed != null) {
            CommitStatusEvent(changed.repositoryId, changed.id, changed.modified).dispatch()
            changed
        } else {
            requireNotNull(statusRepository.findByContext(repositoryId, commitSha, context))
        }
    }

    override suspend fun getStatuses(repositoryId: UUID, commitSha: String): List<CommitStatus> =
        statusRepository.findByCommitSha(repositoryId, commitSha)

    override suspend fun findById(id: UUID): CommitStatus? = statusRepository.findById(id)

    override suspend fun findChangedSince(repositoryId: UUID, since: OffsetDateTime, offset: Long, limit: Int): List<CommitStatus> =
        statusRepository.findChangedSince(repositoryId, since, offset, limit)

    override suspend fun areRequiredChecksPassing(repositoryId: UUID, commitSha: String, requiredContexts: List<String>): Boolean {
        val statuses = getStatuses(repositoryId, commitSha)
        return statuses.all { it.state == CommitStatusState.SUCCESS } &&
            statuses.map { it.context }.toSet().containsAll(requiredContexts)
    }
}
