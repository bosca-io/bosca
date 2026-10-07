package bosca.artifacts.model

import bosca.events.Event
import bosca.events.annotation.JobEvent
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/** A Docker tag pointing to a stored manifest, dispatched by the ordinary tag write path. */
@Serializable
@JobEvent(jobs = [], displayName = "Artifact Tag Published", description = "A Docker image tag has been published")
data class ArtifactTagPublished(
    @Contextual val id: UUID,
    @Contextual val repositoryId: UUID,
    @Contextual val versionId: UUID,
    val tagName: String,
    val manifestDigest: String,
    @Contextual val created: OffsetDateTime = OffsetDateTime.now(),
) : Event {
    override fun identityKey(): Any = id
}
