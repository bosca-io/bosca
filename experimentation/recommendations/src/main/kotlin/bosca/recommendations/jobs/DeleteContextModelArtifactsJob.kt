package bosca.recommendations.jobs

import bosca.queue.annotations.IJobDefinition
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/** Removes both artifact exports after a model's history deletion commits. */
@Serializable
data class DeleteContextModelArtifactsJob(@Contextual val contextId: UUID, val version: Long) : IJobDefinition
