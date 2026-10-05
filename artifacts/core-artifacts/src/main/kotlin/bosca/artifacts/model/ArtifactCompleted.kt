package bosca.artifacts.model

import bosca.events.Event
import bosca.events.annotation.JobEvent
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/** A stored artifact version whose producers have finished uploading all of its files. */
@Serializable
@JobEvent(
    jobs = [],
    displayName = "Artifact Completed",
    description = "An artifact version has finished uploading",
)
data class ArtifactCompleted(
    @Contextual val id: UUID,
    @Contextual val versionId: UUID,
    val jobIds: List<@Contextual UUID>,
    val commitSha: String,
    @Contextual val principalId: UUID?,
    @Contextual val created: OffsetDateTime = OffsetDateTime.now(),
) : Event {
    override fun identityKey(): Any = id
}
