package bosca.git.model

import bosca.events.Event
import bosca.events.annotation.JobEvent
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/** First synchronization failure for a verified delivery; retries retain the same notification occurrence. */
@Serializable
@JobEvent(jobs = [])
data class GitHubSynchronizationFailed(
    @Contextual val repositoryId: UUID,
    val repositoryName: String,
    val deliveryId: String,
    val event: String,
    val problem: String,
    val recipientIds: Set<UUID> = emptySet(),
) : Event {
    override fun identityKey(): Any = deliveryId
}
