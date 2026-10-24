package bosca.git.model

import bosca.events.annotation.JobEvent
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Serializable

/** Announces a persisted commit status revision for event-driven synchronization. */
@JobEvent(jobs = [])
@Serializable
data class CommitStatusEvent(
    override val repositoryId: UUID,
    val statusId: UUID,
    val modified: OffsetDateTime,
) : GitEvent() {
    override fun identityKey(): Any = statusId
}
